package io.github.kachaya.karukan

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Karukan IME の Android InputMethodService 実装クラス (QWERTY ローマ字入力対応)
 */
class KarukanInputMethodService : InputMethodService() {

    private lateinit var engine: KarukanEngine
    private val romajiBuffer = StringBuilder()

    private lateinit var composingTextView: TextView
    private lateinit var candidateScrollContainer: HorizontalScrollView
    private lateinit var candidateLayout: LinearLayout

    companion object {
        private const val TAG = "KarukanInputService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KarukanInputMethodService created")
        engine = KarukanEngine(this)
    }

    override fun onCreateInputView(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.keyboard_bg, theme))
        }

        val initialPaddingLeft = rootLayout.paddingLeft
        val initialPaddingTop = rootLayout.paddingTop
        val initialPaddingRight = rootLayout.paddingRight
        val initialPaddingBottom = rootLayout.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight,
                initialPaddingBottom + navInsets.bottom
            )
            insets
        }

        // 1. 未確定テキスト表示エリア (ひらがな + ローマ字)
        composingTextView = TextView(this).apply {
            text = "かな: (なし)"
            textSize = 14f
            setPadding(16, 8, 16, 8)
            setTextColor(resources.getColor(android.R.color.white, theme))
        }
        rootLayout.addView(composingTextView)

        // 2. 変換候補バー (横スクロール可能)
        candidateScrollContainer = HorizontalScrollView(this).apply {
            setBackgroundColor(resources.getColor(R.color.candidate_bg, theme))
            setPadding(8, 4, 8, 4)
        }
        candidateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val defaultCandidateTextView = TextView(this).apply {
            text = "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
        }
        candidateLayout.addView(defaultCandidateTextView)
        candidateScrollContainer.addView(candidateLayout)
        rootLayout.addView(candidateScrollContainer)

        // 3. QWERTY 画面キーボードの構築
        val keyboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }

        val row1Keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row2Keys = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-")
        val row3Keys = listOf("z", "x", "c", "v", "b", "n", "m")

        keyboardContainer.addView(createKeyRow(row1Keys))
        keyboardContainer.addView(createKeyRow(row2Keys))
        keyboardContainer.addView(createRow3WithBackspace(row3Keys))
        keyboardContainer.addView(createActionRow())

        rootLayout.addView(keyboardContainer)

        return rootLayout
    }

    private fun createKeyRow(keys: List<String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            for (key in keys) {
                val btn = Button(this@KarukanInputMethodService).apply {
                    text = key
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    setOnClickListener {
                        onKeyPress(key)
                    }
                }
                addView(btn)
            }
        }
    }

    private fun createRow3WithBackspace(keys: List<String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            for (key in keys) {
                val btn = Button(this@KarukanInputMethodService).apply {
                    text = key
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    setOnClickListener {
                        onKeyPress(key)
                    }
                }
                addView(btn)
            }

            // ⌫ BS ボタン
            val btnBs = Button(this@KarukanInputMethodService).apply {
                text = "⌫"
                textSize = 16f
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.5f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    onBackspace()
                }
            }
            addView(btnBs)
        }
    }

    private fun createActionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // Space / 変換 ボタン
            val btnSpace = Button(this@KarukanInputMethodService).apply {
                text = "スペース / 変換"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    2f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    if (romajiBuffer.isNotEmpty()) {
                        performConversion()
                    } else {
                        currentInputConnection?.commitText(" ", 1)
                    }
                }
            }

            // 確定 ボタン
            val btnCommit = Button(this@KarukanInputMethodService).apply {
                text = "確定"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    commitCurrentHiragana()
                }
            }

            // クリア ボタン
            val btnClear = Button(this@KarukanInputMethodService).apply {
                text = "クリア"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    romajiBuffer.clear()
                    updateComposingText()
                    clearCandidateBar()
                }
            }

            addView(btnSpace)
            addView(btnCommit)
            addView(btnClear)
        }
    }

    private fun onKeyPress(key: String) {
        romajiBuffer.append(key)
        updateComposingText()
    }

    private fun onBackspace() {
        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer.deleteCharAt(romajiBuffer.length - 1)
            updateComposingText()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun updateComposingText() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) {
            composingTextView.text = "かな: (なし)"
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        val hiragana = engine.romajiToHiragana(raw)
        composingTextView.text = "かな: $hiragana  [$raw]"
        currentInputConnection?.setComposingText(hiragana, 1)
    }

    private fun performConversion() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        val candidates = engine.convert(hiragana)

        renderCandidates(hiragana, candidates)
    }

    private fun renderCandidates(hiragana: String, candidates: List<String>) {
        candidateLayout.removeAllViews()

        val allCandidates = mutableListOf<String>()
        if (candidates.isNotEmpty()) {
            allCandidates.addAll(candidates)
        }
        if (!allCandidates.contains(hiragana)) {
            allCandidates.add(hiragana)
        }

        for (cand in allCandidates) {
            val candBtn = Button(this).apply {
                text = cand
                textSize = 16f
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    currentInputConnection?.commitText(cand, 1)
                    romajiBuffer.clear()
                    updateComposingText()
                    clearCandidateBar()
                }
            }
            candidateLayout.addView(candBtn)
        }
    }

    private fun clearCandidateBar() {
        candidateLayout.removeAllViews()
        val defaultCandidateTextView = TextView(this).apply {
            text = "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
        }
        candidateLayout.addView(defaultCandidateTextView)
    }

    private fun commitCurrentHiragana() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        currentInputConnection?.commitText(hiragana, 1)
        romajiBuffer.clear()
        updateComposingText()
        clearCandidateBar()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        romajiBuffer.clear()
        if (::composingTextView.isInitialized) {
            updateComposingText()
            clearCandidateBar()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.isPrintingKey) {
            val unicodeChar = event.unicodeChar
            if (unicodeChar != 0) {
                val c = unicodeChar.toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c == '-') {
                    romajiBuffer.append(c.lowercaseChar())
                    updateComposingText()
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onBackspace()
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                if (romajiBuffer.isNotEmpty()) {
                    performConversion()
                } else {
                    currentInputConnection?.commitText(" ", 1)
                }
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                if (romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }
}
