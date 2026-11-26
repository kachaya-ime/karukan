package io.github.kachaya.karukan

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Karukan IME のアセットファイル (GGUFモデル、Tokenizer、システム辞書 dict.tgz 等)
 * の非同期展開・準備を行うヘルパーオブジェクトです。
 */
object EngineAssetPreparer {

    private const val TAG = "EngineAssetPreparer"
    private const val SYSTEM_DICT_TGZ_NAME = "dict.tgz"
    private const val SYSTEM_DICT_FILE_NAME = "dict.bin"
    private const val USER_DICTS_DIR = "user_dicts"

    private val assetCopyLock = Any()

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

        synchronized(assetCopyLock) {
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
    }

    @JvmStatic
    @JvmOverloads
    fun prepareAssetFile(
        context: Context,
        fileName: String,
        onProgress: ((progressText: String, percent: Int) -> Unit)? = null
    ): String? {
        val targetFile = File(context.filesDir, fileName)
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile.absolutePath
        }

        synchronized(assetCopyLock) {
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            val tempFile = File(context.filesDir, "$fileName.tmp")
            return try {
                val totalSize = try {
                    context.assets.openFd(fileName).use { it.length }
                } catch (_: Exception) {
                    try {
                        context.assets.open(fileName).use { it.available().toLong() }
                    } catch (_: Exception) {
                        -1L
                    }
                }

                context.assets.open(fileName).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        copyStreamWithProgress(
                            input = input,
                            output = output,
                            totalBytes = totalSize,
                            label = "コピー中 ($fileName)",
                            onProgress = onProgress
                        )
                    }
                }
                if (tempFile.renameTo(targetFile)) {
                    targetFile.absolutePath
                } else if (targetFile.exists() && targetFile.length() > 0) {
                    targetFile.absolutePath
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare asset file: $fileName", e)
                if (tempFile.exists()) tempFile.delete()
                if (targetFile.exists() && targetFile.length() == 0L) targetFile.delete()
                null
            }
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
                                        copyStreamWithProgress(tarIn, output, totalSize, "展開中", onProgress)
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
        label: String = "展開中",
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
                    val text = "$label: ${DictUtils.formatFileSize(written)} / ${DictUtils.formatFileSize(totalBytes)} (${percent}%)"
                    onProgress?.invoke(text, percent)
                }
            }
        }
    }
}
