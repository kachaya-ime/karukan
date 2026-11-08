package io.github.kachaya.karukan

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 日付・時刻変換フレーズおよび共通フォーマットの追加・編集・削除を管理するアクティビティ
 */
class DatePhraseListActivity : AppCompatActivity() {

    // ---------------------------------------------------------------------------------------------
    // Region: Activity Lifecycle & Views
    // ---------------------------------------------------------------------------------------------

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: DatePhraseAdapter
    private lateinit var btnAddPhrase: Button
    private lateinit var btnEditFormats: Button
    private lateinit var btnResetDefault: ImageButton
    private lateinit var tvPhraseCount: TextView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var rvPhraseList: RecyclerView

    private var currentConfigData: DateConfigData = DateConfigData()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_date_phrase_list)

        title = "日付・時刻変換フレーズの管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        btnAddPhrase = findViewById(R.id.btnAddPhrase)
        btnEditFormats = findViewById(R.id.btnEditFormats)
        btnResetDefault = findViewById(R.id.btnResetDefault)
        tvPhraseCount = findViewById(R.id.tvPhraseCount)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        rvPhraseList = findViewById(R.id.rvPhraseList)

        adapter = DatePhraseAdapter(
            onEdit = { showEditPhraseDialog(it) },
            onDelete = { showDeleteConfirmDialog(it) }
        )

        rvPhraseList.layoutManager = LinearLayoutManager(this)
        rvPhraseList.adapter = adapter

        btnAddPhrase.setOnClickListener { showAddPhraseDialog() }
        btnEditFormats.setOnClickListener { showEditFormatsDialog() }
        btnResetDefault.setOnClickListener { showResetConfirmDialog() }

        loadData()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Data Persistence & Configuration Loading
    // ---------------------------------------------------------------------------------------------

    private fun loadData() {
        val json = prefs.getString("pref_date_config_json", null)
        currentConfigData = DateConfigData.fromJsonString(json)

        val phrases = currentConfigData.phrases
        tvPhraseCount.text = "登録フレーズ: ${phrases.size}件 (共通フォーマット: ${currentConfigData.formats.size}件)"

        if (phrases.isEmpty()) {
            tvEmptyMessage.isVisible = true
            rvPhraseList.isVisible = false
        } else {
            tvEmptyMessage.isVisible = false
            rvPhraseList.isVisible = true
            adapter.submitList(phrases)
        }
    }

    private fun saveData(config: DateConfigData) {
        currentConfigData = config
        prefs.edit().putString("pref_date_config_json", config.toJsonString()).apply()
        loadData()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Dialog Handlers (Add/Edit Phrase, Formats, Reset)
    // ---------------------------------------------------------------------------------------------

    private fun showAddPhraseDialog() {
        showPhraseEditDialog(null)
    }

    private fun showEditPhraseDialog(item: DatePhraseItem) {
        showPhraseEditDialog(item)
    }

    private fun showPhraseEditDialog(existingItem: DatePhraseItem?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val etReading = EditText(this).apply {
            hint = "読み (例: きょう, いま, らいしゅう)"
            setText(existingItem?.reading ?: "")
        }

        val etOffsetDays = EditText(this).apply {
            hint = "今日からの日数オフセット (例: 0=今日, 1=明日, -1=昨日)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText((existingItem?.offsetDays ?: 0).toString())
        }

        val etFormats = EditText(this).apply {
            hint = "固有フォーマット(任意、1行1件。空欄で共通フォーマット使用)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(3)
            setText(existingItem?.formats?.joinToString("\n") ?: "")
        }

        layout.addView(etReading)
        layout.addView(etOffsetDays)
        layout.addView(etFormats)

        val titleText = if (existingItem == null) "フレーズの追加" else "フレーズの編集"

        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val reading = etReading.text.toString().trim()
                val offset = etOffsetDays.text.toString().trim().toLongOrNull() ?: 0L
                val rawFormats = etFormats.text.toString().trim()
                val formatsList = if (rawFormats.isNotEmpty()) {
                    rawFormats.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                } else null

                if (reading.isNotEmpty()) {
                    val currentPhrases = currentConfigData.phrases.toMutableList()
                    val newItem = DatePhraseItem(reading, offset, formatsList)

                    if (existingItem != null) {
                        val index = currentPhrases.indexOf(existingItem)
                        if (index >= 0) {
                            currentPhrases[index] = newItem
                        } else {
                            currentPhrases.add(newItem)
                        }
                    } else {
                        currentPhrases.add(newItem)
                    }

                    saveData(currentConfigData.copy(phrases = currentPhrases))
                    Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "読みを入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDeleteConfirmDialog(item: DatePhraseItem) {
        AlertDialog.Builder(this)
            .setTitle("フレーズの削除")
            .setMessage("「${item.reading}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                val currentPhrases = currentConfigData.phrases.toMutableList()
                currentPhrases.remove(item)
                saveData(currentConfigData.copy(phrases = currentPhrases))
                Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showEditFormatsDialog() {
        val input = EditText(this).apply {
            hint = "1行に1つのフォーマットを入力してください"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(8)
            setText(currentConfigData.formats.joinToString("\n"))
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("共通フォーマットの編集")
            .setMessage("フォーマット記述例:\n{YEAR}/{MONTH}/{DATE}\n{YEAR}年{MONTH:bare}月{DATE:bare}日\n{WEEKDAY}曜日")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text.toString().trim()
                val formatsList = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (formatsList.isNotEmpty()) {
                    saveData(currentConfigData.copy(formats = formatsList))
                    Toast.makeText(this, "共通フォーマットを更新しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "少なくとも1つのフォーマットを入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("設定のリセット")
            .setMessage("日付・時刻変換フレーズと共通フォーマットを標準の初期状態に戻しますか？")
            .setPositiveButton("リセット") { _, _ ->
                saveData(DateConfigData())
                Toast.makeText(this, "デフォルト設定に復元しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: RecyclerView Adapter & ViewHolder
    // ---------------------------------------------------------------------------------------------

    private class DatePhraseAdapter(
        private val onEdit: (DatePhraseItem) -> Unit,
        private val onDelete: (DatePhraseItem) -> Unit
    ) : ListAdapter<DatePhraseItem, DatePhraseAdapter.ViewHolder>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<DatePhraseItem>() {
            override fun areItemsTheSame(oldItem: DatePhraseItem, newItem: DatePhraseItem): Boolean =
                oldItem.reading == newItem.reading && oldItem.offsetDays == newItem.offsetDays

            override fun areContentsTheSame(oldItem: DatePhraseItem, newItem: DatePhraseItem): Boolean =
                oldItem == newItem
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvReading: TextView = itemView.findViewById(R.id.tvReading)
            private val tvSubInfo: TextView = itemView.findViewById(R.id.tvSubInfo)
            private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(
                item: DatePhraseItem,
                onEdit: (DatePhraseItem) -> Unit,
                onDelete: (DatePhraseItem) -> Unit
            ) {
                tvReading.text = item.reading
                val fmtInfo = if (item.formats.isNullOrEmpty()) "共通フォーマット" else "個別: ${item.formats.size}件"
                tvSubInfo.text = "オフセット: ${item.offsetDays}日 ($fmtInfo)"

                btnEdit.setOnClickListener { onEdit(item) }
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_date_phrase, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), onEdit, onDelete)
        }
    }
}
