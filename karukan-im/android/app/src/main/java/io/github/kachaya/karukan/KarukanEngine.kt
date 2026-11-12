package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

// -------------------------------------------------------------------------------------------------
// Region: Data Structures & Callbacks
// -------------------------------------------------------------------------------------------------

data class LearningItem(
    val reading: String,
    val surface: String,
    val count: Int = 1,
    val timestamp: Long = 0L
)

data class PresetDictItem(
    val file: File,
    val name: String,
    val isEnabled: Boolean,
    val sizeBytes: Long
)

data class DictEntryItem(
    val reading: String,
    val surface: String,
    val pos: String = "",
    val comment: String = ""
)


fun interface DictLoadProgressListener {
    fun onProgress(message: String, percent: Int)
}

/**
 * karukan AI かな漢字変換エンジンの JNI ブリッジクラス
 */
class KarukanEngine(private val context: Context) {

    private var isLoaded = false
    private val executor = Executors.newSingleThreadExecutor()
    private val fileIoExecutor = Executors.newSingleThreadExecutor()
    private var learningCache: MutableList<LearningItem>? = null
    private val learningLock = Any()

    private var currentLoadedModelName: String? = null
    private val userDictFileManager = UserDictFileManager(context)

    companion object {
        private const val TAG = "KarukanEngine"
        private const val DEFAULT_MODEL_NAME = "jinen-v2-xsmall-Q8_0.gguf"
        private const val TOKENIZER_NAME = "tokenizer.json"
        private const val SYSTEM_DICT_FILE_NAME = "dict.bin"
        private const val LEARNING_HISTORY_FILE = "learning_history.txt"

        private val globalModelLock = Any()

        // -----------------------------------------------------------------------------------------
        // Region: Asset & Dictionary File Preparation Delegation
        // -----------------------------------------------------------------------------------------

        @JvmStatic
        @JvmOverloads
        fun prepareSystemDictAsset(
            context: Context,
            forceOverwrite: Boolean = false,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ): String? = EngineAssetPreparer.prepareSystemDictAsset(context, forceOverwrite, onProgress)

        @JvmStatic
        @JvmOverloads
        fun prepareAssetFile(
            context: Context,
            fileName: String,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ): String? = EngineAssetPreparer.prepareAssetFile(context, fileName, onProgress)

        @JvmStatic
        @JvmOverloads
        fun prepareUserDictAssets(
            context: Context,
            forceOverwrite: Boolean = false,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ) = EngineAssetPreparer.prepareUserDictAssets(context, forceOverwrite, onProgress)

        private var isLibraryLoaded = false

        init {
            try {
                try {
                    System.loadLibrary("c++_shared")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "c++_shared library load skipped or not needed: ${e.message}")
                }
                System.loadLibrary("karukan_android")
                isLibraryLoaded = true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load native library karukan_android", e)
            }
        }

