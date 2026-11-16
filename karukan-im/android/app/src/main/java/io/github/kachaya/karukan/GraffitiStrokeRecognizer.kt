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
        val endRegion: GridRegion = GridRegion.CENTER,
        val startEndDistance: Float = 0f,
        val pathLength: Float = 0f
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

    data class StrokeMetrics(
        val totalDx: Float,
        val totalDy: Float,
        val pathLength: Float
    )

    /**
     * ストロークの各セグメントにおける移動量（水平成分 totalDx, 垂直成分 totalDy）と正規化後のPath長を同時に求めます。
     */
    fun computeStrokeMetrics(points: List<Point>): StrokeMetrics {
        var totalDx = 0f
        var totalDy = 0f
        var pathLength = 0f
        for (i in 0 until points.size - 1) {
            val dx = points[i + 1].x - points[i].x
            val dy = points[i + 1].y - points[i].y
            totalDx += abs(dx)
            totalDy += abs(dy)
            pathLength += hypot(dx, dy)
        }
        return StrokeMetrics(totalDx, totalDy, pathLength)
    }

    /**
     * ストロークの各セグメントにおける移動量の絶対値の和（水平成分 totalDx, 垂直成分 totalDy）を求めます。
     */
    fun computeTotalMovement(points: List<Point>): Pair<Float, Float> {
        val metrics = computeStrokeMetrics(points)
        return Pair(metrics.totalDx, metrics.totalDy)
    }

    /**
     * 始点と終点間の直線距離（ユークリッド距離）を求めます。
     */
    fun computeStartEndDistance(points: List<Point>): Float {
        if (points.size < 2) return 0f
        val start = points.first()
        val end = points.last()
        return hypot(end.x - start.x, end.y - start.y)
    }

    /**
     * ストロークの正規化後のPath長（連続する点間のユークリッド距離の総和）を求めます。
     */
    fun computePathLength(points: List<Point>): Float {
        return computeStrokeMetrics(points).pathLength
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

    // ---------------------------------------------------------------------------------------------
    // Region: Stroke Features & Normalization Engine
    // ---------------------------------------------------------------------------------------------

    data class StrokeFeatures(
        val sampledPoints: List<Point>,
        val scaledPoints: List<Point>,
        val centeredPoints: List<Point>,
        val startRegion: GridRegion,
        val endRegion: GridRegion,
        val totalDx: Float,
        val totalDy: Float,
        val startEndDistance: Float,
        val pathLength: Float
    )

    /**
     * 入力点列およびテンプレート点列から、同一アルゴリズムで統一正規化特徴量を生成します。
     */
    fun extractFeatures(rawPoints: List<Point>): StrokeFeatures? {
        if (rawPoints.isEmpty()) return null
        val sampled = samplePoints(rawPoints, DEFAULT_SAMPLING_COUNT)
        if (sampled.isEmpty()) return null
        val scaled = scaleToSquare(sampled, SQUARE_SIZE)
        val centered = translateToOrigin(scaled)
        val metrics = computeStrokeMetrics(scaled)
        val startRegion = if (scaled.isNotEmpty()) getGridRegion(scaled.first()) else GridRegion.CENTER
        val endRegion = if (scaled.isNotEmpty()) getGridRegion(scaled.last()) else GridRegion.CENTER
        val startEndDistance = computeStartEndDistance(scaled)

        return StrokeFeatures(
            sampledPoints = sampled,
            scaledPoints = scaled,
            centeredPoints = centered,
            startRegion = startRegion,
            endRegion = endRegion,
            totalDx = metrics.totalDx,
            totalDy = metrics.totalDy,
            startEndDistance = startEndDistance,
            pathLength = metrics.pathLength
        )
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

        val inputFeatures = extractFeatures(rawPoints)
            ?: return DetailedRecognitionResult(RecognitionResult.None)

        val targetCategory = determineTargetCategory(inputFeatures.sampledPoints, bounds, isPunctuationMode)

        var matchedPoints: List<Point>? = null
        var matchedName: String? = null

        data class MatchResult(
            val template: Template,
            val dist: Float,
            val result: RecognitionResult
        )

        var top3Matches = emptyList<MatchResult>()

        val result = run {
            val (minX, minY, maxX, maxY) = computeBoundingBox(rawPoints)
            val maxRawDim = max(maxX - minX, maxY - minY)
            if (rawPoints.size < 3 || maxRawDim < 25f) {
                return@run if (targetCategory == TargetCategory.PUNCTUATION) {
                    RecognitionResult.CharResult(".")
                } else {
                    RecognitionResult.PunctuationShift
                }
            }

            val targetTemplates = when (targetCategory) {
                TargetCategory.ALPHA -> alphaTemplates
                TargetCategory.NUMERIC -> numericTemplates
                TargetCategory.PUNCTUATION -> punctuationTemplates
            }

            val candidateTemplates = mutableListOf<Template>()
            logI(
                TAG,
                "Evaluating ${targetTemplates.size} templates for category $targetCategory " +
                        "(start=${inputFeatures.startRegion.displayName}, end=${inputFeatures.endRegion.displayName}, " +
                        "dx=${"%.1f".format(inputFeatures.totalDx)}, dy=${"%.1f".format(inputFeatures.totalDy)}, " +
                        "startEndDist=${"%.1f".format(inputFeatures.startEndDistance)}, " +
                        "pathLen=${"%.1f".format(inputFeatures.pathLength)}):"
            )
            for (template in targetTemplates) {
                // 縦成分のみ
                if (inputFeatures.totalDx == 0f && template.totalDx != 0f) {
                    continue;
                }
                // 横成分のみ
                if (inputFeatures.totalDy == 0f && template.totalDy != 0f) {
                    continue;
                }
                // 開始位置の比較
                if (template.startRegion != inputFeatures.startRegion) {
                    continue
                }
                // 終了位置の比較
                if (template.endRegion != inputFeatures.endRegion) {
                    continue
                }
                // Path長
                if (abs(template.pathLength - inputFeatures.pathLength) > (SQUARE_SIZE * 0.9f)) {
                    continue
                }
                // 水平方向移動量
                if (template.totalDx != 0f) {
                    if (abs(template.totalDx - inputFeatures.totalDx) > (SQUARE_SIZE * 0.9f)) {
                        continue
                    }
                }
                // 垂直方向移動量
                if (template.totalDy != 0f) {
                    if (abs(template.totalDy - inputFeatures.totalDy) > (SQUARE_SIZE * 0.9f)) {
                        continue
                    }
                }

                candidateTemplates.add(template)
            }
            logI(TAG, "Filtered candidate templates count: ${candidateTemplates.size} / ${targetTemplates.size}")

            val validMatches = candidateTemplates.map { template ->
                val res = when (targetCategory) {
                    TargetCategory.ALPHA -> template.alphaResult
                    TargetCategory.NUMERIC -> template.numericResult
                    TargetCategory.PUNCTUATION -> template.punctuationResult
                }
                val dist = pathDistance(inputFeatures.centeredPoints, template.points)
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

        logI(
            TAG,
            "Input Stroke Features -> Category: $targetCategory, " +
                    "Movement: (dx=${"%.1f".format(inputFeatures.totalDx)}, dy=${"%.1f".format(inputFeatures.totalDy)}), " +
                    "StartRegion: ${inputFeatures.startRegion.displayName}, EndRegion: ${inputFeatures.endRegion.displayName}, " +
                    "StartEndDist: ${"%.1f".format(inputFeatures.startEndDistance)}, " +
                    "PathLen: ${"%.1f".format(inputFeatures.pathLength)}, " +
                    "MatchedTemplate: ${matchedName ?: "None"}, Result: $result"
        )

        if (top3Matches.isNotEmpty()) {
            logI(TAG, "Top 3 Similar Templates:")
            top3Matches.forEachIndexed { idx, m ->
                logI(
                    TAG,
                    "  #${idx + 1}: ${m.template.name} -> result=${m.result}, dist=${"%.2f".format(m.dist)}, " +
                            "dx=${"%.1f".format(m.template.totalDx)}, dy=${"%.1f".format(m.template.totalDy)}, " +
                            "startRegion=${m.template.startRegion.displayName}, endRegion=${m.template.endRegion.displayName}, " +
                            "startEndDist=${"%.1f".format(m.template.startEndDistance)}, " +
                            "pathLen=${"%.1f".format(m.template.pathLength)}"
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
            startRegion = inputFeatures.startRegion,
            endRegion = inputFeatures.endRegion
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

        if (w <= h * 0.25f) {
            val scaleY = if (h > 0f) size / h else 1f
            return points.map { p ->
                Point(
                    x = size / 2f,
                    y = (p.y - minY) * scaleY
                )
            }
        } else if (h <= w * 0.25f) {
            val scaleX = if (w > 0f) size / w else 1f
            return points.map { p ->
                Point(
                    x = (p.x - minX) * scaleX,
                    y = size / 2f
                )
            }
        }

        val scaleX = if (w > 0f) size / w else 1f
        val scaleY = if (h > 0f) size / h else 1f

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

        val features = extractFeatures(rawPoints) ?: return

        val template = Template(
            name = name,
            points = features.centeredPoints,
            alphaResult = alphaRes,
            numericResult = numericRes,
            punctuationResult = punctuationRes,
            totalDx = features.totalDx,
            totalDy = features.totalDy,
            startRegion = features.startRegion,
            endRegion = features.endRegion,
            startEndDistance = features.startEndDistance,
            pathLength = features.pathLength
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
                            "startRegion=${t.startRegion.displayName}, endRegion=${t.endRegion.displayName}, " +
                            "startEndDist=${"%.1f".format(t.startEndDistance)}, " +
                            "pathLen=${"%.1f".format(t.pathLength)}"
                )
            }
        }

        logCategory("ALPHA", alphaTemplates) { it.alphaResult }
        logCategory("NUMERIC", numericTemplates) { it.numericResult }
        logCategory("PUNCTUATION", punctuationTemplates) { it.punctuationResult }
    }
}
