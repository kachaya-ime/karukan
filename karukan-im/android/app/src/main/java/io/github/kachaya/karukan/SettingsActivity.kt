package io.github.kachaya.karukan

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * Karukan IME の設定画面アクティビティ
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }

        val mainView = findViewById<android.view.View>(R.id.settingsContainer) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_karukan, rootKey)

            findPreference<Preference>("pref_third_party_licenses")?.setOnPreferenceClickListener {
                showLegalDialog("サードパーティライセンス", "legal/THIRD_PARTY_LICENSES")
                true
            }

            findPreference<Preference>("pref_karukan_license")?.setOnPreferenceClickListener {
                showLegalDialog("Karukan ライセンス", "legal/LICENSE-MIT")
                true
            }
        }

        private fun showLegalDialog(title: String, assetPath: String) {
            val context = requireContext()
            val content = try {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "ライセンス情報の読み込みに失敗しました: ${e.message}"
            }

            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("閉じる", null)
                .show()
        }
    }
}
