package io.github.kachaya.karukan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * ユーザー定義キーショートカットの管理・編集アクティビティ
 */
class KeyShortcutListActivity : AppCompatActivity() {

    // ---------------------------------------------------------------------------------------------
    // Region: Activity Lifecycle & Views
    // ---------------------------------------------------------------------------------------------

    private lateinit var adapter: KeyShortcutAdapter
    private lateinit var tvShortcutCount: TextView
    private lateinit var btnResetDefaults: Button
    private lateinit var rvShortcutList: RecyclerView

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var currentItems: List<KeyShortcutItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_shortcut_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("キーショートカットの設定")

        val mainView = findViewById<View>(R.id.keyShortcutListMainLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvShortcutCount = findViewById(R.id.tvShortcutCount)
        btnResetDefaults = findViewById(R.id.btnResetDefaults)
        rvShortcutList = findViewById(R.id.rvShortcutList)

        adapter = KeyShortcutAdapter { item ->
            if (!item.isReserved) {
                showActionSelectionDialog(item)
            } else {
                Toast.makeText(this, "標準のショートカットキーは変更できません", Toast.LENGTH_SHORT).show()
            }
        }

        rvShortcutList.layoutManager = LinearLayoutManager(this)
        rvShortcutList.adapter = adapter

        btnResetDefaults.setOnClickListener {
            showResetConfirmDialog()
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

    // ---------------------------------------------------------------------------------------------
    // Region: Data Persistence & Configuration Loading
    // ---------------------------------------------------------------------------------------------

    private fun loadData() {
        val json = prefs.getString("pref_key_shortcuts_json", null)
        currentItems = KeyShortcutItem.fromJsonString(json)
        adapter.submitList(currentItems)

        tvShortcutCount.text = "登録キー: 全${currentItems.size}件"
    }

    private fun saveItems(items: List<KeyShortcutItem>) {
        currentItems = items
        val json = KeyShortcutItem.toJsonString(items)
        prefs.edit {
            putString("pref_key_shortcuts_json", json)
        }
        loadData()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Dialog Handlers (Action Selection, Reset)
    // ---------------------------------------------------------------------------------------------

    private fun showActionSelectionDialog(targetItem: KeyShortcutItem) {
        val actionIds = KeyShortcutItem.configurableActionIds
        val labels = actionIds.map { KeyShortcutItem.getActionLabel(it) }.toTypedArray()
        val currentIndex = actionIds.indexOf(targetItem.actionId).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("${targetItem.displayKeyCombo} のアクション選択")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selectedActionId = actionIds[which]
                val updatedItems = currentItems.map { item ->
                    if (item.shortcutKey == targetItem.shortcutKey) {
                        item.copy(actionId = selectedActionId)
                    } else {
                        item
                    }
                }
                saveItems(updatedItems)
                dialog.dismiss()
                Toast.makeText(this, "${targetItem.displayKeyCombo} のアクションを変更しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("初期値に戻す")
            .setMessage("すべてのキーショートカット設定をデフォルトの標準状態に戻しますか？")
            .setPositiveButton("復元") { _, _ ->
                val defaults = KeyShortcutItem.getDefaultList()
                saveItems(defaults)
                Toast.makeText(this, "ショートカット設定を初期値に復元しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

// -------------------------------------------------------------------------------------------------
// Region: RecyclerView Adapter & ViewHolder
// -------------------------------------------------------------------------------------------------

class KeyShortcutAdapter(
    private val onItemClick: (KeyShortcutItem) -> Unit
) : ListAdapter<KeyShortcutItem, KeyShortcutAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvKeyCombo: TextView = view.findViewById(R.id.tvKeyCombo)
        val tvActionLabel: TextView = view.findViewById(R.id.tvActionLabel)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_key_shortcut, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvKeyCombo.text = item.displayKeyCombo
        holder.tvActionLabel.text = KeyShortcutItem.getActionLabel(item.actionId)

        if (item.isReserved) {
            holder.tvStatusBadge.text = "固定"
            holder.tvStatusBadge.setTextColor(0xFF888888.toInt())
            holder.tvStatusBadge.setBackgroundColor(0x1F888888.toInt())
            holder.itemView.alpha = 0.75f
        } else {
            holder.tvStatusBadge.text = "変更可能"
            holder.tvStatusBadge.setTextColor(0xFF0F5132.toInt())
            holder.tvStatusBadge.setBackgroundColor(0x30E6F4EA.toInt())
            holder.itemView.alpha = 1.0f
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<KeyShortcutItem>() {
        override fun areItemsTheSame(oldItem: KeyShortcutItem, newItem: KeyShortcutItem): Boolean {
            return oldItem.shortcutKey == newItem.shortcutKey
        }

        override fun areContentsTheSame(oldItem: KeyShortcutItem, newItem: KeyShortcutItem): Boolean {
            return oldItem == newItem
        }
    }
}
