// Copyright PolyAI Limited

//  LogsDialog.kt — Examples/views/07-playground
//  A count header, a filter field, monospaced rows that expand to show detail, a
//  copy button, and Done, presented as a full-screen Dialog with its own title bar.

package ai.poly.examples.playground.views

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class LogsDialog(context: Context, private val logs: List<LogEntry>) : Dialog(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var filter = ""
    private var filtered: List<LogEntry> = logs
    private val expanded = mutableSetOf<UUID>()

    private lateinit var countLabel: TextView
    private lateinit var list: RecyclerView
    private val adapter = LogAdapter()

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Title bar: copy-all on the left, Done on the right.
        val titleBar = FrameLayout(context)
        val copyButton = ImageButton(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_copy))
            imageTintList = ColorStateList.valueOf(Palette.systemBlue)
            background = null
            contentDescription = "Copy all logs"
            setOnClickListener { copyAll() }
        }
        titleBar.addView(
            copyButton,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        titleBar.addView(
            TextView(context).apply {
                text = "Debug Logs"
                setTextColor(Palette.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        titleBar.addView(
            TextView(context).apply {
                text = "Done"
                setTextColor(Palette.systemBlue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { dismiss() }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        root.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        // Count header ("N entries" / "N entries · M match").
        countLabel = TextView(context).apply {
            setTextColor(Palette.secondaryLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        root.addView(countLabel)

        // Filter field.
        val filterBg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Palette.systemGray6)
                cornerRadius = 8 * density
            }
            setPadding(dp(10), 0, dp(10), 0)
        }
        filterBg.addView(
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_search))
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(Palette.secondaryLabel))
            },
            LinearLayout.LayoutParams(dp(16), dp(16)),
        )
        val filterField = EditText(context).apply {
            hint = "Filter logs..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = null
            // No autocapitalize / suggestions — the filter matches raw log text.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            doAfterTextChanged {
                filter = it?.toString() ?: ""
                applyFilter()
            }
        }
        filterBg.addView(
            filterField,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(
            filterBg,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply {
                setMargins(dp(16), dp(8), dp(16), dp(8))
            },
        )

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@LogsDialog.adapter
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawableResource(android.R.color.white)

        updateCount()
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun applyFilter() {
        filtered = if (filter.isEmpty()) {
            logs
        } else {
            logs.filter {
                it.summary.contains(filter, ignoreCase = true) ||
                    (it.detail?.contains(filter, ignoreCase = true) ?: false)
            }
        }
        updateCount()
        adapter.notifyDataSetChanged()
    }

    private fun updateCount() {
        countLabel.text = if (filter.isEmpty()) {
            "${logs.size} entries"
        } else {
            "${logs.size} entries · ${filtered.size} match"
        }
    }

    private fun copyAll() {
        val text = logs.joinToString("\n") { e -> e.summary + (e.detail?.let { "\n$it" } ?: "") }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Debug Logs", text))
    }

    private fun scrollToBottom() {
        if (filtered.isNotEmpty()) list.post { list.scrollToPosition(filtered.size - 1) }
    }

    // ---- Rows ----

    private inner class LogAdapter : RecyclerView.Adapter<LogHolder>() {
        override fun getItemCount(): Int = filtered.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogHolder =
            LogHolder(LogRowView(parent.context))

        override fun onBindViewHolder(holder: LogHolder, position: Int) {
            val entry = filtered[position]
            holder.row.configure(entry, expanded.contains(entry.id), zebra = position % 2 == 0)
            holder.row.setOnClickListener {
                if (entry.detail == null) return@setOnClickListener
                if (!expanded.add(entry.id)) expanded.remove(entry.id)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    private class LogHolder(val row: LogRowView) : RecyclerView.ViewHolder(row)

    private class LogRowView(context: Context) : LinearLayout(context) {
        private val density = resources.displayMetrics.density
        private fun dp(v: Int) = (v * density).toInt()

        private val icon = ImageView(context)
        private val summary = TextView(context)
        private val chevron = ImageView(context)
        private val detail = TextView(context)

        init {
            orientation = VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT,
            )
            val header = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            header.addView(icon, LayoutParams(dp(16), dp(16)))
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            summary.typeface = Typeface.MONOSPACE
            summary.setTextColor(Palette.label)
            summary.ellipsize = android.text.TextUtils.TruncateAt.END
            header.addView(
                summary,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) },
            )
            ImageViewCompat.setImageTintList(chevron, ColorStateList.valueOf(Palette.systemBlue))
            header.addView(chevron, LayoutParams(dp(12), dp(12)).apply { marginStart = dp(8) })
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            detail.typeface = Typeface.MONOSPACE
            detail.setTextColor(Palette.secondaryLabel)
            detail.setPadding(dp(36), 0, dp(12), dp(8))
            addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun configure(entry: LogEntry, isExpanded: Boolean, zebra: Boolean) {
            val (color, iconRes) = level(entry.summary)
            icon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
            summary.text = entry.summary
            summary.maxLines = if (isExpanded) Int.MAX_VALUE else 1
            setBackgroundColor(if (zebra) Color.WHITE else Palette.secondarySystemBackground)
            if (entry.detail != null) {
                chevron.visibility = VISIBLE
                chevron.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    ),
                )
                detail.visibility = if (isExpanded) VISIBLE else GONE
                detail.text = if (isExpanded) entry.detail else null
            } else {
                chevron.visibility = GONE
                detail.visibility = GONE
            }
        }

        /** Keyword heuristics that map a log summary to a color + icon by severity. */
        private fun level(summary: String): Pair<Int, Int> {
            val lower = summary.lowercase()
            return when {
                lower.contains("error") || lower.contains("failed") ->
                    Palette.systemRed to R.drawable.ic_close_circle
                lower.contains("warn") || lower.contains("timeout") ->
                    Palette.systemOrange to R.drawable.ic_error_triangle
                lower.contains("connected") || lower.contains("session started") || lower.contains("confirmed") ->
                    Palette.systemGreen to R.drawable.ic_check_circle
                lower.contains("chunk") || lower.contains("thinking") ->
                    Palette.secondaryLabel to R.drawable.ic_more_horiz
                else -> Palette.label to R.drawable.ic_info
            }
        }
    }
}
