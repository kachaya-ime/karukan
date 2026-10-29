package io.github.kachaya.karukan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        val mainView: View = findViewById(R.id.settingsContainer) ?: window.decorView
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
}

class SettingsFragment : PreferenceFragmentCompat() {

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            backupLearningDataToUri(uri)
        }
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            restoreLearningDataFromUri(uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_karukan, rootKey)

        findPreference<Preference>("pref_learning_list")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), LearningListActivity::class.java)
            startActivity(intent)
            true
        }

        findPreference<Preference>("pref_export_learning")?.setOnPreferenceClickListener {
            backupLauncher.launch("karukan_learning_backup.txt")
            true
        }

        findPreference<Preference>("pref_import_learning")?.setOnPreferenceClickListener {
            restoreLauncher.launch(arrayOf("text/plain", "*/*"))
            true
        }

        findPreference<Preference>("pref_preset_dict")?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), PresetDictActivity::class.java)
            startActivity(intent)
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

    private fun backupLearningDataToUri(uri: Uri) {
        val context = requireContext()
        val engine = KarukanEngine(context)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    engine.backupLearningToStream(outputStream)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${getString(R.string.export_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restoreLearningDataFromUri(uri: Uri) {
        val context = requireContext()
        val engine = KarukanEngine(context)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    engine.restoreLearningFromStream(inputStream)
                } ?: false

                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${getString(R.string.import_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showLegalDialog(title: String, assetPath: String) {
        val context = requireContext()
        val content = try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "ライセンス情報の読み込みに失敗しました: ${e.message}"
        }

        val scrollView = android.widget.ScrollView(context).apply {
            setPadding(48, 24, 48, 24)
        }
        val textView = android.widget.TextView(context).apply {
            text = content
            textSize = 12f
            setMonospaceFont()
        }
        scrollView.addView(textView)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun android.widget.TextView.setMonospaceFont() {
        typeface = android.graphics.Typeface.MONOSPACE
    }
}

class EngineSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_karukan_engine, rootKey)
    }
}
