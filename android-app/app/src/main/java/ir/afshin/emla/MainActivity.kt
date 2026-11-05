package ir.afshin.emla

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private lateinit var database: WordsDatabase
    private lateinit var searchInput: EditText
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private lateinit var adapter: EntryAdapter
    private lateinit var clearButton: TextView
    private lateinit var allFilter: Button
    private lateinit var approvedFilter: Button
    private lateinit var spellingFilter: Button
    private lateinit var exactMatchFilter: CheckBox
    private var selectedKind: Int? = null
    private var ready = false
    private var searchSerial = 0
    private var pendingSearch: Runnable? = null

    private val green = Color.rgb(22, 118, 93)
    private val greenDark = Color.rgb(16, 91, 72)
    private val ink = Color.rgb(30, 45, 43)
    private val muted = Color.rgb(105, 123, 116)
    private val line = Color.rgb(225, 233, 228)
    private val paper = Color.WHITE
    private val pageBackground = Color.rgb(244, 247, 244)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = WordsDatabase(this)
        buildScreen()
        importDatabase()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pageBackground)
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }

        val heading = TextView(this).apply {
            text = "اَفرا"
            textSize = 23f
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.RIGHT
        }
        root.addView(heading, matchWrap())

        val subtitle = TextView(this).apply {
            text = "جست‌وجوی آفلاین واژگان مصوب فرهنگستان و فرهنگ املایی"
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.RIGHT
            setPadding(0, dp(2), 0, dp(15))
        }
        root.addView(subtitle, matchWrap())

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = rounded(paper, green, 2, 14)
            setPadding(dp(6), dp(4), dp(8), dp(4))
        }
        searchInput = EditText(this).apply {
            hint = "واژه یا موضوع را جست‌وجو کنید…"
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setTextColor(ink)
            setHintTextColor(Color.rgb(135, 151, 143))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))

        clearButton = TextView(this).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(muted)
            contentDescription = "پاک‌کردن جست‌وجو"
            visibility = View.GONE
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                searchInput.text.clear()
                searchInput.requestFocus()
            }
        }
        searchRow.addView(clearButton, LinearLayout.LayoutParams(dp(40), dp(42)))
        root.addView(searchRow, matchWrap())

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(12), 0, dp(8))
        }
        allFilter = filterButton("همه")
        approvedFilter = filterButton("واژگان مصوب")
        spellingFilter = filterButton("فرهنگ املایی")
        listOf(allFilter, approvedFilter, spellingFilter).forEachIndexed { index, button ->
            filters.addView(
                button,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
            )
        }
        root.addView(filters, matchWrap())
        updateFilterStyles()

        exactMatchFilter = CheckBox(this).apply {
            text = "تطبیق دقیق واژه"
            textSize = 13f
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            buttonTintList = ColorStateList.valueOf(green)
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(exactMatchFilter, matchWrap())

        status = TextView(this).apply {
            text = "در حال آماده‌سازی پایگاه‌داده…"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.RIGHT
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        root.addView(status, matchWrap())

        adapter = EntryAdapter()
        results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            itemAnimator = null
        }
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                scheduleSearch()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                pendingSearch?.let(mainHandler::removeCallbacks)
                searchNow()
                true
            } else {
                false
            }
        }

        allFilter.setOnClickListener { setFilter(null) }
        approvedFilter.setOnClickListener { setFilter(0) }
        spellingFilter.setOnClickListener { setFilter(1) }
        exactMatchFilter.setOnCheckedChangeListener { _, _ -> scheduleSearch() }
    }

    private fun importDatabase() {
        databaseExecutor.execute {
            try {
                database.importIfNeeded { message ->
                    mainHandler.post { status.text = message }
                }
                val count = database.totalCount()
                mainHandler.post {
                    ready = true
                    status.text = "آمادهٔ جست‌وجو · " + persianDigits(count) + " مدخل"
                    showInitialMessage()
                    scheduleSearch()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    ready = false
                    status.text = "آماده‌سازی پایگاه‌داده انجام نشد."
                    adapter.submit(emptyList())
                    showMessage("داده‌های همراه اپ خوانده نشد. اپ را دوباره نصب کنید.")
                }
            }
        }
    }

    private fun setFilter(kind: Int?) {
        selectedKind = kind
        updateFilterStyles()
        scheduleSearch()
    }

    private fun updateFilterStyles() {
        styleFilter(allFilter, selectedKind == null)
        styleFilter(approvedFilter, selectedKind == 0)
        styleFilter(spellingFilter, selectedKind == 1)
    }

    private fun scheduleSearch() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        val task = Runnable { searchNow() }
        pendingSearch = task
        mainHandler.postDelayed(task, 220)
    }

    private fun searchNow() {
        if (!ready) return
        val query = searchInput.text?.toString().orEmpty().trim()
        val serial = ++searchSerial
        if (query.isEmpty()) {
            adapter.submit(emptyList())
            status.text = "برای جست‌وجو، واژه‌ای بنویسید."
            return
        }
        status.text = "در حال جست‌وجو…"
        val filter = selectedKind
        databaseExecutor.execute {
            val matches = database.search(query, filter, exactMatchFilter.isChecked)
            mainHandler.post {
                if (serial != searchSerial) return@post
                adapter.submit(matches)
                status.text = if (matches.isEmpty()) {
                    "برای «" + query + "» نتیجه‌ای پیدا نشد."
                } else {
                    persianDigits(matches.size) + " نتیجه برای «" + query + "»"
                }
            }
        }
    }

    private fun showInitialMessage() {
        adapter.submit(emptyList())
        status.text = "برای جست‌وجو، واژه‌ای بنویسید."
    }

    private fun showMessage(message: String) {
        adapter.submit(emptyList())
        status.text = message
    }

    private fun filterButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setPadding(dp(5), 0, dp(5), 0)
        minHeight = 0
        minimumHeight = dp(40)
        stateListAnimator = null
    }

    private fun styleFilter(button: Button, selected: Boolean) {
        button.background = rounded(
            if (selected) Color.rgb(232, 244, 238) else paper,
            if (selected) Color.rgb(143, 194, 163) else line,
            1,
            10
        )
        button.setTextColor(if (selected) greenDark else muted)
        button.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryHolder>() {
        private var items: List<WordEntry> = emptyList()

        fun submit(values: List<WordEntry>) {
            items = values
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = rounded(paper, line, 1, 14)
                setPadding(dp(15), dp(12), dp(15), dp(13))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            return EntryHolder(card)
        }

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class EntryHolder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        fun bind(entry: WordEntry) {
            card.removeAllViews()
            val type = if (entry.kind == 0) "واژگان مصوب فرهنگستان" else "فرهنگ املایی خط فارسی"
            addText(card, type, 11f, muted, bold = false, top = 0, bottom = 4)

            addText(
                card,
                entry.title.ifBlank { entry.english },
                19f,
                greenDark,
                bold = true,
                top = 0,
                bottom = 4
            )

            val englishTerms = splitEnglishTerms(entry.english)
            if (englishTerms.isNotEmpty()) {
                addField(card, "برابر انگلیسی:", englishTerms.first(), ltr = true)
                val otherEnglish = englishTerms.drop(1)
                if (otherEnglish.isNotEmpty()) {
                    addField(card, "صورت‌های دیگر انگلیسی:", otherEnglish.joinToString(", "), ltr = true)
                }
            }
            if (entry.domain.isNotBlank()) addField(card, "حوزه:", entry.domain)
            if (entry.kind == 0 && entry.definition.isNotBlank()) {
                val (definition, synonyms) = splitDefinitionAndSynonyms(entry.definition)
                formatDefinitionRows(definition).forEachIndexed { index, line ->
                    addField(card, if (index == 0) "تعریف:" else "", line)
                }
                if (synonyms.isNotBlank()) addField(card, "مترادف:", synonyms)
            }
            if (entry.volumes.isNotBlank()) addField(card, "منبع:", formatVolumes(entry.volumes))
            if (entry.notes.isNotBlank()) addField(card, "یادداشت:", entry.notes)
        }

        private fun addField(parent: LinearLayout, label: String, value: String, ltr: Boolean = false) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(4), 0, 0)
            }
            val labelView = TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.RIGHT
            }
            row.addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val valueView = TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(ink)
                gravity = if (ltr) Gravity.LEFT else Gravity.RIGHT
                textDirection = if (ltr) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_FIRST_STRONG
                setTextIsSelectable(true)
                setPadding(0, 0, dp(7), 0)
            }
            row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            parent.addView(row, matchWrap())
        }
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        top: Int,
        bottom: Int
    ) {
        val text = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(0, dp(top), 0, dp(bottom))
        }
        parent.addView(text, matchWrap())
    }

    private fun rounded(fill: Int, stroke: Int, strokeWidth: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(strokeWidth), stroke)
        }

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun persianDigits(value: Int): String = persianDigits(value.toString())

    private fun persianDigits(value: String): String = value.map { ch ->
        when {
            ch in '0'..'9' -> ('۰'.code + (ch.code - '0'.code)).toChar()
            ch in '٠'..'٩' -> ('۰'.code + (ch.code - '٠'.code)).toChar()
            else -> ch
        }
    }.joinToString("")

    private fun splitEnglishTerms(raw: String): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        if (Regex(""",\s*(?:and|or)\s+""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return listOf(text)
        }
        return text.split(Regex("[,،;؛]")).map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun splitDefinitionAndSynonyms(raw: String): Pair<String, String> {
        val marker = Regex("""متـ?\s*\.\s*""")
        val matches = marker.findAll(raw).toList()
        if (matches.isEmpty()) return raw.trim() to ""
        val definition = raw.substring(0, matches.first().range.first).trim()
        val synonyms = matches.mapIndexedNotNull { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: raw.length
            raw.substring(match.range.last + 1, end).trim().takeIf { it.isNotBlank() }
        }.joinToString("، ")
        return definition to synonyms
    }

    private fun formatDefinitionRows(raw: String): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val marker = Regex("""(^|\s)(\[[^\]\n]+\]\s*)?([0-9٠-٩۰-۹]+)([.)])(?=\s)""")
        val matches = marker.findAll(text).toList()
        if (matches.size < 2) return listOf(formatDefinition(text))

        return matches.mapIndexed { index, match ->
            val nextStart = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val body = text.substring(match.range.last + 1, nextStart).trim()
            val domain = match.groupValues[2].trim()
            val prefix = if (index == 0) text.substring(0, match.range.first).trim() else ""
            listOf(prefix, domain, persianDigits(match.groupValues[3]) + match.groupValues[4], body)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .let(::formatDefinition)
        }
    }

    private fun formatDefinition(raw: String): String {
        var text = raw.replace(Regex("""(?<=[\p{L}\p{M}])ـ+(?=[\p{L}\p{M}])"""), "")
        val marker = Regex("""(^|\s)([0-9٠-٩۰-۹]+)([.)])(?=\s)""")
        text = marker.replace(text) { match ->
            match.groupValues[1] + persianDigits(match.groupValues[2]) + match.groupValues[3]
        }
        val persianText = Regex("""[\u0600-\u06ff\u0750-\u077f\u08a0-\u08ff]""")
        val quotePatterns = listOf(
            Regex("""“([^”\n]*)”""") to Pair("“", "”"),
            Regex("""["]([^"\n]*)["]""") to Pair("«", "»"),
            Regex("""‘([^’\n]*)’""") to Pair("«", "»"),
            Regex("""'([^'\n]*)'""") to Pair("«", "»")
        )
        for ((pattern, quotes) in quotePatterns) {
            text = pattern.replace(text) { match ->
                val inside = match.groupValues[1]
                if (persianText.containsMatchIn(inside)) quotes.first + inside + quotes.second else match.value
            }
        }
        return text
    }

    private fun formatVolumes(raw: String): String {
        val volumes = raw.split(Regex("[،,]"))
            .map { persianDigits(it.trim()) }
            .filter { it.isNotBlank() }
        return when (volumes.size) {
            0 -> ""
            1 -> "دفتر " + volumes[0]
            else -> "دفترهای " + volumes.dropLast(1).joinToString("، ") + " و " + volumes.last()
        }
    }

    override fun onDestroy() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        super.onDestroy()
    }
}
