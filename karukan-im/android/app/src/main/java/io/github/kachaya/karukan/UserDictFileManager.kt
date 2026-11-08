package io.github.kachaya.karukan

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * ユーザー辞書ファイル (TSV 形式) の読み込み・追加・更新・削除および
 * プリセット辞書の切り替え操作を行うマネージャーです。
 */
class UserDictFileManager(private val context: Context) {

    companion object {
        private const val TAG = "UserDictFileManager"
        private const val USER_DICTS_DIR = "user_dicts"
        private const val LEARNING_HISTORY_FILE = "learning_history.txt"
        private const val SYSTEM_DICT_FILE_NAME = "dict.bin"
    }

    fun getUserDictDir(): File {
        val dir = File(context.filesDir, USER_DICTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
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
        comment: String = "",
        onDictChanged: (() -> Unit)? = null
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
            onDictChanged?.invoke()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding entry to dict file: ${file.name}", e)
            false
        }
    }

    fun removeEntryFromDictFile(
        file: File,
        reading: String,
        surface: String,
        onDictChanged: (() -> Unit)? = null
    ): Boolean {
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
            onDictChanged?.invoke()
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
        newSurface: String,
        onDictChanged: (() -> Unit)? = null
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
            onDictChanged?.invoke()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating entry in dict file: ${file.name}", e)
            false
        }
    }

    fun importDictFile(
        inputStream: InputStream,
        fileName: String,
        onDictChanged: ((progressText: String, percent: Int) -> Unit)? = null
    ): Boolean {
        return try {
            val targetFile = File(getUserDictDir(), fileName)
            targetFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            onDictChanged?.invoke("ロード完了", 100)
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

    fun togglePresetDict(
        item: PresetDictItem,
        enable: Boolean,
        onDictChanged: ((progressText: String, percent: Int) -> Unit)? = null
    ): Boolean {
        return try {
            if (enable && !item.isEnabled) {
                val target = File(item.file.parentFile, item.name)
                item.file.renameTo(target)
            } else if (!enable && item.isEnabled) {
                val target = File(item.file.parentFile, "${item.name}.disabled")
                item.file.renameTo(target)
            }
            onDictChanged?.invoke("ロード完了", 100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling preset dict: ${item.name}", e)
            false
        }
    }

    fun deletePresetDict(
        item: PresetDictItem,
        onDictChanged: ((progressText: String, percent: Int) -> Unit)? = null
    ): Boolean {
        return try {
            if (item.file.exists()) {
                item.file.delete()
            }
            onDictChanged?.invoke("ロード完了", 100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting preset dict: ${item.name}", e)
            false
        }
    }
}
