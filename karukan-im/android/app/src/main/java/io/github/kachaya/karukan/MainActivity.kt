package io.github.kachaya.karukan

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Karukan IME のセットアップ用アクティビティ
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnEnableIme: Button
    private lateinit var btnSelectIme: Button
    private lateinit var btnOpenSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mainView = findViewById<android.view.View>(R.id.mainLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnEnableIme = findViewById(R.id.btnEnableIme)
        btnSelectIme = findViewById(R.id.btnSelectIme)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        btnEnableIme.setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        }

        btnSelectIme.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        btnOpenSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSetupStatus()
    }

    private fun updateSetupStatus() {
        val isEnabled = isImeEnabled()
        val isSelected = isImeSelected()

        if (isEnabled) {
            btnEnableIme.text = "1. Karukan IME を有効化 (有効化済み ✅)"
        } else {
            btnEnableIme.text = getString(R.string.enable_ime_button)
        }

        if (isSelected) {
            btnSelectIme.text = "2. キーボードを選択 (選択中 ✅)"
        } else {
            btnSelectIme.text = getString(R.string.select_ime_button)
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val enabledImes = imm.enabledInputMethodList
        return enabledImes.any { it.packageName == packageName }
    }

    private fun isImeSelected(): Boolean {
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return defaultIme.contains(packageName)
    }
}
