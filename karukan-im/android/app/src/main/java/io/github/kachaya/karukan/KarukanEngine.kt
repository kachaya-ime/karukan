package io.github.kachaya.karukan

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * karukan AI かな漢字変換エンジンの JNI ブリッジクラス
 */
class KarukanEngine(private val context: Context) {

    private var isLoaded = false
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "KarukanEngine"
        private const val MODEL_NAME = "jinen-v2-xsmall-Q5_K_M.gguf"
        private const val TOKENIZER_NAME = "tokenizer.json"

        @JvmStatic
        fun prepareAssetFile(context: Context, fileName: String): String? {
            val targetFile = File(context.filesDir, fileName)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            return try {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        targetFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare asset file: $fileName", e)
                if (targetFile.exists()) targetFile.delete()
                null
            }
        }
    }

    init {
        executor.execute {
            try {
                Log.i(TAG, "Loading native library libkarukan_android.so...")
                try {
                    System.loadLibrary("c++_shared")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "c++_shared library load skipped or not needed: ${e.message}")
                }
                System.loadLibrary("karukan_android")

                prepareAssetFile(context, TOKENIZER_NAME)
                val modelPath = prepareAssetFile(context, MODEL_NAME)

                if (modelPath != null) {
                    val success = nativeInit(modelPath)
                    isLoaded = success
                    Log.i(TAG, "KarukanEngine nativeInit success: $success ($modelPath)")
                } else {
                    Log.e(TAG, "Model file preparation failed ($MODEL_NAME)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing KarukanEngine", e)
            }
        }
    }

    fun setNThreads(nThreads: Int) {
        if (!isLoaded) return
        try {
            nativeSetNThreads(nThreads)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting n_threads: $nThreads", e)
        }
    }

    fun romajiToHiragana(query: String): String {
        if (query.isEmpty()) return ""
        return try {
            nativeRomajiToHiragana(query)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting romaji: $query", e)
            query
        }
    }

    fun romajiToHiraganaFlush(query: String): String {
        if (query.isEmpty()) return ""
        return try {
            nativeRomajiToHiraganaFlush(query)
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing romaji: $query", e)
            query
        }
    }

    fun convert(query: String, maxCandidates: Int = 9, context: String = ""): List<String> {
        if (query.isEmpty() || !isLoaded) return listOf(query)

        return try {
            val candidates = nativeConvert(query, maxCandidates, context)
            if (candidates.isNotEmpty()) candidates.toList() else listOf(query)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting query: $query", e)
            listOf(query)
        }
    }

    fun recordLearning(reading: String, surface: String) {
        if (!isLoaded || reading.isEmpty() || surface.isEmpty()) return
        try {
            nativeRecordLearning(reading, surface)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording learning: $reading -> $surface", e)
        }
    }

    fun clearLearning() {
        if (!isLoaded) return
        try {
            nativeClearLearning()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing learning cache", e)
        }
    }

    @Suppress("JniMissingFunction")
    private external fun nativeInit(modelPath: String): Boolean

    @Suppress("JniMissingFunction")
    private external fun nativeSetNThreads(nThreads: Int)

    @Suppress("JniMissingFunction")
    private external fun nativeConvert(query: String, maxCandidates: Int, context: String): Array<String>

    @Suppress("JniMissingFunction")
    private external fun nativeRecordLearning(reading: String, surface: String)

    @Suppress("JniMissingFunction")
    private external fun nativeClearLearning()

    @Suppress("JniMissingFunction")
    private external fun nativeRomajiToHiragana(query: String): String

    @Suppress("JniMissingFunction")
    private external fun nativeRomajiToHiraganaFlush(query: String): String
}
