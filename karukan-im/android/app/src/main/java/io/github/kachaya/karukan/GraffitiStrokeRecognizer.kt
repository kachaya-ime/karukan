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

    data class DetailedRecognitionResult(
        val result: RecognitionResult,
        val matchedTemplatePoints: List<Point>? = null,
        val matchedTemplateName: String? = null,
        val hasSelfIntersection: Boolean = false
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
        val hasIntersection: Boolean = false
    )

    private val templates = mutableListOf<Template>()

    /** Punctuation Shift (記号入力) モードの状態 */
    var isPunctuationMode: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Region: Path Movement & Intersection Utilities
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
     * ストロークの各線分同士がクロスしているかを判定します。
     */
    fun hasSelfIntersection(points: List<Point>): Boolean {
        if (points.size < 4) return false

        for (i in 0 until points.size - 2) {
            val p1 = points[i]
            val p2 = points[i + 1]

            for (j in i + 2 until points.size - 1) {
                val p3 = points[j]
                val p4 = points[j + 1]

                if (isSegmentIntersecting(p1, p2, p3, p4)) {
                    return true
                }
            }
        }
        return false
    }

    private fun ccw(a: Point, b: Point, c: Point): Float {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

    private fun isSegmentIntersecting(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
        val ccw1 = ccw(p1, p2, p3)
        val ccw2 = ccw(p1, p2, p4)
        val ccw3 = ccw(p3, p4, p1)
        val ccw4 = ccw(p3, p4, p2)

        return ((ccw1 > 0f && ccw2 < 0f) || (ccw1 < 0f && ccw2 > 0f)) &&
                ((ccw3 > 0f && ccw4 < 0f) || (ccw3 < 0f && ccw4 > 0f))
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
        val hasSelfIntersection: Boolean = false,
    )

    fun findMinBoundingBoxByRotation(points: Collection<Point>): MinBoundingBoxResult? {
        if (points.isEmpty()) return null

        val start = points.first()

        var minScaledArea = Float.MAX_VALUE
        var bestScaledWidth = 0f
        var bestScaledHeight = 0f
        var bestAngleDeg = 0
        var bestScaledRotatedPoints = emptyList<Point>()
        var bestRawRotatedPoints = emptyList<Point>()
        var bestRawWidth = 0f
        var bestRawHeight = 0f
        var bestRawArea = 0f

        for (angleDeg in 0 until 360 step 90) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val cos = kotlin.math.cos(rad)
            val sin = kotlin.math.sin(rad)

            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE

            val rawRotatedPoints = ArrayList<Point>(points.size)

            for (p in points) {
                val dx = p.x - start.x
                val dy = p.y - start.y
                val rx = (dx * cos - dy * sin).toFloat()
                val ry = (dx * sin + dy * cos).toFloat()
                rawRotatedPoints.add(Point(rx, ry))

                if (rx < minX) minX = rx.toDouble()
                if (rx > maxX) maxX = rx.toDouble()
                if (ry < minY) minY = ry.toDouble()
                if (ry > maxY) maxY = ry.toDouble()
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

            if (scaledArea < minScaledArea) {
                minScaledArea = scaledArea
                bestScaledWidth = scaledWidth
                bestScaledHeight = scaledHeight
                bestAngleDeg = angleDeg
                bestRawWidth = w
                bestRawHeight = h
                bestRawArea = rawArea
                bestRawRotatedPoints = rawRotatedPoints

                bestScaledRotatedPoints = rawRotatedPoints.map {
                    Point(
                        x = it.x * scale,
                        y = it.y * scale
                    )
                }
            }
        }

        val (scaledDx, scaledDy) = computeTotalMovement(bestScaledRotatedPoints)
        val (rawDx, rawDy) = computeTotalMovement(bestRawRotatedPoints)
        val hasIntersection = hasSelfIntersection(bestScaledRotatedPoints)

        return MinBoundingBoxResult(
            width = bestScaledWidth,
            height = bestScaledHeight,
            area = minScaledArea,
            angleDeg = bestAngleDeg,
            rotatedPoints = bestScaledRotatedPoints,
            rawWidth = bestRawWidth,
            rawHeight = bestRawHeight,
            rawArea = bestRawArea,
            pointCount = points.size,
            totalDx = scaledDx,
            totalDy = scaledDy,
            rawTotalDx = rawDx,
            rawTotalDy = rawDy,
            hasSelfIntersection = hasIntersection
        )
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

        val result = run {
            val minBoxResult = findMinBoundingBoxByRotation(sampledRaw) ?: return@run RecognitionResult.None
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

            data class MatchResult(
                val template: Template,
                val dist: Float,
                val result: RecognitionResult
            )

            val allMatches = templates.map { template ->
                val res = when (targetCategory) {
                    TargetCategory.ALPHA -> template.alphaResult
                    TargetCategory.NUMERIC -> template.numericResult
                    TargetCategory.PUNCTUATION -> template.punctuationResult
                }
                val dist = pathDistance(translated, template.points)
                MatchResult(template, dist, res)
            }.sortedBy { it.dist }

            val validMatches = allMatches.filter { it.result != RecognitionResult.None }
            val bestMatch = validMatches.firstOrNull()
            if (bestMatch != null) {
                matchedPoints = bestMatch.template.points
                matchedName = bestMatch.template.name
                return@run bestMatch.result
            }

            RecognitionResult.None
        }

        val inputHasIntersection = hasSelfIntersection(sampledRaw)

        if (result is RecognitionResult.PunctuationShift) {
            this.isPunctuationMode = !this.isPunctuationMode
        } else if (result != RecognitionResult.None) {
            this.isPunctuationMode = false
        }

        return DetailedRecognitionResult(
            result = result,
            matchedTemplatePoints = matchedPoints,
            matchedTemplateName = matchedName,
            hasSelfIntersection = inputHasIntersection
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
        val hasIntersection = hasSelfIntersection(sampled)
        templates.add(
            Template(
                name = name,
                points = translated,
                alphaResult = alphaRes,
                numericResult = numericRes,
                punctuationResult = punctuationRes,
                totalDx = totalDx,
                totalDy = totalDy,
                hasIntersection = hasIntersection
            )
        )
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

    internal fun clearTemplates() {
        templates.clear()
    }

    fun parseSvgPathToPoints(pathData: String): List<Point> {
        val points = mutableListOf<Point>()
        var currentX = 0f
        var currentY = 0f

        val regex = Regex("([a-zA-Z])|([-+]?[0-9]*\\.?[0-9]+)")
        val matches = regex.findAll(pathData).map { it.value }.toList()

        var idx = 0
        var cmd = ' '
        while (idx < matches.size) {
            val token = matches[idx]
            if (token.length == 1 && token[0].isLetter()) {
                cmd = token[0]
                idx++
            }

            when (cmd) {
                'm', 'M' -> {
                    if (idx + 1 < matches.size) {
                        val x = matches[idx].toFloatOrNull() ?: 0f
                        val y = matches[idx + 1].toFloatOrNull() ?: 0f
                        idx += 2
                        if (cmd == 'm' && points.isNotEmpty()) {
                            currentX += x
                            currentY += y
                        } else {
                            currentX = x
                            currentY = y
                        }
                        points.add(Point(currentX, currentY))
                        cmd = if (cmd == 'm') 'l' else 'L'
                    } else {
                        idx++
                    }
                }
                'l', 'L' -> {
                    if (idx + 1 < matches.size) {
                        val x = matches[idx].toFloatOrNull() ?: 0f
                        val y = matches[idx + 1].toFloatOrNull() ?: 0f
                        idx += 2
                        if (cmd == 'l') {
                            currentX += x
                            currentY += y
                        } else {
                            currentX = x
                            currentY = y
                        }
                        points.add(Point(currentX, currentY))
                    } else {
                        idx++
                    }
                }
                'h', 'H' -> {
                    if (idx < matches.size) {
                        val x = matches[idx].toFloatOrNull() ?: 0f
                        idx++
                        if (cmd == 'h') {
                            currentX += x
                        } else {
                            currentX = x
                        }
                        points.add(Point(currentX, currentY))
                    } else {
                        idx++
                    }
                }
                'v', 'V' -> {
                    if (idx < matches.size) {
                        val y = matches[idx].toFloatOrNull() ?: 0f
                        idx++
                        if (cmd == 'v') {
                            currentY += y
                        } else {
                            currentY = y
                        }
                        points.add(Point(currentX, currentY))
                    } else {
                        idx++
                    }
                }
                'c', 'C' -> {
                    if (idx + 5 < matches.size) {
                        val x1 = matches[idx].toFloatOrNull() ?: 0f
                        val y1 = matches[idx + 1].toFloatOrNull() ?: 0f
                        val x2 = matches[idx + 2].toFloatOrNull() ?: 0f
                        val y2 = matches[idx + 3].toFloatOrNull() ?: 0f
                        val x = matches[idx + 4].toFloatOrNull() ?: 0f
                        val y = matches[idx + 5].toFloatOrNull() ?: 0f
                        idx += 6

                        val startX = currentX
                        val startY = currentY
                        val endX = if (cmd == 'c') currentX + x else x
                        val endY = if (cmd == 'c') currentY + y else y
                        val ctrl1X = if (cmd == 'c') currentX + x1 else x1
                        val ctrl1Y = if (cmd == 'c') currentY + y1 else y1
                        val ctrl2X = if (cmd == 'c') currentX + x2 else x2
                        val ctrl2Y = if (cmd == 'c') currentY + y2 else y2

                        for (step in 1..5) {
                            val t = step / 5f
                            val mt = 1f - t
                            val bx = mt * mt * mt * startX + 3 * mt * mt * t * ctrl1X + 3 * mt * t * t * ctrl2X + t * t * t * endX
                            val by = mt * mt * mt * startY + 3 * mt * mt * t * ctrl1Y + 3 * mt * t * t * ctrl2Y + t * t * t * endY
                            points.add(Point(bx, by))
                        }
                        currentX = endX
                        currentY = endY
                    } else {
                        idx++
                    }
                }
                else -> idx++
            }
        }
        return points
    }

    fun parseSvgPath(pathData: String): Path {
        return PathParser.createPathFromPathData(pathData)
    }

    fun loadTemplatesFromContext(context: Context) {
        try {
            val jsonString = context.assets.open("graffiti_templates.json")
                .bufferedReader().use { it.readText() }
            loadTemplatesFromJsonString(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load graffiti_templates.json from assets", e)
        }
    }

    fun loadTemplatesFromJsonString(jsonString: String) {
        templates.clear()
        val rootArray = JSONArray(jsonString)
        for (i in 0 until rootArray.length()) {
            val obj = rootArray.getJSONObject(i)
            val name = obj.optString("name", "template_$i")

            val points = mutableListOf<Point>()
            val pathData = obj.optString("path", obj.optString("svgPath", ""))
            if (pathData.isNotEmpty()) {
                points.addAll(parseSvgPathToPoints(pathData))
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
    }
}
