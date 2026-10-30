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
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 複数の変換ペルソナの登録・有効化・編集・削除を行う管理画面アクティビティ
 */
class PersonaListActivity : AppCompatActivity() {

    private lateinit var adapter: PersonaAdapter
    private lateinit var btnAddPersona: Button
    private lateinit var tvPersonaCount: TextView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var rvPersonaList: RecyclerView

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("変換ペルソナの管理")

        val mainView = findViewById<View>(R.id.personaListMainLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnAddPersona = findViewById(R.id.btnAddPersona)
        tvPersonaCount = findViewById(R.id.tvPersonaCount)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        rvPersonaList = findViewById(R.id.rvPersonaList)

        adapter = PersonaAdapter(
            onItemClick = { item ->
                showEditPersonaDialog(item)
            },
            onToggle = { item, isEnabled ->
                updatePersonaEnabled(item, isEnabled)
            },
            onDelete = { item ->
                showDeleteConfirmDialog(item)
            }
        )

        rvPersonaList.layoutManager = LinearLayoutManager(this)
        rvPersonaList.adapter = adapter

        btnAddPersona.setOnClickListener {
            showAddPersonaDialog()
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
        val items = getPersonaItemsFromPrefs()
        adapter.submitList(items)
        val enabledCount = items.count { it.isEnabled }
        tvPersonaCount.text = "登録ペルソナ: ${items.size}件 (有効: ${enabledCount}件)"

        if (items.isEmpty()) {
            tvEmptyMessage.isVisible = true
            rvPersonaList.isVisible = false
        } else {
            tvEmptyMessage.isVisible = false
            rvPersonaList.isVisible = true
        }
    }

    private fun getPersonaItemsFromPrefs(): List<PersonaItem> {
        val json = prefs.getString("pref_persona_list", null)
        return if (json != null) {
            PersonaItem.fromJsonString(json)
        } else {
            emptyList()
        }
    }

    private fun savePersonaItemsToPrefs(items: List<PersonaItem>) {
        val json = PersonaItem.toJsonString(items)
        prefs.edit().putString("pref_persona_list", json).apply()
    }

    private fun showAddPersonaDialog() {
        val input = EditText(this).apply {
            hint = "例: プログラミング, 医療, ビジネスメール"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("ペルソナの追加")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val currentItems = getPersonaItemsFromPrefs().toMutableList()
                    currentItems.add(PersonaItem(text = text, isEnabled = true))
                    savePersonaItemsToPrefs(currentItems)
                    loadData()
                    Toast.makeText(this, "ペルソナを追加しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "ペルソナの内容を入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showEditPersonaDialog(item: PersonaItem) {
        val input = EditText(this).apply {
            setText(item.text)
            setSelection(item.text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("ペルソナの編集")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    val currentItems = getPersonaItemsFromPrefs().toMutableList()
                    val index = currentItems.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        currentItems[index] = item.copy(text = newText)
                        savePersonaItemsToPrefs(currentItems)
                        loadData()
                        Toast.makeText(this, "ペルソナを更新しました", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "ペルソナの内容を入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updatePersonaEnabled(item: PersonaItem, isEnabled: Boolean) {
        val currentItems = getPersonaItemsFromPrefs().toMutableList()
        val index = currentItems.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            currentItems[index] = item.copy(isEnabled = isEnabled)
            savePersonaItemsToPrefs(currentItems)
            loadData()
        }
    }

    private fun showDeleteConfirmDialog(item: PersonaItem) {
        AlertDialog.Builder(this)
            .setTitle("ペルソナの削除")
            .setMessage("ペルソナ「${item.text}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                val currentItems = getPersonaItemsFromPrefs().toMutableList()
                currentItems.removeAll { it.id == item.id }
                savePersonaItemsToPrefs(currentItems)
                loadData()
                Toast.makeText(this, "ペルソナを削除しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private class PersonaAdapter(
        private val onItemClick: (PersonaItem) -> Unit,
        private val onToggle: (PersonaItem, Boolean) -> Unit,
        private val onDelete: (PersonaItem) -> Unit
    ) : ListAdapter<PersonaItem, PersonaAdapter.ViewHolder>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<PersonaItem>() {
            override fun areItemsTheSame(oldItem: PersonaItem, newItem: PersonaItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PersonaItem, newItem: PersonaItem): Boolean =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_persona, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.bind(item, onItemClick, onToggle, onDelete)
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvPersonaText: TextView = itemView.findViewById(R.id.tvPersonaText)
            private val switchEnable: SwitchMaterial = itemView.findViewById(R.id.switchEnable)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(
                item: PersonaItem,
                onItemClick: (PersonaItem) -> Unit,
                onToggle: (PersonaItem, Boolean) -> Unit,
                onDelete: (PersonaItem) -> Unit
            ) {
                tvPersonaText.text = item.text

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
