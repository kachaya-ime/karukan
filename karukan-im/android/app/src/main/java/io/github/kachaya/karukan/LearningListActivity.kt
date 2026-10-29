package io.github.kachaya.karukan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 変換学習によって記憶されたデータの一覧を表示・管理するアクティビティ
 */
class LearningListActivity : AppCompatActivity() {

    private lateinit var engine: KarukanEngine
    private lateinit var adapter: LearningListAdapter

    private lateinit var etSearchQuery: EditText
    private lateinit var tvLearningCount: TextView
    private lateinit var btnClearAll: Button
    private lateinit var tvEmptyMessage: TextView
    private lateinit var rvLearningList: RecyclerView

    private var allEntries: List<LearningItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learning_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.learning_list_title)

        val mainView = findViewById<View>(R.id.learningListMainLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        engine = KarukanEngine(this)

        etSearchQuery = findViewById(R.id.etSearchQuery)
        tvLearningCount = findViewById(R.id.tvLearningCount)
        btnClearAll = findViewById(R.id.btnClearAll)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        rvLearningList = findViewById(R.id.rvLearningList)

        adapter = LearningListAdapter(
            onDeleteClick = { item ->
                showDeleteConfirmDialog(item)
            }
        )

        rvLearningList.layoutManager = LinearLayoutManager(this)
        rvLearningList.adapter = adapter

        btnClearAll.setOnClickListener {
            if (allEntries.isEmpty()) {
                Toast.makeText(this, "消去する学習履歴はありません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showClearAllConfirmDialog()
        }

        etSearchQuery.doOnTextChanged { text, _, _, _ ->
            filterList(text?.toString().orEmpty())
        }

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
        allEntries = engine.getLearningEntries()
        val hasData = allEntries.isNotEmpty()
        btnClearAll.isEnabled = hasData
        etSearchQuery.isEnabled = hasData
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
        tvLearningCount.text = getString(R.string.learning_count_format, filtered.size)

        tvEmptyMessage.isVisible = filtered.isEmpty()
        rvLearningList.isVisible = filtered.isNotEmpty()
    }

    private fun showDeleteConfirmDialog(item: LearningItem) {
        AlertDialog.Builder(this)
            .setTitle("学習データの削除")
            .setMessage("「${item.reading} → ${item.surface}」の学習データを削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                engine.removeLearningEntry(item.reading, item.surface)
                Toast.makeText(this, "削除しました: ${item.surface}", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showClearAllConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("学習履歴の消去")
            .setMessage("これまでのすべての変換学習データを消去しますか？")
            .setPositiveButton("全件消去") { _, _ ->
                engine.clearLearning()
                Toast.makeText(this, "学習履歴を全件消去しました", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private class LearningListAdapter(
        private val onDeleteClick: (LearningItem) -> Unit
    ) : ListAdapter<LearningItem, LearningListAdapter.ViewHolder>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<LearningItem>() {
            override fun areItemsTheSame(oldItem: LearningItem, newItem: LearningItem): Boolean =
                oldItem.reading == newItem.reading && oldItem.surface == newItem.surface

            override fun areContentsTheSame(oldItem: LearningItem, newItem: LearningItem): Boolean =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_learning_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.bind(item, onDeleteClick)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvSurface: TextView = itemView.findViewById(R.id.tvSurface)
            private val tvReading: TextView = itemView.findViewById(R.id.tvReading)
            private val tvCount: TextView = itemView.findViewById(R.id.tvCount)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(item: LearningItem, onDeleteClick: (LearningItem) -> Unit) {
                tvSurface.text = item.surface
                tvReading.text = item.reading
                tvCount.text = "使用回数: ${item.count}"
                btnDelete.setOnClickListener { onDeleteClick(item) }
            }
        }
    }
}
