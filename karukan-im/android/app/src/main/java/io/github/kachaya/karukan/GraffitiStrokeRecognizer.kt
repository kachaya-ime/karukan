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
import kotlin.math.sqrt

/**
 * Palm OS の Graffiti 1 (unistroke) ジェスチャ認識エンジン。
 * ベーステンプレート (graffiti_templates.json) から高次元特徴量ベクトルを抽出し、
 * 機械学習型・重み付き k-NN 分類器（Feature-based Weighted k-NN）により高精度な認識を行います。
 *
 * 免責事項：
 * Palm、Palm OS、および Graffiti はそれぞれの権利者に帰属する
 * 商標または登録商標です。
 *
 * 本アプリは Palm、PalmSource、ACCESS の公式製品ではありません。
 * 本アプリは独自実装であり、これらの企業との提携・認証・承認を
 * 受けているものではありません。
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

    // ---------------------------------------------------------------------------------------------
    // Region: ML Training Sample & Feature Model
    // ---------------------------------------------------------------------------------------------

    class TrainingSample(
        val name: String,
        val alphaResult: RecognitionResult,
        val numericResult: RecognitionResult,
        val punctuationResult: RecognitionResult,
        val points: List<Point>, // Centered points for drawing/debugging
        val featureVector: FloatArray
    )

    private val alphaTrainingSamples = mutableListOf<TrainingSample>()
    private val numericTrainingSamples = mutableListOf<TrainingSample>()
    private val punctuationTrainingSamples = mutableListOf<TrainingSample>()
    private var isTemplatesLoaded = false

    /** Punctuation Shift (記号入力) モードの状態 */
    var isPunctuationMode: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Region: Stroke Metrics & Feature Extraction
    // ---------------------------------------------------------------------------------------------

    data class StrokeMetrics(
        val totalDx: Float,
        val totalDy: Float,
        val pathLength: Float
    )

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

    fun computeStartEndDistance(points: List<Point>): Float {
        if (points.size < 2) return 0f
        val start = points.first()
        val end = points.last()
        return hypot(end.x - start.x, end.y - start.y)
    }

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

    /**
     * 機械学習用高次元特徴量ベクトル (Feature Vector) を生成します。
     * 正規化した点座標 (64次元) + グローバル形状メトリクス (pathLength, startEndDistance, totalDx, totalDy)
     */
    fun extractFeatureVector(rawPoints: List<Point>): FloatArray? {
        val features = extractFeatures(rawPoints) ?: return null
        val pts = features.centeredPoints
        val vec = FloatArray(pts.size * 2 + 4)
        for (i in pts.indices) {
            vec[i * 2] = pts[i].x / SQUARE_SIZE
            vec[i * 2 + 1] = pts[i].y / SQUARE_SIZE
        }
        val offset = pts.size * 2
        vec[offset] = features.pathLength / SQUARE_SIZE
        vec[offset + 1] = features.startEndDistance / SQUARE_SIZE
        vec[offset + 2] = features.totalDx / SQUARE_SIZE
        vec[offset + 3] = features.totalDy / SQUARE_SIZE
        return vec
    }

    fun featureDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return Float.MAX_VALUE
        var sum = 0f
        for (i in v1.indices) {
            val diff = v1[i] - v2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Recognition Engine (Weighted k-NN Classifier)
    // ---------------------------------------------------------------------------------------------

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
        val inputFeatureVector = extractFeatureVector(rawPoints)
            ?: return DetailedRecognitionResult(RecognitionResult.None)

        val targetCategory = determineTargetCategory(inputFeatures.sampledPoints, bounds, isPunctuationMode)

        val (minX, minY, maxX, maxY) = computeBoundingBox(rawPoints)
        val maxRawDim = max(maxX - minX, maxY - minY)
        if (rawPoints.size < 3 || maxRawDim < 25f) {
            val defaultRes = if (targetCategory == TargetCategory.PUNCTUATION) {
                RecognitionResult.CharResult(".")
            } else {
                RecognitionResult.PunctuationShift
            }
            if (defaultRes is RecognitionResult.PunctuationShift) {
                this.isPunctuationMode = !this.isPunctuationMode
            } else if (defaultRes != RecognitionResult.None) {
                this.isPunctuationMode = false
            }
            return DetailedRecognitionResult(defaultRes)
        }

        val trainingSamples = when (targetCategory) {
            TargetCategory.ALPHA -> alphaTrainingSamples
            TargetCategory.NUMERIC -> numericTrainingSamples
            TargetCategory.PUNCTUATION -> punctuationTrainingSamples
        }

        // 全トレーニングサンプルを対象に機械学習型特徴量マッチング（k-NN）を実行（事前フィルタリングなしで柔軟性とロバスト性を最大化）
        val candidates = trainingSamples

        data class ScoredMatch(
            val sample: TrainingSample,
            val dist: Float,
            val result: RecognitionResult
        )

        val scoredMatches = candidates.map { sample ->
            val res = when (targetCategory) {
                TargetCategory.ALPHA -> sample.alphaResult
                TargetCategory.NUMERIC -> sample.numericResult
                TargetCategory.PUNCTUATION -> sample.punctuationResult
            }
            val dist = featureDistance(inputFeatureVector, sample.featureVector)
            ScoredMatch(sample, dist, res)
        }.sortedBy { it.dist }

        val top5 = scoredMatches.take(5) // k-NN (k=5)
        logI(TAG, "Top 5 Matching Templates:")
        top5.forEachIndexed { idx, match ->
            logI(TAG, "  #${idx + 1}: template=${match.sample.name}, result=${match.result}, dist=${"%.3f".format(match.dist)}")
        }

        // 重み付き投票 (Weighted k-NN)
        val classScores = mutableMapOf<RecognitionResult, Float>()
        for ((idx, match) in top5.withIndex()) {
            if (match.result == RecognitionResult.None) continue
            val weight = 1.0f / (match.dist + 0.001f)
            val score = weight * (6 - idx) // 上位ほどボーナス
            classScores[match.result] = (classScores[match.result] ?: 0f) + score
        }

        val bestResultEntry = classScores.maxByOrNull { it.value }
        val bestResult = bestResultEntry?.key ?: RecognitionResult.None

        val bestMatchSample = top5.firstOrNull { it.result == bestResult }?.sample
            ?: top5.firstOrNull()?.sample

        logI(
            TAG,
            "ML Recognition -> Category: $targetCategory, " +
                    "BestResult: $bestResult (Score: ${bestResultEntry?.value ?: 0f}), " +
                    "MatchedTemplate: ${bestMatchSample?.name ?: "None"}, " +
                    "Candidates: ${candidates.size}/${trainingSamples.size}"
        )

        if (bestResult is RecognitionResult.PunctuationShift) {
            this.isPunctuationMode = !this.isPunctuationMode
        } else if (bestResult != RecognitionResult.None) {
            this.isPunctuationMode = false
        }

        return DetailedRecognitionResult(
            result = bestResult,
            matchedTemplatePoints = bestMatchSample?.points,
            matchedTemplateName = bestMatchSample?.name,
            startRegion = inputFeatures.startRegion,
            endRegion = inputFeatures.endRegion
        )
    }

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
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        return points.map { Point(it.x - cx, it.y - cy) }
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
    // Region: Template & ML Model Loading Engine
    // ---------------------------------------------------------------------------------------------

    internal fun addTrainingSample(
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
        val featureVector = extractFeatureVector(rawPoints) ?: return

        val sample = TrainingSample(
            name = name,
            alphaResult = alphaRes,
            numericResult = numericRes,
            punctuationResult = punctuationRes,
            points = features.centeredPoints,
            featureVector = featureVector
        )

        if (alphaRes != RecognitionResult.None) {
            alphaTrainingSamples.add(sample)
        }
        if (numericRes != RecognitionResult.None) {
            numericTrainingSamples.add(sample)
        }
        if (punctuationRes != RecognitionResult.None) {
            punctuationTrainingSamples.add(sample)
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

    internal fun addRotatedTrainingSamples(
        baseName: String,
        basePoints: List<Point>,
        specs: List<RotationResultSpec>
    ) {
        val specMap = specs.associateBy { it.angleDeg }
        for (angleDeg in 0 until 360 step 90) {
            val spec = specMap[angleDeg] ?: RotationResultSpec(angleDeg, "none", "none", "none")
            val rotated = rotatePoints(basePoints, angleDeg)
            addTrainingSample(
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

    fun reloadTemplates(context: Context) {
        isTemplatesLoaded = false
        alphaTrainingSamples.clear()
        numericTrainingSamples.clear()
        punctuationTrainingSamples.clear()
        loadTemplatesFromContext(context)
    }

    fun loadTemplatesFromJsonString(jsonString: String) {
        alphaTrainingSamples.clear()
        numericTrainingSamples.clear()
        punctuationTrainingSamples.clear()
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
                    val alpha = rObj.optString("a", rObj.optString("alpha", "none"))
                    val numeric = rObj.optString("n", rObj.optString("numeric", "none"))
                    val punctuation = rObj.optString("p", rObj.optString("punctuation", "none"))
                    specs.add(RotationResultSpec(angle, alpha, numeric, punctuation))
                }
            } else {
                val alpha = obj.optString("a", obj.optString("alpha", "none"))
                val numeric = obj.optString("n", obj.optString("numeric", "none"))
                val punctuation = obj.optString("p", obj.optString("punctuation", "none"))
                specs.add(RotationResultSpec(0, alpha, numeric, punctuation))
            }

            addRotatedTrainingSamples(name, points, specs)
        }

        isTemplatesLoaded = true
        logTrainingModelSummary()
    }

    fun logTrainingModelSummary() {
        val totalCount = alphaTrainingSamples.size + numericTrainingSamples.size + punctuationTrainingSamples.size
        logI(TAG, "ML Training Model Summary (Total Samples: $totalCount):")
        logI(TAG, "  ALPHA training samples: ${alphaTrainingSamples.size} entries")
        logI(TAG, "  NUMERIC training samples: ${numericTrainingSamples.size} entries")
        logI(TAG, "  PUNCTUATION training samples: ${punctuationTrainingSamples.size} entries")
    }
}
