package io.github.kachaya.karukan

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * プリセット辞書および追加辞書ファイルの管理画面アクティビティ
 */
class PresetDictActivity : AppCompatActivity() {

    private lateinit var engine: KarukanEngine
    private lateinit var adapter: PresetDictAdapter

    private lateinit var btnAddDict: Button
    private lateinit var tvPresetDictCount: TextView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var rvPresetDictList: RecyclerView

    private val openDictFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            importDictFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preset_dict)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.preset_dict_title)

        val mainView = findViewById<View>(R.id.presetDictMainLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        engine = KarukanEngine(this)

        btnAddDict = findViewById(R.id.btnAddDict)
        tvPresetDictCount = findViewById(R.id.tvPresetDictCount)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        rvPresetDictList = findViewById(R.id.rvPresetDictList)

        adapter = PresetDictAdapter(
            onItemClick = { item ->
                val intent = android.content.Intent(this, UserDictContentActivity::class.java).apply {
                    putExtra(UserDictContentActivity.EXTRA_FILE_PATH, item.file.absolutePath)
                    putExtra(UserDictContentActivity.EXTRA_FILE_NAME, item.name)
                }
                startActivity(intent)
            },
            onToggle = { item, isEnabled ->
                val statusStr = if (isEnabled) "有効化" else "無効化"
                executeWithProgress("辞書「${item.name}」を${statusStr}処理中...", action = { progressCallback ->
                    engine.togglePresetDict(item, isEnabled, onProgress = { text, percent ->
                        progressCallback(text, percent)
                    })
                }, onComplete = { success ->
                    if (success) {
                        Toast.makeText(this, "${item.name} を${if (isEnabled) "有効" else "無効"}にしました", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "切り替えに失敗しました", Toast.LENGTH_SHORT).show()
                    }
                    loadData()
                })
            },
            onDelete = { item ->
                showDeleteConfirmDialog(item)
            }
        )

        rvPresetDictList.layoutManager = LinearLayoutManager(this)
        rvPresetDictList.adapter = adapter

        btnAddDict.setOnClickListener {
            openDictFileLauncher.launch("*/*")
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
        val items = engine.getPresetDictItems()
        adapter.submitList(items)
        tvPresetDictCount.text = getString(R.string.preset_dict_count_format, items.size)

        if (items.isEmpty()) {
            tvEmptyMessage.isVisible = true
            rvPresetDictList.isVisible = false
        } else {
            tvEmptyMessage.isVisible = false
            rvPresetDictList.isVisible = true
        }
    }

    private fun importDictFromUri(uri: Uri) {
        val fileName = uri.getFileName(this) ?: "imported_dict_${System.currentTimeMillis()}.txt"
        executeWithProgress("辞書ファイル「$fileName」を読み込み中...", action = { progressCallback ->
            contentResolver.openInputStream(uri)?.use { stream ->
                engine.importDictFile(stream, fileName, onProgress = { text, percent ->
                    progressCallback(text, percent)
                })
            } ?: false
        }, onComplete = { success ->
            if (success) {
                Toast.makeText(this, "辞書ファイルを追加しました: $fileName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "辞書ファイルの追加に失敗しました", Toast.LENGTH_SHORT).show()
            }
            loadData()
        })
    }

    private fun showDeleteConfirmDialog(item: PresetDictItem) {
        AlertDialog.Builder(this)
            .setTitle("辞書ファイルの削除")
            .setMessage("辞書ファイル「${item.name}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                executeWithProgress("辞書ファイル「${item.name}」を削除中...", action = { progressCallback ->
                    engine.deletePresetDict(item, onProgress = { text, percent ->
                        progressCallback(text, percent)
                    })
                }, onComplete = { success ->
                    if (success) {
                        Toast.makeText(this, "削除しました: ${item.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                    loadData()
                })
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private class PresetDictAdapter(
        private val onItemClick: (PresetDictItem) -> Unit,
        private val onToggle: (PresetDictItem, Boolean) -> Unit,
        private val onDelete: (PresetDictItem) -> Unit
    ) : ListAdapter<PresetDictItem, PresetDictAdapter.ViewHolder>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<PresetDictItem>() {
            override fun areItemsTheSame(oldItem: PresetDictItem, newItem: PresetDictItem): Boolean =
                oldItem.file.absolutePath == newItem.file.absolutePath

            override fun areContentsTheSame(oldItem: PresetDictItem, newItem: PresetDictItem): Boolean =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_preset_dict, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.bind(item, onItemClick, onToggle, onDelete)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDictName: TextView = itemView.findViewById(R.id.tvDictName)
            private val tvDictInfo: TextView = itemView.findViewById(R.id.tvDictInfo)
            private val switchEnable: SwitchMaterial = itemView.findViewById(R.id.switchEnable)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(
                item: PresetDictItem,
                onItemClick: (PresetDictItem) -> Unit,
                onToggle: (PresetDictItem, Boolean) -> Unit,
                onDelete: (PresetDictItem) -> Unit
            ) {
                tvDictName.text = item.name

                val sizeFormatted = DictUtils.formatFileSize(item.sizeBytes)
                val statusStr = if (item.isEnabled) "有効" else "無効"
                tvDictInfo.text = "サイズ: $sizeFormatted  |  状態: $statusStr"

                itemView.setOnClickListener {
                    onItemClick(item)
                }

                switchEnable.setOnCheckedChangeListener(null)
                switchEnable.isChecked = item.isEnabled
                switchEnable.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(item, isChecked)
                }

                btnDelete.setOnClickListener {
                    onDelete(item)
                }
            }
        }
    }
}