        fun calculateOptimalNThreads(): Int {
            val processors = Runtime.getRuntime().availableProcessors()
            return when {
                processors >= 8 -> 4
                processors >= 4 -> 3
                else -> maxOf(1, processors - 1)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Model Switching & Progress
    // ---------------------------------------------------------------------------------------------

    fun interface OnEngineLoadProgressListener {
        fun onProgress(message: String, percent: Int)
    }

    @Volatile
    var loadProgressListener: OnEngineLoadProgressListener? = null

    @Volatile
    var currentLoadStatus: String = ""
        private set

    @Volatile
    var currentLoadPercent: Int = 100
        private set

    @Volatile
    var isPreparing: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun reportLoadProgress(msg: String, percent: Int) {
        currentLoadStatus = msg
        currentLoadPercent = percent
        mainHandler.post {
            loadProgressListener?.onProgress(msg, percent)
        }
    }

    init {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val initialModel = prefs.getString("pref_model_name", DEFAULT_MODEL_NAME) ?: DEFAULT_MODEL_NAME
        switchModel(initialModel)
    }

    @JvmOverloads
    fun switchModel(modelName: String, onProgress: ((message: String, percent: Int) -> Unit)? = null) {
        val targetModelName = modelName.ifEmpty { DEFAULT_MODEL_NAME }
        if (currentLoadedModelName == targetModelName && isLoaded) {
            reportLoadProgress("準備完了", 100)
            onProgress?.invoke("準備完了", 100)
            return
        }

        isPreparing = true
        executor.execute {
            synchronized(globalModelLock) {
                fun report(msg: String, percent: Int) {
                    reportLoadProgress(msg, percent)
                    onProgress?.invoke(msg, percent)
                }

                try {
                    if (!isLibraryLoaded) {
                        Log.e(TAG, "Cannot initialize native model: libkarukan_android.so is not loaded")
                        isPreparing = false
                        return@execute
                    }

                    report("Tokenizer 準備中...", 5)
                    prepareAssetFile(context, TOKENIZER_NAME) { text, subPercent ->
                        val overall = 5 + (subPercent * 5 / 100)
                        report(text, overall)
                    }

                    report("モデルファイル準備中...", 10)
                    val modelPath = prepareAssetFile(context, targetModelName) { text, subPercent ->
                        val overall = 10 + (subPercent * 60 / 100)
                        report(text, overall)
                    }

                    if (modelPath != null) {
                        report("モデル初期化中...", 70)
                        val success = nativeInit(modelPath)
                        isLoaded = success
                        if (isLoaded) {
                            currentLoadedModelName = targetModelName
                            report("システム辞書ロード中...", 80)
                            loadSystemDict { progressText, subPercent ->
                                val overall = 80 + (subPercent * 10 / 100)
                                report(progressText, overall)
                            }
                            report("ユーザー辞書ロード中...", 90)
                            loadUserDicts { progressText, subPercent ->
                                val overall = 90 + (subPercent * 10 / 100)
                                report(progressText, overall)
                            }
                            isPreparing = false
                            report("準備完了", 100)
                        } else {
                            Log.e(TAG, "KarukanEngine nativeInit failed for $targetModelName")
                            isPreparing = false
                            report("モデル初期化失敗", 100)
                        }
                    } else {
                        Log.e(TAG, "Model file preparation failed ($targetModelName)")
                        isPreparing = false
                        report("モデルファイル準備失敗", 100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching model to $targetModelName", e)
                    isPreparing = false
                    report("モデル設定エラー", 100)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: System & User Dictionary Management Delegation
    // ---------------------------------------------------------------------------------------------

    fun getSystemDictFile(): File {
        return File(context.filesDir, SYSTEM_DICT_FILE_NAME)
    }

    fun loadSystemDict(onProgress: ((progressText: String, percent: Int) -> Unit)? = null) {
        if (!isLibraryLoaded) return
        try {
            prepareSystemDictAsset(context, false) { progressText, percent ->
                onProgress?.invoke(progressText, percent / 2)
            }
            val systemDictFile = getSystemDictFile()
            if (systemDictFile.exists() && systemDictFile.length() > 0) {
                val nativeListener = DictLoadProgressListener { message, percent ->
                    val totalPercent = 50 + (percent / 2)
                    onProgress?.invoke(message, totalPercent)
                }
                nativeLoadSystemDict(systemDictFile.absolutePath, nativeListener)
            } else {
                Log.w(TAG, "System dict file does not exist: ${systemDictFile.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading system dict", e)
        }
    }

    fun getUserDictDir(): File {
        return userDictFileManager.getUserDictDir()
    }

    fun getLearningHistoryFile(): File {
        return File(getUserDictDir(), LEARNING_HISTORY_FILE)
    }

    fun loadUserDicts(onProgress: ((progressText: String, percent: Int) -> Unit)? = null) {
        if (!isLibraryLoaded) return
        try {
            prepareUserDictAssets(context, false) { progressText, percent ->
                onProgress?.invoke(progressText, percent / 2)
            }
            val dir = getUserDictDir()
            val nativeListener = DictLoadProgressListener { message, percent ->
                val totalPercent = 50 + (percent / 2)
                onProgress?.invoke(message, totalPercent)
            }
            nativeLoadUserDicts(dir.absolutePath, nativeListener)

            val learningFile = getLearningHistoryFile()
            if (learningFile.exists()) {
                nativeLoadLearning(learningFile.absolutePath)
            }

            synchronized(learningLock) {
                learningCache = null
            }

            onProgress?.invoke("ロード完了", 100)
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading user dicts", e)
        }
    }

    fun getEntriesFromDictFile(file: File): List<DictEntryItem> =
        userDictFileManager.getEntriesFromDictFile(file)

    fun addEntryToDictFile(
        file: File,
        reading: String,
        surface: String,
        pos: String = "",
        comment: String = ""
    ): Boolean = userDictFileManager.addEntryToDictFile(file, reading, surface, pos, comment) {
        loadUserDicts()
    }

    fun removeEntryFromDictFile(file: File, reading: String, surface: String): Boolean =
        userDictFileManager.removeEntryFromDictFile(file, reading, surface) {
            loadUserDicts()
        }

    fun updateEntryInDictFile(
        file: File,
        oldReading: String,
        oldSurface: String,
        newReading: String,
        newSurface: String
    ): Boolean = userDictFileManager.updateEntryInDictFile(file, oldReading, oldSurface, newReading, newSurface) {
        loadUserDicts()
    }

    fun importDictFile(inputStream: InputStream, fileName: String, onProgress: ((progressText: String, percent: Int) -> Unit)? = null): Boolean =
        userDictFileManager.importDictFile(inputStream, fileName) { _, _ ->
            loadUserDicts(onProgress)
        }

    fun getPresetDictItems(): List<PresetDictItem> =
        userDictFileManager.getPresetDictItems()

    fun togglePresetDict(item: PresetDictItem, enable: Boolean, onProgress: ((progressText: String, percent: Int) -> Unit)? = null): Boolean =
        userDictFileManager.togglePresetDict(item, enable) { progressText, percent ->
            loadUserDicts(onProgress)
        }

    fun deletePresetDict(item: PresetDictItem, onProgress: ((progressText: String, percent: Int) -> Unit)? = null): Boolean =
        userDictFileManager.deletePresetDict(item) { progressText, percent ->
            loadUserDicts(onProgress)
        }

    // ---------------------------------------------------------------------------------------------
    // Region: Conversion & Processing APIs
    // ---------------------------------------------------------------------------------------------

    fun hiraganaToKatakana(hiragana: String): String {
        if (hiragana.isEmpty()) return hiragana
        val sb = StringBuilder(hiragana.length)
        for (c in hiragana) {
            if (c in '\u3041'..'\u3096' || c in '\u309D'..'\u309E') {
                sb.append((c.code + 0x60).toChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun romajiToHiragana(query: String): String {
        if (query.isEmpty() || !isLibraryLoaded) return query
        return try {
            nativeRomajiToHiragana(query)
        } catch (e: Throwable) {
            Log.e(TAG, "Error converting romaji: $query", e)
            query
        }
    }

    fun romajiToHiraganaFlush(query: String): String {
        if (query.isEmpty() || !isLibraryLoaded) return query
        return try {
            nativeRomajiToHiraganaFlush(query)
        } catch (e: Throwable) {
            Log.e(TAG, "Error flushing romaji: $query", e)
            query
        }
    }

    fun convert(
        query: String,
        maxCandidates: Int = 9,
        context: String = "",
        queryId: Long = 0,
        sourceFilter: Int = 0
    ): List<String> {
        if (query.isBlank() || !isLibraryLoaded || !isLoaded) return listOf(query)

        return try {
            val joinedCandidates = nativeConvert(query, maxCandidates, context, queryId, sourceFilter)
            if (joinedCandidates.isNotEmpty()) {
                joinedCandidates.split("\t")
            } else {
                listOf(query)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error converting query: $query, queryId: $queryId", e)
            listOf(query)
        }
    }


    fun cancelQuery(queryId: Long) {
        if (!isLibraryLoaded || queryId <= 0) return
        try {
            nativeCancelQuery(queryId)
        } catch (e: Throwable) {
            Log.e(TAG, "Error cancelling query: $queryId", e)
        }
    }

    fun resetEngine() {
        if (!isLibraryLoaded) return
        try {
            nativeResetEngine()
        } catch (e: Throwable) {
            Log.e(TAG, "Error resetting engine", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Learning History Persistence
    // ---------------------------------------------------------------------------------------------

    fun getLearningEntries(): List<LearningItem> {
        synchronized(learningLock) {
            if (learningCache == null) {
                learningCache = loadLearningEntriesFromFile().toMutableList()
            }
            return learningCache!!.toList()
        }
    }

    private fun loadLearningEntriesFromFile(): List<LearningItem> {
        val file = getLearningHistoryFile()
        if (!file.exists()) return emptyList()

        val list = mutableListOf<LearningItem>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("\t")
                    if (parts.size >= 2) {
                        val reading = parts[0].trim()
                        val surface = parts[1].trim()
                        val count = if (parts.size >= 3) parts[2].trim().toIntOrNull() ?: 1 else 1
                        val timestamp = if (parts.size >= 4) parts[3].trim().toLongOrNull() ?: 0L else 0L
                        if (reading.isNotEmpty() && surface.isNotEmpty()) {
                            list.add(LearningItem(reading, surface, count, timestamp))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading learning entries from file", e)
        }
        return list
    }

    fun removeLearningEntry(reading: String, surface: String) {
        val r = reading.trim()
        val s = surface.trim()

        if (isLibraryLoaded && isLoaded) {
            try {
                nativeRemoveLearning(r, s)
            } catch (e: Throwable) {
                Log.e(TAG, "Error removing native learning entry: $r -> $s", e)
            }
        }

        synchronized(learningLock) {
            learningCache?.removeAll { it.reading == r && it.surface == s }
        }

        fileIoExecutor.execute {
            if (isLibraryLoaded && isLoaded) {
                nativeSaveLearning(getLearningHistoryFile().absolutePath)
            }
        }
    }

    fun recordLearning(reading: String, surface: String) {
        val r = reading.trim()
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty()) return

        if (isLibraryLoaded && isLoaded) {
            try {
                nativeRecordLearning(r, s)
            } catch (e: Throwable) {
                Log.e(TAG, "Error recording learning: $r -> $s", e)
            }
        }

        synchronized(learningLock) {
            if (learningCache == null) {
                learningCache = loadLearningEntriesFromFile().toMutableList()
            }
            val cache = learningCache!!
            val index = cache.indexOfFirst { it.reading == r && it.surface == s }
            if (index >= 0) {
                val existing = cache.removeAt(index)
                cache.add(0, LearningItem(r, s, existing.count + 1))
            } else {
                cache.add(0, LearningItem(r, s, 1))
            }
        }

        fileIoExecutor.execute {
            if (isLibraryLoaded && isLoaded) {
                nativeSaveLearning(getLearningHistoryFile().absolutePath)
            }
        }
    }

    fun clearLearning() {
        if (isLibraryLoaded && isLoaded) {
            try {
                nativeClearLearning()
            } catch (e: Throwable) {
                Log.e(TAG, "Error clearing learning cache", e)
            }
        }

        synchronized(learningLock) {
            learningCache = mutableListOf()
        }

        fileIoExecutor.execute {
            try {
                val file = getLearningHistoryFile()
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting learning history file", e)
            }
        }
    }

    fun backupLearningToStream(outputStream: OutputStream) {
        val sourceFile = getLearningHistoryFile()
        if (sourceFile.exists()) {
            sourceFile.inputStream().use { input ->
                input.copyTo(outputStream)
            }
        } else {
            outputStream.write("# karukan 学習履歴データ (TSV形式)\n".toByteArray(Charsets.UTF_8))
        }
    }

    fun restoreLearningFromStream(inputStream: InputStream): Boolean {
        try {
            val destFile = getLearningHistoryFile()
            destFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            if (isLibraryLoaded && isLoaded) {
                nativeLoadLearning(destFile.absolutePath)
            }

            synchronized(learningLock) {
                learningCache = null
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring learning history stream", e)
            return false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Emoji Categories & Utilities
    // ---------------------------------------------------------------------------------------------

    @Volatile
    private var cachedEmojiCategories: Map<String, List<String>>? = null

    fun getEmojiCategories(): Map<String, List<String>> {
        cachedEmojiCategories?.let { return it }
        synchronized(this) {
            cachedEmojiCategories?.let { return it }
            val categories = LinkedHashMap<String, List<String>>()
            val paint = Paint()
            try {
                context.assets.open("emoji_categories.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val name = parts[0].trim()
                            val emojis = parts[1].split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() && paint.hasGlyph(it) }
                            if (emojis.isNotEmpty()) {
                                categories[name] = emojis
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading emoji categories from assets", e)
            }
            cachedEmojiCategories = categories
            return categories
        }
    }

    fun calculateOptimalNThreads(): Int = Companion.calculateOptimalNThreads()

    fun updateEngineConfig(settings: KarukanSettings) {
        if (!isLibraryLoaded) return
        if (settings.modelName.isNotEmpty() && settings.modelName != currentLoadedModelName && !isPreparing) {
            switchModel(settings.modelName)
        }
        try {
            nativeUpdateEngineConfig(
                nThreads = settings.nThreads,
                strategy = settings.strategy,
                maxLatencyMs = settings.maxLatencyMs,
                chunkChars = settings.chunkChars,
                chunkSymbols = settings.chunkSymbols,
                chunkDigits = settings.chunkDigits,
                chunkAlphabets = settings.chunkAlphabets,
                beamWidth = settings.beamWidth,
                beamChars = settings.beamChars,
                persona = settings.persona,
                contextChars = settings.contextChars,
                useContext = settings.useContext,
                punctuation = settings.punctuationStyle,
                bracket = settings.bracketStyle,
                slash = settings.slashStyle,
                space = settings.spaceStyle,
                kanaSymbolWidth = settings.kanaSymbolWidth,
                asciiSymbolWidth = settings.asciiWidth,
                digitWidth = settings.digitWidth,
                maxLearningEntries = settings.maxLearningEntries,
                maxLearningSurfaceChars = settings.maxLearningSurfaceChars,
                isLearningEnabled = settings.isLearningEnabled,
                isDateConversionEnabled = settings.isDateConversionEnabled
            )
            nativeSetDateConfigJson(settings.dateConfigJson)
        } catch (e: Throwable) {
            Log.e(TAG, "Error updating engine config", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: External Native Function Declarations
    // ---------------------------------------------------------------------------------------------

    @Suppress("JniMissingFunction")
    private external fun nativeInit(modelPath: String): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeLoadSystemDict(dictPath: String, listener: DictLoadProgressListener? = null): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeLoadUserDicts(dictDirPath: String, listener: DictLoadProgressListener? = null): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeUpdateEngineConfig(
        nThreads: Int,
        strategy: String,
        maxLatencyMs: Int,
        chunkChars: Int,
        chunkSymbols: Int,
        chunkDigits: Int,
        chunkAlphabets: Int,
        beamWidth: Int,
        beamChars: Int,
        persona: String,
        contextChars: Int,
        useContext: Boolean,
        punctuation: String,
        bracket: String,
        slash: String,
        space: String,
        kanaSymbolWidth: String,
        asciiSymbolWidth: String,
        digitWidth: String,
        maxLearningEntries: Int,
        maxLearningSurfaceChars: Int,
        isLearningEnabled: Boolean,
        isDateConversionEnabled: Boolean
    )

    fun toHankakuKatakana(input: String): String {
        if (!isLibraryLoaded || input.isEmpty()) return TextTransformUtils.toHankakuKatakanaFallback(input)
        return try {
            nativeToHankakuKatakana(input)
        } catch (_: Throwable) {
            TextTransformUtils.toHankakuKatakanaFallback(input)
        }
    }

    fun toZenkakuAlphanumeric(input: String): String {
        if (!isLibraryLoaded || input.isEmpty()) return TextTransformUtils.toZenkakuAlphanumericFallback(input)
        return try {
            nativeToZenkakuAlphanumeric(input)
        } catch (_: Throwable) {
            TextTransformUtils.toZenkakuAlphanumericFallback(input)
        }
    }

    @Suppress("JniMissingFunction")
    private external fun nativeToHankakuKatakana(input: String): String

    @Suppress("JniMissingFunction")
    private external fun nativeToZenkakuAlphanumeric(input: String): String

    @Suppress("JniMissingFunction")
    private external fun nativeSetDateConfigJson(json: String)


    @Suppress("JniMissingFunction")
    private external fun nativeCancelQuery(queryId: Long)

    @Suppress("JniMissingFunction")
    private external fun nativeResetEngine()

    @Suppress("JniMissingFunction")
    private external fun nativeConvert(
        query: String,
        maxCandidates: Int,
        context: String,
        queryId: Long,
        sourceFilter: Int
    ): String

    @Suppress("JniMissingFunction")
    private external fun nativeRecordLearning(reading: String, surface: String)

    @Suppress("JniMissingFunction")
    private external fun nativeRemoveLearning(reading: String, surface: String): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeSaveLearning(filePath: String): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeLoadLearning(filePath: String): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeClearLearning()

    @Suppress("JniMissingFunction")
    private external fun nativeRomajiToHiragana(query: String): String

    @Suppress("JniMissingFunction")
    private external fun nativeRomajiToHiraganaFlush(query: String): String
}
