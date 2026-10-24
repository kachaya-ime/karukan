package io.github.kachaya.karukan

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * 個別のユーザー辞書ファイル内の単語一覧の表示・検索・追加・編集・削除を行うアクティビティ
 */
class UserDictContentActivity : AppCompatActivity() {

    private lateinit var engine: KarukanEngine
    private lateinit var adapter: DictEntryAdapter
    private lateinit var targetFile: File

    private lateinit var etSearchQuery: EditText
    private lateinit var tvEntryCount: TextView
    private lateinit var btnAddEntry: Button
    private lateinit var tvEmptyMessage: TextView
    private lateinit var rvEntryList: RecyclerView

    private var allEntries: List<DictEntryItem> = emptyList()

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dict_content)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "ユーザー辞書"

        if (filePath.isNullOrEmpty()) {
            Toast.makeText(this, "辞書ファイルが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        targetFile = File(filePath)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = fileName

        val mainView = findViewById<View>(R.id.dictContentMainLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        engine = KarukanEngine(this)

        etSearchQuery = findViewById(R.id.etSearchQuery)
        tvEntryCount = findViewById(R.id.tvEntryCount)
        btnAddEntry = findViewById(R.id.btnAddEntry)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        rvEntryList = findViewById(R.id.rvEntryList)

        adapter = DictEntryAdapter(
            onEdit = { item ->
                showEditEntryDialog(item)
            },
            onDelete = { item ->
                showDeleteEntryDialog(item)
            }
        )

        rvEntryList.layoutManager = LinearLayoutManager(this)
        rvEntryList.adapter = adapter

        btnAddEntry.setOnClickListener {
            showAddEntryDialog()
        }

        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadData() {
        allEntries = engine.getEntriesFromDictFile(targetFile)
        filterList(etSearchQuery.text.toString())
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { item ->
                item.reading.lowercase().contains(q) || item.surface.lowercase().contains(q)
            }
        }

        adapter.submitList(filtered)
        tvEntryCount.text = "登録単語: ${filtered.size}件"

        if (filtered.isEmpty()) {
            tvEmptyMessage.visibility = View.VISIBLE
            rvEntryList.visibility = View.GONE
        } else {
            tvEmptyMessage.visibility = View.GONE
            rvEntryList.visibility = View.VISIBLE
        }
    }

    private fun showAddEntryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val editReading = EditText(this).apply {
            hint = "よみ (例: かるかん)"
        }
        val editSurface = EditText(this).apply {
            hint = "単語・表記 (例: Karukan)"
        }

        layout.addView(editReading)
        layout.addView(editSurface)

        AlertDialog.Builder(this)
            .setTitle("新規単語の追加")
            .setView(layout)
            .setPositiveButton("追加") { _, _ ->
                val reading = editReading.text.toString().trim()
                val surface = editSurface.text.toString().trim()
                if (reading.isNotEmpty() && surface.isNotEmpty()) {
                    val success = engine.addEntryToDictFile(targetFile, reading, surface)
                    if (success) {
                        Toast.makeText(this, "追加しました: $reading -> $surface", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(this, "追加に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showEditEntryDialog(item: DictEntryItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val editReading = EditText(this).apply {
            hint = "よみ"
            setText(item.reading)
        }
        val editSurface = EditText(this).apply {
            hint = "単語・表記"
            setText(item.surface)
        }

        layout.addView(editReading)
        layout.addView(editSurface)

        AlertDialog.Builder(this)
            .setTitle("単語の編集")
            .setView(layout)
            .setPositiveButton("更新") { _, _ ->
                val newReading = editReading.text.toString().trim()
                val newSurface = editSurface.text.toString().trim()
                if (newReading.isNotEmpty() && newSurface.isNotEmpty()) {
                    val success = engine.updateEntryInDictFile(
                        targetFile,
                        item.reading,
                        item.surface,
                        newReading,
                        newSurface
                    )
                    if (success) {
                        Toast.makeText(this, "更新しました: $newReading -> $newSurface", Toast.LENGTH_SHORT).show()
                        loadData()
                    } else {
                        Toast.makeText(this, "更新に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDeleteEntryDialog(item: DictEntryItem) {
        AlertDialog.Builder(this)
            .setTitle("単語の削除")
            .setMessage("「${item.reading} → ${item.surface}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                val success = engine.removeEntryFromDictFile(targetFile, item.reading, item.surface)
                if (success) {
                    Toast.makeText(this, "削除しました: ${item.surface}", Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private class DictEntryAdapter(
        private val onEdit: (DictEntryItem) -> Unit,
        private val onDelete: (DictEntryItem) -> Unit
    ) : RecyclerView.Adapter<DictEntryAdapter.ViewHolder>() {

        private var items: List<DictEntryItem> = emptyList()

        fun submitList(newItems: List<DictEntryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dict_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onEdit, onDelete)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvSurface: TextView = itemView.findViewById(R.id.tvSurface)
            private val tvReading: TextView = itemView.findViewById(R.id.tvReading)
            private val tvPosComment: TextView = itemView.findViewById(R.id.tvPosComment)
            private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(
                item: DictEntryItem,
                onEdit: (DictEntryItem) -> Unit,
                onDelete: (DictEntryItem) -> Unit
            ) {
                tvSurface.text = item.surface
                tvReading.text = item.reading

                val extraInfo = listOf(item.pos, item.comment).filter { it.isNotEmpty() }.joinToString(" | ")
                if (extraInfo.isNotEmpty()) {
                    tvPosComment.visibility = View.VISIBLE
                    tvPosComment.text = extraInfo
                } else {
                    tvPosComment.visibility = View.GONE
                }

                btnEdit.setOnClickListener { onEdit(item) }
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
