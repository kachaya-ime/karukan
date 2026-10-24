package io.github.kachaya.karukan

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 辞書・ファイル操作共通ユーティリティ
 */
object DictUtils {

    /**
     * バイト数を人間が読みやすい文字列 (KB, MB) に変換
     */
    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val k = sizeBytes / 1024.0
        val m = k / 1024.0
        return when {
            m >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", m)
            k >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", k)
            else -> "$sizeBytes B"
        }
    }
}

/**
 * Uri から表示用ファイル名を取得する拡張関数
 */
fun Uri.getFileName(context: Context): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    return name
}

/**
 * バックグラウンド処理を実行し、進捗ダイアログを更新・完了通知を行う拡張関数
 */
fun AppCompatActivity.executeWithProgress(
    initialMessage: String,
    action: (progressCallback: (String, Int) -> Unit) -> Boolean,
    onComplete: (Boolean) -> Unit
) {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(60, 48, 60, 48)
    }

    val tvMessage = TextView(this).apply {
        text = initialMessage
        textSize = 15f
        setPadding(0, 0, 0, 24)
    }

    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        max = 100
    }

    layout.addView(tvMessage)
    layout.addView(progressBar)

    val dialog = AlertDialog.Builder(this)
        .setView(layout)
        .setCancelable(false)
        .create()

    val updateProgress: (String, Int) -> Unit = { message, percent ->
        runOnUiThread {
            tvMessage.text = message
            if (percent in 0..100) {
                progressBar.isIndeterminate = false
                progressBar.progress = percent
            } else {
                progressBar.isIndeterminate = true
            }
        }
    }

    dialog.show()

    Executors.newSingleThreadExecutor().execute {
        val success = try {
            action(updateProgress)
        } catch (_: Exception) {
            false
        }
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                dialog.dismiss()
                onComplete(success)
            }
        }
    }
}
