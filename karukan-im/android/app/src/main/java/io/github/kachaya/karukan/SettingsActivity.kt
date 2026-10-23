package io.github.kachaya.karukan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

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

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val args = pref.extras
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentName).apply {
            arguments = args
            @Suppress("DEPRECATION")
            setTargetFragment(caller, 0)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else {
                finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_karukan, rootKey)

            findPreference<Preference>("pref_user_dict")?.setOnPreferenceClickListener {
                showUserDictDialog()
                true
            }

            findPreference<Preference>("pref_export_learning")?.setOnPreferenceClickListener {
                exportLearningData()
                true
            }

            findPreference<Preference>("pref_import_learning")?.setOnPreferenceClickListener {
                showImportDialog()
                true
            }

            findPreference<Preference>("pref_clear_learning")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("学習履歴の消去")
                    .setMessage("これまでの変換学習データを消去しますか？")
                    .setPositiveButton("消去") { _, _ ->
                        KarukanEngine(requireContext()).clearLearning()
                        Toast.makeText(requireContext(), "学習履歴を消去しました", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                true
            }

            findPreference<Preference>("pref_third_party_licenses")?.setOnPreferenceClickListener {
                showLegalDialog("サードパーティライセンス", "legal/THIRD_PARTY_LICENSES")
                true
            }

            findPreference<Preference>("pref_karukan_license")?.setOnPreferenceClickListener {
                showLegalDialog("Karukan ライセンス", "legal/LICENSE-MIT")
                true
            }
        }

        private fun showUserDictDialog() {
            val context = requireContext()
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }

            val editReading = EditText(context).apply {
                hint = "よみ (例: かるかん)"
            }
            val editSurface = EditText(context).apply {
                hint = "単語・表記 (例: Karukan)"
            }

            layout.addView(editReading)
            layout.addView(editSurface)

            AlertDialog.Builder(context)
                .setTitle("単語登録（ユーザー辞書）")
                .setView(layout)
                .setPositiveButton("登録") { _, _ ->
                    val reading = editReading.text.toString().trim()
                    val surface = editSurface.text.toString().trim()
                    if (reading.isNotEmpty() && surface.isNotEmpty()) {
                        KarukanEngine(context).recordLearning(reading, surface)
                        Toast.makeText(context, "登録しました: $reading -> $surface", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        private fun exportLearningData() {
            val context = requireContext()
            val sampleData = "# Karukan User Dictionary / Learning Export\nかるかん\tKarukan\n"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Karukan Dict Export", sampleData)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "辞書・学習データをクリップボードにコピーしました", Toast.LENGTH_SHORT).show()
        }

        private fun showImportDialog() {
            val context = requireContext()
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }

            val editTsv = EditText(context).apply {
                hint = "TSVデータを貼り付け (形式: 読み[TAB]単語)"
                minLines = 4
            }

            layout.addView(editTsv)

            AlertDialog.Builder(context)
                .setTitle("辞書・学習データのインポート")
                .setView(layout)
                .setPositiveButton("インポート") { _, _ ->
                    val content = editTsv.text.toString()
                    var count = 0
                    val engine = KarukanEngine(context)
                    for (line in content.lineSequence()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val parts = trimmed.split("\t")
                        if (parts.size >= 2) {
                            val reading = parts[0].trim()
                            val surface = parts[1].trim()
                            if (reading.isNotEmpty() && surface.isNotEmpty()) {
                                engine.recordLearning(reading, surface)
                                count++
                            }
                        }
                    }
                    Toast.makeText(context, "$count 件の単語をインポートしました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
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

    class EngineSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_karukan_engine, rootKey)
        }
    }
}
