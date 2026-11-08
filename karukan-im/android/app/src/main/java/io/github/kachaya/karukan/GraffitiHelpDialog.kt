package io.github.kachaya.karukan

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2

/**
 * Graffiti ストロークヘルプ画像 (stroke_help1, stroke_help2, stroke_help3) を表示するダイアログ
 */
object GraffitiHelpDialog {

    // ---------------------------------------------------------------------------------------------
    // Region: Dialog Display & ViewPager Setup
    // ---------------------------------------------------------------------------------------------

    fun show(anchorView: View, isDark: Boolean) {
        val context = anchorView.context
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val bgColor = if (isDark) Color.parseColor("#202124") else Color.parseColor("#F8F9FA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val btnBgColor = if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0")

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(16, 16, 16, 16)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(context).apply {
            text = "Graffiti ストローク一覧"
            textSize = 18f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        rootLayout.addView(titleView)

        val helpImages = listOf(
            Pair("英字 (A - Z)", R.drawable.stroke_help1),
            Pair("数字・制御 (0 - 9, Space, BS...)", R.drawable.stroke_help2),
            Pair("記号 (Punctuation Shift 後)", R.drawable.stroke_help3)
        )

        val pageIndicator = TextView(context).apply {
            text = "1 / 3: ${helpImages[0].first}"
            textSize = 14f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(pageIndicator)

        val viewPager = ViewPager2(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * context.resources.displayMetrics.density).toInt()
            )
        }

        viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<HelpImageViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HelpImageViewHolder {
                val iv = ImageView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                    setPadding(8, 8, 8, 8)
                }
                return HelpImageViewHolder(iv)
            }

            override fun onBindViewHolder(holder: HelpImageViewHolder, position: Int) {
                holder.imageView.setImageResource(helpImages[position].second)
            }

            override fun getItemCount(): Int = helpImages.size
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.text = "${position + 1} / 3: ${helpImages[position].first}"
            }
        })
        rootLayout.addView(viewPager)

        val closeBtn = Button(context).apply {
            text = "閉じる"
            setTextColor(textColor)
            setBackgroundColor(btnBgColor)
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0)
            }
        }
        rootLayout.addView(closeBtn)

        dialog.setContentView(rootLayout)
        dialog.window?.apply {
            val token = anchorView.windowToken
            if (token != null) {
                attributes = attributes.apply {
                    type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                    this.token = token
                }
                addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Help Image ViewHolder
    // ---------------------------------------------------------------------------------------------

    private class HelpImageViewHolder(val imageView: ImageView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(imageView)
}
