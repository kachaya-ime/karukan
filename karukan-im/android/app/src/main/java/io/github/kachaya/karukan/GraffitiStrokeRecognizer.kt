package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.PathParser
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Palm OS の Graffiti 1 (unistroke) ジェスチャ認識エンジン。
 * stroke_help1.png (英字), stroke_help2.png (数字・制御), stroke_help3.png (記号・修飾) のストローク定義に準拠。
 */
object GraffitiStrokeRecognizer {

    // ---------------------------------------------------------------------------------------------
    // Region: Point & Result Data Structures
    // ---------------------------------------------------------------------------------------------

    data class Point(val x: Float, val y: Float)

    enum class TargetCategory {
        ALPHA,       // 英字
        NUMERIC,     // 数字
        PUNCTUATION  // 記号
    }

    sealed interface RecognitionResult {
        data class CharResult(val char: String) : RecognitionResult
        data object Space : RecognitionResult
        data object Backspace : RecognitionResult
        data object Return : RecognitionResult
        data object Shift : RecognitionResult
        data object Caps : RecognitionResult
        data object DpadLeft : RecognitionResult
        data object DpadRight : RecognitionResult
        data object DpadUp : RecognitionResult
        data object DpadDown : RecognitionResult
        data object Ctrl : RecognitionResult
        data object PunctuationShift : RecognitionResult
        data object Tab : RecognitionResult
        data object Cancel : RecognitionResult
        data object Menu : RecognitionResult
        data object Ext : RecognitionResult
        data object None : RecognitionResult
    }

    enum class GridRegion(val displayName: String) {
        TOP_LEFT("左上"),
        TOP_CENTER("中央上"),
        TOP_RIGHT("右上"),
        CENTER_LEFT("左中央"),
        CENTER("中央"),
        CENTER_RIGHT("右中央"),
        BOTTOM_LEFT("左下"),
        BOTTOM_CENTER("中央下"),
        BOTTOM_RIGHT("右下")
    }

    data class DetailedRecognitionResult(
        val result: RecognitionResult,
        val matchedTemplatePoints: List<Point>? = null,
        val matchedTemplateName: String? = null,
        val startRegion: GridRegion = GridRegion.CENTER,
        val endRegion: GridRegion = GridRegion.CENTER
    )

    private const val TAG = "GraffitiStrokeRecognizer"
    private const val SQUARE_SIZE = 100f
    private const val DEFAULT_SAMPLING_COUNT = 32

    private data class Template(
        val name: String,
        val points: List<Point>,
        val alphaResult: RecognitionResult,
        val numericResult: RecognitionResult,
        val punctuationResult: RecognitionResult,
        val totalDx: Float = 0f,
        val totalDy: Float = 0f,
        val startRegion: GridRegion = GridRegion.CENTER,
        val endRegion: GridRegion = GridRegion.CENTER
    )

    private val alphaTemplates = mutableListOf<Template>()
    private val numericTemplates = mutableListOf<Template>()
    private val punctuationTemplates = mutableListOf<Template>()
    private var isTemplatesLoaded = false

    /** Punctuation Shift (記号入力) モードの状態 */
    var isPunctuationMode: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Region: Path Movement Utilities
    // ---------------------------------------------------------------------------------------------

    /**
     * ストロークの各セグメントにおける移動量の絶対値の和（水平成分 totalDx, 垂直成分 totalDy）を求めます。
     */
    fun computeTotalMovement(points: List<Point>): Pair<Float, Float> {
        var totalDx = 0f
        var totalDy = 0f
        for (i in 0 until points.size - 1) {
            totalDx += abs(points[i + 1].x - points[i].x)
            totalDy += abs(points[i + 1].y - points[i].y)
        }
        return Pair(totalDx, totalDy)
    }

