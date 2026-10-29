package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

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

data class ProcessKeyResult(
    val consumed: Boolean,
    val commitText: String? = null,
    val hasPreedit: Boolean = false,
    val preeditText: String? = null,
    val caret: Int = 0,
    val hasCandidates: Boolean = false,
    val candidates: List<String>? = null,
    val hideCandidates: Boolean = false,
    val auxText: String? = null
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

    companion object {
        private const val TAG = "KarukanEngine"
        private const val DEFAULT_MODEL_NAME = "jinen-v2-xsmall-Q5_K_M.gguf"
        private const val TOKENIZER_NAME = "tokenizer.json"
        private const val SYSTEM_DICT_TGZ_NAME = "dict.tgz"
        private const val SYSTEM_DICT_FILE_NAME = "dict.bin"
        private const val USER_DICTS_DIR = "user_dicts"
        private const val LEARNING_HISTORY_FILE = "learning_history.txt"

        @JvmStatic
        @JvmOverloads
        fun prepareSystemDictAsset(
            context: Context,
            forceOverwrite: Boolean = false,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ): String? {
            val targetFile = File(context.filesDir, SYSTEM_DICT_FILE_NAME)
            if (!forceOverwrite && targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            return try {
                val assetList = context.assets.list("") ?: emptyArray()
                if (assetList.contains(SYSTEM_DICT_TGZ_NAME)) {
                    extractTarGzAsset(context, SYSTEM_DICT_TGZ_NAME, context.filesDir, onProgress)
                } else if (assetList.contains(SYSTEM_DICT_FILE_NAME)) {
                    prepareAssetFile(context, SYSTEM_DICT_FILE_NAME)
                }
                if (targetFile.exists() && targetFile.length() > 0) targetFile.absolutePath else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare system dict asset", e)
                null
            }
        }

        @JvmStatic
        fun prepareAssetFile(context: Context, fileName: String): String? {
            val targetFile = File(context.filesDir, fileName)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            return try {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare asset file: $fileName", e)
                if (targetFile.exists()) targetFile.delete()
                null
            }
        }

        @JvmStatic
        @JvmOverloads
        fun prepareUserDictAssets(
            context: Context,
            forceOverwrite: Boolean = false,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ) {
            val userDictDir = File(context.filesDir, USER_DICTS_DIR)
            if (!userDictDir.exists()) {
                userDictDir.mkdirs()
            }

            try {
                val list = context.assets.list(USER_DICTS_DIR) ?: emptyArray()
                for (fileName in list) {
                    val assetPath = "$USER_DICTS_DIR/$fileName"
                    val isTgz = fileName.endsWith(".tgz") || fileName.endsWith(".tar.gz")
                    val isGzip = fileName.endsWith(".gz") && !isTgz

                    if (isTgz) {
                        val targetFileName = "dict.bin"
                        val targetFile = File(userDictDir, targetFileName)
                        val disabledFile = File(userDictDir, "$targetFileName.disabled")

                        if (!forceOverwrite && ((targetFile.exists() && targetFile.length() > 0) || disabledFile.exists())) {
                            continue
                        }

                        try {
                            extractTarGzAsset(context, assetPath, userDictDir, onProgress)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to extract tgz user dict asset $fileName", e)
                        }
                    } else {
                        val targetFileName = if (isGzip) fileName.removeSuffix(".gz") else fileName
                        val targetFile = File(userDictDir, targetFileName)
                        val disabledFile = File(userDictDir, "$targetFileName.disabled")

                        if (!forceOverwrite && ((targetFile.exists() && targetFile.length() > 0) || disabledFile.exists())) {
                            continue
                        }

                        try {
                            context.assets.open(assetPath).use { rawInput ->
                                val input = if (isGzip) GZIPInputStream(rawInput) else rawInput
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to copy user dict asset $fileName", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No user_dicts in assets or error listing user dict assets: ${e.message}")
            }
        }

        @JvmStatic
        private fun extractTarGzAsset(
            context: Context,
            assetPath: String,
            targetDir: File,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ) {
            context.assets.open(assetPath).use { rawIn ->
                GZIPInputStream(rawIn).use { gzipIn ->
                    TarArchiveInputStream(gzipIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val fileName = File(entry.name).name
                                if (fileName == "dict.bin") {
                                    val outputFile = File(targetDir, fileName)
                                    val tempFile = File(targetDir, "$fileName.tmp")
                                    try {
                                        val totalSize = entry.size
                                        FileOutputStream(tempFile).use { output ->
                                            copyStreamWithProgress(tarIn, output, totalSize, onProgress)
                                        }
                                        if (outputFile.exists()) outputFile.delete()
                                        tempFile.renameTo(outputFile)
                                    } catch (e: Exception) {
                                        if (tempFile.exists()) tempFile.delete()
                                        throw e
                                    }
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }

        private fun copyStreamWithProgress(
            input: InputStream,
            output: FileOutputStream,
            totalBytes: Long,
            onProgress: ((progressText: String, percent: Int) -> Unit)? = null
        ) {
            val buffer = ByteArray(65536)
            var written = 0L
            var lastReportTime = 0L

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                written += read

                val now = System.currentTimeMillis()
                if (now - lastReportTime > 100 || (totalBytes > 0 && written == totalBytes)) {
                    lastReportTime = now
                    if (totalBytes > 0) {
                        val percent = ((written * 100) / totalBytes).toInt().coerceIn(0, 95)
                        val text = "展開中: ${formatFileSize(written)} / ${formatFileSize(totalBytes)} (${percent}%)"
                        onProgress?.invoke(text, percent)
                    }
                }
            }
        }

        private fun formatFileSize(size: Long): String {
            return DictUtils.formatFileSize(size)
        }

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
                processors >= 8 -> 4 // big.LITTLE構成の高性能コア群を想定
                processors >= 4 -> 3
                else -> maxOf(1, processors - 1)
            }
        }
    }

    init {
        val initialModel = PreferencesManager(context) {}.loadSettings().modelName
        switchModel(initialModel)
    }

    fun switchModel(modelName: String) {
        val targetModelName = modelName.ifEmpty { DEFAULT_MODEL_NAME }
        if (currentLoadedModelName == targetModelName && isLoaded) return

        executor.execute {
            try {
                if (!isLibraryLoaded) {
                    Log.e(TAG, "Cannot initialize native model: libkarukan_android.so is not loaded")
                    return@execute
                }

                prepareAssetFile(context, TOKENIZER_NAME)
                val modelPath = prepareAssetFile(context, targetModelName)

                if (modelPath != null) {
                    val success = nativeInit(modelPath)
                    isLoaded = success
                    if (isLoaded) {
                        currentLoadedModelName = targetModelName
                        loadSystemDict()
                        loadUserDicts()
                    } else {
                        Log.e(TAG, "KarukanEngine nativeInit failed for $targetModelName")
                    }
                } else {
                    Log.e(TAG, "Model file preparation failed ($targetModelName)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error switching model to $targetModelName", e)
            }
        }
    }

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
        val dir = File(context.filesDir, USER_DICTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
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

            // メモリ上の学習データキャッシュを復元
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


    fun getEntriesFromDictFile(file: File): List<DictEntryItem> {
        if (!file.exists()) return emptyList()

        val list = mutableListOf<DictEntryItem>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("\t")
                    if (parts.size >= 2) {
                        val reading = parts[0].trim()
                        val surface = parts[1].trim()
                        val pos = if (parts.size >= 3) parts[2].trim() else ""
                        val comment = if (parts.size >= 4) parts[3].trim() else ""
                        if (reading.isNotEmpty() && surface.isNotEmpty()) {
                            list.add(DictEntryItem(reading, surface, pos, comment))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading dict file: ${file.name}", e)
        }
        return list
    }

    fun addEntryToDictFile(
        file: File,
        reading: String,
        surface: String,
        pos: String = "",
        comment: String = ""
    ): Boolean {
        val r = reading.trim()
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty()) return false

        return try {
            val line = if (pos.isNotEmpty() || comment.isNotEmpty()) {
                "$r\t$s\t$pos\t$comment\n"
            } else {
                "$r\t$s\n"
            }
            file.appendText(line, Charsets.UTF_8)
            loadUserDicts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding entry to dict file: ${file.name}", e)
            false
        }
    }

    fun removeEntryFromDictFile(file: File, reading: String, surface: String): Boolean {
        val r = reading.trim()
        val s = surface.trim()
        if (!file.exists()) return false

        return try {
            val remainingLines = file.readLines(Charsets.UTF_8).filter { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    true
                } else {
                    val parts = trimmed.split("\t")
                    if (parts.size >= 2) {
                        !(parts[0].trim() == r && parts[1].trim() == s)
                    } else {
                        true
                    }
                }
            }
            val content = remainingLines.joinToString("\n") + if (remainingLines.isNotEmpty()) "\n" else ""
            file.writeText(content, Charsets.UTF_8)
            loadUserDicts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing entry from dict file: ${file.name}", e)
            false
        }
    }

    fun updateEntryInDictFile(
        file: File,
        oldReading: String,
        oldSurface: String,
        newReading: String,
        newSurface: String
    ): Boolean {
        val oldR = oldReading.trim()
        val oldS = oldSurface.trim()
        val newR = newReading.trim()
        val newS = newSurface.trim()
        if (!file.exists() || newR.isEmpty() || newS.isEmpty()) return false

        return try {
            val newLines = file.readLines(Charsets.UTF_8).map { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    line
                } else {
                    val parts = trimmed.split("\t")
                    if (parts.size >= 2 && parts[0].trim() == oldR && parts[1].trim() == oldS) {
                        val pos = if (parts.size >= 3) parts[2].trim() else ""
                        val comment = if (parts.size >= 4) parts[3].trim() else ""
                        if (pos.isNotEmpty() || comment.isNotEmpty()) {
                            "$newR\t$newS\t$pos\t$comment"
                        } else {
                            "$newR\t$newS"
                        }
                    } else {
                        line
                    }
                }
            }
            val content = newLines.joinToString("\n") + if (newLines.isNotEmpty()) "\n" else ""
            file.writeText(content, Charsets.UTF_8)
            loadUserDicts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating entry in dict file: ${file.name}", e)
            false
        }
    }


    fun importDictFile(inputStream: InputStream, fileName: String, onProgress: ((progressText: String, percent: Int) -> Unit)? = null): Boolean {
        return try {
            val targetFile = File(getUserDictDir(), fileName)
            targetFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            loadUserDicts(onProgress)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing dict file: $fileName", e)
            false
        }
    }

    fun getPresetDictItems(): List<PresetDictItem> {
        val dir = getUserDictDir()
        val files = dir.listFiles() ?: return emptyList()
        val list = mutableListOf<PresetDictItem>()

        for (file in files) {
            if (!file.isFile) continue
            val fileName = file.name
            if (fileName == LEARNING_HISTORY_FILE || fileName == SYSTEM_DICT_FILE_NAME || fileName == "$SYSTEM_DICT_FILE_NAME.disabled") {
                continue
            }

            val isEnabled = !fileName.endsWith(".disabled")
            val displayName = if (isEnabled) fileName else fileName.removeSuffix(".disabled")
            list.add(
                PresetDictItem(
                    file = file,
                    name = displayName,
                    isEnabled = isEnabled,
                    sizeBytes = file.length()
                )
            )
        }

        return list.sortedBy { it.name.lowercase() }
    }

    fun togglePresetDict(item: PresetDictItem, enable: Boolean, onProgress: ((progressText: String, percent: Int) -> Unit)? = null): Boolean {
        return try {
            if (enable && !item.isEnabled) {
                val target = File(item.file.parentFile, item.name)
                item.file.renameTo(target)
            } else if (!enable && item.isEnabled) {
                val target = File(item.file.parentFile, "${item.name}.disabled")
                item.file.renameTo(target)
            }
            loadUserDicts(onProgress)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling preset dict: ${item.name}", e)
            false
        }
    }

    fun deletePresetDict(item: PresetDictItem, onProgress: ((progressText: String, percent: Int) -> Unit)? = null): Boolean {
        return try {
            if (item.file.exists()) {
                item.file.delete()
            }
            loadUserDicts(onProgress)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting preset dict: ${item.name}", e)
            false
        }
    }

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

    fun convert(query: String, maxCandidates: Int = 9, context: String = "", queryId: Long = 0): List<String> {
        if (query.isBlank() || !isLibraryLoaded || !isLoaded) return listOf(query)

        return try {
            val joinedCandidates = nativeConvert(query, maxCandidates, context, queryId)
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

        // 1. ネイティブ側のメモリキャッシュ更新 (自動カウントアップ & タイムスタンプ更新)
        if (isLibraryLoaded && isLoaded) {
            try {
                nativeRecordLearning(r, s)
            } catch (e: Throwable) {
                Log.e(TAG, "Error recording learning: $r -> $s", e)
            }
        }

        // 2. Kotlin側のメモリキャッシュ更新
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

        // 3. エンジンの save 機能で非同期保存 (frequency & last_access が保持される)
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

    /**
     * 学習履歴ファイルをOutputStreamへそのまま出力する (バックアップ)
     */
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

    /**
     * 入力ストリームから学習履歴ファイルをそのまま読み込んで内部学習辞書ファイルに書き込み、ロードする (復元)
     */
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

    /**
     * KarukanSettings のすべての設定値をエンジンおよびネイティブ各コンポーネントへ一括反映します
     */
    fun updateEngineConfig(settings: KarukanSettings) {
        if (!isLibraryLoaded) return
        if (settings.modelName.isNotEmpty() && settings.modelName != currentLoadedModelName) {
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
                punctuation = settings.punctuationStyle,
                bracket = settings.bracketStyle,
                slash = settings.slashStyle,
                space = settings.spaceStyle,
                kanaSymbolWidth = settings.kanaSymbolWidth,
                asciiSymbolWidth = settings.asciiWidth,
                digitWidth = settings.digitWidth,
                maxLearningEntries = settings.maxLearningEntries,
                isLearningEnabled = settings.isLearningEnabled,
                isDateConversionEnabled = settings.isDateConversionEnabled
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error updating engine config", e)
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
        punctuation: String,
        bracket: String,
        slash: String,
        space: String,
        kanaSymbolWidth: String,
        asciiSymbolWidth: String,
        digitWidth: String,
        maxLearningEntries: Int,
        isLearningEnabled: Boolean,
        isDateConversionEnabled: Boolean
    )

    fun processKey(keysym: Int, modifiers: Int, isRelease: Boolean = false): ProcessKeyResult {
        if (!isLibraryLoaded) return ProcessKeyResult(consumed = false)
        return try {
            nativeProcessKey(keysym, modifiers, isRelease) ?: ProcessKeyResult(consumed = false)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in processKey: keysym=$keysym, modifiers=$modifiers", e)
            ProcessKeyResult(consumed = false)
        }
    }

    @Suppress("JniMissingFunction")
    private external fun nativeProcessKey(keysym: Int, modifiers: Int, isRelease: Boolean): ProcessKeyResult?

    @Suppress("JniMissingFunction")
    private external fun nativeCancelQuery(queryId: Long)

    @Suppress("JniMissingFunction")
    private external fun nativeConvert(query: String, maxCandidates: Int, context: String, queryId: Long): String

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