    /**
     * 正規化後の座標 (0..squareSize, 0..squareSize) における点の 3x3 グリッド領域を取得します。
     * 分割比率は 3:4:3 (端 30%, 中央 40%, 端 30%) です。
     * （左上, 中央上, 右上, 左中央, 中央, 右中央, 左下, 中央下, 右下）
     */
    fun getGridRegion(p: Point, squareSize: Float = SQUARE_SIZE): GridRegion {
        val col = when {
            p.x < squareSize * 0.3f -> 0
            p.x < squareSize * 0.7f -> 1
            else -> 2
        }
        val row = when {
            p.y < squareSize * 0.3f -> 0
            p.y < squareSize * 0.7f -> 1
            else -> 2
        }
        return when (row * 3 + col) {
            0 -> GridRegion.TOP_LEFT
            1 -> GridRegion.TOP_CENTER
            2 -> GridRegion.TOP_RIGHT
            3 -> GridRegion.CENTER_LEFT
            4 -> GridRegion.CENTER
            5 -> GridRegion.CENTER_RIGHT
            6 -> GridRegion.BOTTOM_LEFT
            7 -> GridRegion.BOTTOM_CENTER
            else -> GridRegion.BOTTOM_RIGHT
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Bounding Box & Rotation Calculations
    // ---------------------------------------------------------------------------------------------

    data class MinBoundingBoxResult(
        val width: Float,
        val height: Float,
        val area: Float,
        val angleDeg: Int,
        val rotatedPoints: List<Point>,
        val rawWidth: Float = 0f,
        val rawHeight: Float = 0f,
        val rawArea: Float = 0f,
        val pointCount: Int = 0,
        val totalDx: Float = 0f,
        val totalDy: Float = 0f,
        val rawTotalDx: Float = 0f,
        val rawTotalDy: Float = 0f,
        val startRegion: GridRegion = GridRegion.CENTER,
        val endRegion: GridRegion = GridRegion.CENTER
    )

    fun findBoundingBox(points: Collection<Point>): MinBoundingBoxResult? {
        if (points.isEmpty()) return null

        val start = points.first()

        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        val rawRotatedPoints = ArrayList<Point>(points.size)

        for (p in points) {
            val dx = (p.x - start.x).toDouble()
            val dy = (p.y - start.y).toDouble()
            rawRotatedPoints.add(Point(dx.toFloat(), dy.toFloat()))

            if (dx < minX) minX = dx
            if (dx > maxX) maxX = dx
            if (dy < minY) minY = dy
            if (dy > maxY) maxY = dy
        }

        val w = (maxX - minX).toFloat()
        val h = (maxY - minY).toFloat()
        val rawArea = w * h

        val maxAbs = max(
            max(abs(minX), abs(maxX)),
            max(abs(minY), abs(maxY))
        ).toFloat()

        val scale = if (maxAbs > 0f) 100f / maxAbs else 1f
        val scaledWidth = w * scale
        val scaledHeight = h * scale
        val scaledArea = scaledWidth * scaledHeight

        val scaledRotatedPoints = rawRotatedPoints.map {
            Point(
                x = it.x * scale,
                y = it.y * scale
            )
        }

        val (scaledDx, scaledDy) = computeTotalMovement(scaledRotatedPoints)
        val (rawDx, rawDy) = computeTotalMovement(rawRotatedPoints)
        val startRegion = if (scaledRotatedPoints.isNotEmpty()) getGridRegion(scaledRotatedPoints.first()) else GridRegion.CENTER
        val endRegion = if (scaledRotatedPoints.isNotEmpty()) getGridRegion(scaledRotatedPoints.last()) else GridRegion.CENTER

        return MinBoundingBoxResult(
            width = scaledWidth,
            height = scaledHeight,
            area = scaledArea,
            angleDeg = 0,
            rotatedPoints = scaledRotatedPoints,
            rawWidth = w,
            rawHeight = h,
            rawArea = rawArea,
            pointCount = points.size,
            totalDx = scaledDx,
            totalDy = scaledDy,
            rawTotalDx = rawDx,
            rawTotalDy = rawDy,
            startRegion = startRegion,
            endRegion = endRegion
        )
    }

    fun findMinBoundingBoxByRotation(points: Collection<Point>): MinBoundingBoxResult? {
        return findBoundingBox(points)
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Template Matching Engine
    // ---------------------------------------------------------------------------------------------

    fun determineTargetCategory(rawPoints: List<Point>, bounds: RectF, isPunctuationMode: Boolean): TargetCategory {
        if (isPunctuationMode) {
            return TargetCategory.PUNCTUATION
        }
        if (rawPoints.isEmpty()) return TargetCategory.ALPHA

        val start = rawPoints.first()
        val midX = bounds.left + (bounds.right - bounds.left) / 2f
        return if (start.x < midX) TargetCategory.ALPHA else TargetCategory.NUMERIC
    }

    object PathSampler {
        fun sample(path: Path): List<Point> {
            val pts = ArrayList<Point>()
            val pm = PathMeasure(path, false)
            val length = pm.length
            if (length <= 0f) {
                val approx = path.approximate(0.5f)
                if (approx != null && approx.size >= 3) {
                    return listOf(Point(approx[1], approx[2]))
                }
                return emptyList()
            }
            val interval = length / (DEFAULT_SAMPLING_COUNT - 1)
            val pos = FloatArray(2)
            for (i in 0 until DEFAULT_SAMPLING_COUNT) {
                val dist = minOf(i * interval, length)
                pm.getPosTan(dist, pos, null)
                pts.add(Point(pos[0], pos[1]))
            }
            return pts
        }
    }

    fun recognize(path: Path, bounds: RectF): RecognitionResult {
        return recognizeDetailed(path, bounds).result
    }

    fun recognizeDetailed(path: Path, bounds: RectF): DetailedRecognitionResult {
        val rawPoints = PathSampler.sample(path)
        return recognizeDetailed(rawPoints, bounds)
    }

    fun recognizeDetailed(rawPoints: List<Point>, bounds: RectF): DetailedRecognitionResult {
        if (rawPoints.isEmpty()) return DetailedRecognitionResult(RecognitionResult.None)

        val sampledRaw = samplePoints(rawPoints, DEFAULT_SAMPLING_COUNT)
        val targetCategory = determineTargetCategory(sampledRaw, bounds, isPunctuationMode)

        var matchedPoints: List<Point>? = null
        var matchedName: String? = null

        data class MatchResult(
            val template: Template,
            val dist: Float,
            val result: RecognitionResult
        )

        var top3Matches = emptyList<MatchResult>()

        val result = run {
            val minBoxResult = findBoundingBox(sampledRaw) ?: return@run RecognitionResult.None
            val maxRawDim = max(minBoxResult.rawWidth, minBoxResult.rawHeight)
            if (sampledRaw.size < 3 || maxRawDim < 25f) {
                return@run if (targetCategory == TargetCategory.PUNCTUATION) {
                    RecognitionResult.CharResult(".")
                } else {
                    RecognitionResult.PunctuationShift
                }
            }

            val scaled = scaleToSquare(sampledRaw, SQUARE_SIZE)
            val translated = translateToOrigin(scaled)
            val inputEndRegion = if (scaled.isNotEmpty()) getGridRegion(scaled.last()) else GridRegion.CENTER

            val targetTemplates = when (targetCategory) {
                TargetCategory.ALPHA -> alphaTemplates
                TargetCategory.NUMERIC -> numericTemplates
                TargetCategory.PUNCTUATION -> punctuationTemplates
            }

            // endRegionが一致しないテンプレートは解析候補から完全に除外する
            val candidateTemplates = targetTemplates.filter { it.endRegion == inputEndRegion }

            val validMatches = candidateTemplates.map { template ->
                val res = when (targetCategory) {
                    TargetCategory.ALPHA -> template.alphaResult
                    TargetCategory.NUMERIC -> template.numericResult
                    TargetCategory.PUNCTUATION -> template.punctuationResult
                }
                val dist = pathDistance(translated, template.points)
                MatchResult(template, dist, res)
            }.sortedBy { it.dist }

            top3Matches = validMatches.take(3)

            val bestMatch = validMatches.firstOrNull()
            if (bestMatch != null) {
                matchedPoints = bestMatch.template.points
                matchedName = bestMatch.template.name
                return@run bestMatch.result
            }

            RecognitionResult.None
        }

        val scaledInput = scaleToSquare(sampledRaw, SQUARE_SIZE)
        val (scaledDx, scaledDy) = computeTotalMovement(scaledInput)
        val minBoxResult = findBoundingBox(sampledRaw)
        val inputStartRegion = if (scaledInput.isNotEmpty()) getGridRegion(scaledInput.first()) else GridRegion.CENTER
        val inputEndRegion = if (scaledInput.isNotEmpty()) getGridRegion(scaledInput.last()) else GridRegion.CENTER

        logI(
            TAG,
            "Input Stroke Features -> Category: $targetCategory, PointCount: ${rawPoints.size} (Sampled: ${sampledRaw.size}), " +
            "Movement: (dx=${"%.1f".format(scaledDx)}, dy=${"%.1f".format(scaledDy)}), RawBounds: (w=${"%.1f".format(minBoxResult?.rawWidth ?: 0f)}, h=${"%.1f".format(minBoxResult?.rawHeight ?: 0f)}), " +
            "StartRegion: ${inputStartRegion.displayName}, EndRegion: ${inputEndRegion.displayName}, " +
            "MatchedTemplate: ${matchedName ?: "None"}, Result: $result"
        )

        if (top3Matches.isNotEmpty()) {
            logI(TAG, "Top 3 Similar Templates:")
            top3Matches.forEachIndexed { idx, m ->
                logI(
                    TAG,
                    "  #${idx + 1}: ${m.template.name} -> result=${m.result}, dist=${"%.2f".format(m.dist)}, " +
                    "dx=${"%.1f".format(m.template.totalDx)}, dy=${"%.1f".format(m.template.totalDy)}, " +
                    "startRegion=${m.template.startRegion.displayName}, endRegion=${m.template.endRegion.displayName}"
                )
            }
        }

        if (result is RecognitionResult.PunctuationShift) {
            this.isPunctuationMode = !this.isPunctuationMode
        } else if (result != RecognitionResult.None) {
            this.isPunctuationMode = false
        }

        return DetailedRecognitionResult(
            result = result,
            matchedTemplatePoints = matchedPoints,
            matchedTemplateName = matchedName,
            startRegion = inputStartRegion,
            endRegion = inputEndRegion
        )
    }

    private fun mapStringToResult(str: String): RecognitionResult {
        return when (str) {
            "space" -> RecognitionResult.Space
            "backspace" -> RecognitionResult.Backspace
            "return" -> RecognitionResult.Return
            "shift" -> RecognitionResult.Shift
            "caps" -> RecognitionResult.Caps
            "dpad_left" -> RecognitionResult.DpadLeft
            "dpad_right" -> RecognitionResult.DpadRight
            "dpad_up" -> RecognitionResult.DpadUp
            "dpad_down" -> RecognitionResult.DpadDown
            "ctrl" -> RecognitionResult.Ctrl
            "tab" -> RecognitionResult.Tab
            "cancel" -> RecognitionResult.Cancel
            "menu" -> RecognitionResult.Menu
            "ext" -> RecognitionResult.Ext
            "none", "" -> RecognitionResult.None
            else -> RecognitionResult.CharResult(str)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: $1 Recognizer Geometry Calculations
    // ---------------------------------------------------------------------------------------------

    private fun computeBoundingBox(points: List<Point>): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun centroid(points: List<Point>): Point {
        var cx = 0f
        var cy = 0f
        for (p in points) {
            cx += p.x
            cy += p.y
        }
        return Point(cx / points.size, cy / points.size)
    }

    private fun scaleToSquare(points: List<Point>, size: Float): List<Point> {
        if (points.isEmpty()) return emptyList()

        val (minX, minY, maxX, maxY) = computeBoundingBox(points)
        val w = maxX - minX
        val h = maxY - minY
        val longSide = max(w, h)
        val shortSide = min(w, h)

        val isElongated = longSide > 0f && (shortSide / longSide < 0.25f)

        val scaleX: Float
        val scaleY: Float

        if (isElongated) {
            val scale = size / longSide
            scaleX = scale
            scaleY = scale
        } else {
            scaleX = if (w > 0f) size / w else 1f
            scaleY = if (h > 0f) size / h else 1f
        }

        return points.map { p ->
            Point(
                x = (p.x - minX) * scaleX,
                y = (p.y - minY) * scaleY
            )
        }
    }

    private fun translateToOrigin(points: List<Point>): List<Point> {
        val c = centroid(points)
        return points.map { Point(it.x - c.x, it.y - c.y) }
    }

    private fun pathDistance(pts1: List<Point>, pts2: List<Point>): Float {
        if (pts1.isEmpty() || pts2.isEmpty()) return Float.MAX_VALUE

        var d = 0f
        val count = min(pts1.size, pts2.size)
        for (i in 0 until count) {
            d += hypot(pts1[i].x - pts2[i].x, pts1[i].y - pts2[i].y)
        }
        return d / count
    }

    fun samplePoints(points: List<Point>, count: Int = DEFAULT_SAMPLING_COUNT): List<Point> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1 || count <= 1) return List(count) { points[0] }

        val dists = FloatArray(points.size)
        dists[0] = 0f
        for (i in 0 until points.size - 1) {
            val d = hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
            dists[i + 1] = dists[i] + d
        }
        val totalLen = dists.last()
        if (totalLen <= 0f) return List(count) { points[0] }

        val interval = totalLen / (count - 1)
        val result = ArrayList<Point>(count)
        result.add(points.first())

        var segIdx = 0
        for (i in 1 until count - 1) {
            val targetDist = i * interval
            while (segIdx < points.size - 2 && dists[segIdx + 1] < targetDist) {
                segIdx++
            }
            val segStartDist = dists[segIdx]
            val segEndDist = dists[segIdx + 1]
            val segLen = segEndDist - segStartDist
            val t = if (segLen > 0f) (targetDist - segStartDist) / segLen else 0f
            val p1 = points[segIdx]
            val p2 = points[segIdx + 1]
            val x = p1.x + t * (p2.x - p1.x)
            val y = p1.y + t * (p2.y - p1.y)
            result.add(Point(x, y))
        }
        result.add(points.last())
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Region: SVG Path Parsing & Asset Loading
    // ---------------------------------------------------------------------------------------------

    internal fun addTemplate(
        name: String = "",
        rawPoints: List<Point>,
        alpha: String = "none",
        numeric: String = "none",
        punctuation: String = "none"
    ) {
        val alphaRes = mapStringToResult(alpha)
        val numericRes = mapStringToResult(numeric)
        val punctuationRes = mapStringToResult(punctuation)

        if (alphaRes == RecognitionResult.None &&
            numericRes == RecognitionResult.None &&
            punctuationRes == RecognitionResult.None
        ) {
            return
        }

        val sampled = if (rawPoints.isNotEmpty()) {
            samplePoints(rawPoints, DEFAULT_SAMPLING_COUNT)
        } else {
            val path = Path()
            PathSampler.sample(path)
        }
        val scaled = scaleToSquare(sampled, SQUARE_SIZE)
        val translated = translateToOrigin(scaled)
        val (totalDx, totalDy) = computeTotalMovement(scaled)
        val startRegion = if (scaled.isNotEmpty()) getGridRegion(scaled.first()) else GridRegion.CENTER
        val endRegion = if (scaled.isNotEmpty()) getGridRegion(scaled.last()) else GridRegion.CENTER

        val template = Template(
            name = name,
            points = translated,
            alphaResult = alphaRes,
            numericResult = numericRes,
            punctuationResult = punctuationRes,
            totalDx = totalDx,
            totalDy = totalDy,
            startRegion = startRegion,
            endRegion = endRegion
        )

        if (alphaRes != RecognitionResult.None) {
            alphaTemplates.add(template)
        }
        if (numericRes != RecognitionResult.None) {
            numericTemplates.add(template)
        }
        if (punctuationRes != RecognitionResult.None) {
            punctuationTemplates.add(template)
        }
    }

    data class RotationResultSpec(
        val angleDeg: Int,
        val alpha: String,
        val numeric: String,
        val punctuation: String
    )

    private fun rotatePoints(points: List<Point>, angleDeg: Int): List<Point> {
        if (points.isEmpty()) return emptyList()
        val start = points.first()
        val rad = Math.toRadians(angleDeg.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()

        return points.map { p ->
            val dx = p.x - start.x
            val dy = p.y - start.y
            val rx = dx * cos - dy * sin
            val ry = dx * sin + dy * cos
            Point(rx + start.x, ry + start.y)
        }
    }

    internal fun addRotatedTemplates(
        baseName: String,
        basePoints: List<Point>,
        specs: List<RotationResultSpec>
    ) {
        val specMap = specs.associateBy { it.angleDeg }
        for (angleDeg in 0 until 360 step 90) {
            val spec = specMap[angleDeg] ?: RotationResultSpec(angleDeg, "none", "none", "none")
            val rotated = rotatePoints(basePoints, angleDeg)
            addTemplate(
                name = "${baseName}_$angleDeg",
                rawPoints = rotated,
                alpha = spec.alpha,
                numeric = spec.numeric,
                punctuation = spec.punctuation
            )
        }
    }



    fun parseSvgPath(pathData: String): Path {
        return PathParser.createPathFromPathData(pathData)
    }

    fun loadTemplatesFromContext(context: Context) {
        if (isTemplatesLoaded) return
        try {
            val jsonString = context.assets.open("graffiti_templates.json")
                .bufferedReader().use { it.readText() }
            loadTemplatesFromJsonString(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load graffiti_templates.json from assets", e)
        }
    }

    fun loadTemplatesFromJsonString(jsonString: String) {
        alphaTemplates.clear()
        numericTemplates.clear()
        punctuationTemplates.clear()
        isTemplatesLoaded = false
        val rootArray = JSONArray(jsonString)
        for (i in 0 until rootArray.length()) {
            val obj = rootArray.getJSONObject(i)
            val name = obj.optString("name", "template_$i")

            val points = mutableListOf<Point>()
            val pathData = obj.optString("path", obj.optString("svgPath", ""))
            if (pathData.isNotEmpty()) {
                val path = parseSvgPath(pathData)
                points.addAll(PathSampler.sample(path))
            } else if (obj.has("points")) {
                val pointsArray = obj.getJSONArray("points")
                for (j in 0 until pointsArray.length()) {
                    val pObj = pointsArray.getJSONObject(j)
                    val x = pObj.getDouble("x").toFloat()
                    val y = pObj.getDouble("y").toFloat()
                    points.add(Point(x, y))
                }
            }

            val specs = mutableListOf<RotationResultSpec>()
            if (obj.has("rotations")) {
                val rotationsArray = obj.getJSONArray("rotations")
                for (j in 0 until rotationsArray.length()) {
                    val rObj = rotationsArray.getJSONObject(j)
                    val angle = rObj.getInt("angle")
                    val alpha = rObj.optString("a", "none")
                    val numeric = rObj.optString("n", "none")
                    val punctuation = rObj.optString("p", "none")
                    specs.add(RotationResultSpec(angle, alpha, numeric, punctuation))
                }
            }

            addRotatedTemplates(name, points, specs)
        }

        isTemplatesLoaded = true
        logRotatedTemplates()
    }

    fun logRotatedTemplates() {
        val totalCount = alphaTemplates.size + numericTemplates.size + punctuationTemplates.size
        logI(TAG, "Categorized Rotated Templates List (Total Entries: $totalCount):")
        logI(TAG, "  ALPHA templates: ${alphaTemplates.size} entries")
        logI(TAG, "  NUMERIC templates: ${numericTemplates.size} entries")
        logI(TAG, "  PUNCTUATION templates: ${punctuationTemplates.size} entries")

        fun getSortKey(res: RecognitionResult): String {
            return when (res) {
                is RecognitionResult.CharResult -> res.char
                RecognitionResult.Space -> " "
                RecognitionResult.Backspace -> "\b"
                RecognitionResult.Return -> "\n"
                RecognitionResult.Tab -> "\t"
                RecognitionResult.Shift -> "[Shift]"
                RecognitionResult.Caps -> "[Caps]"
                RecognitionResult.Ctrl -> "[Ctrl]"
                RecognitionResult.PunctuationShift -> "[PuncShift]"
                RecognitionResult.DpadLeft -> "[DpadLeft]"
                RecognitionResult.DpadRight -> "[DpadRight]"
                RecognitionResult.DpadUp -> "[DpadUp]"
                RecognitionResult.DpadDown -> "[DpadDown]"
                RecognitionResult.Cancel -> "[Cancel]"
                RecognitionResult.Menu -> "[Menu]"
                RecognitionResult.Ext -> "[Ext]"
                RecognitionResult.None -> "[None]"
            }
        }

        fun logCategory(categoryName: String, list: List<Template>, getResult: (Template) -> RecognitionResult) {
            val sortedList = list.sortedWith(compareBy({ getSortKey(getResult(it)) }, { it.name }))
            logI(TAG, "--- $categoryName (${sortedList.size}) ---")
            sortedList.forEachIndexed { idx, t ->
                val res = getResult(t)
                val charCodeStr = if (res is RecognitionResult.CharResult && res.char.isNotEmpty()) {
                    " (code=${res.char[0].code})"
                } else ""
                logI(
                    TAG,
                    "[$categoryName #${idx + 1}] ${t.name} -> result=$res$charCodeStr, " +
                    "dx=${"%.1f".format(t.totalDx)}, dy=${"%.1f".format(t.totalDy)}, " +
                    "startRegion=${t.startRegion.displayName}, endRegion=${t.endRegion.displayName}"
                )
            }
        }

        logCategory("ALPHA", alphaTemplates) { it.alphaResult }
        logCategory("NUMERIC", numericTemplates) { it.numericResult }
        logCategory("PUNCTUATION", punctuationTemplates) { it.punctuationResult }
    }
}
