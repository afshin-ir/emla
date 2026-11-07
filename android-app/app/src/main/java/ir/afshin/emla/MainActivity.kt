package ir.afshin.emla

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchPageSize = 40
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private var searchWork: Future<*>? = null
    private lateinit var database: WordsDatabase
    private lateinit var searchInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var loadMoreButton: Button
    private lateinit var results: RecyclerView
    private lateinit var adapter: EntryAdapter
    private lateinit var clearButton: TextView
    private lateinit var approvedFilter: Button
    private lateinit var spellingFilter: Button
    private lateinit var dasturFilter: Button
    private lateinit var exactMatchFilter: CheckBox
    private var selectedKind: Int? = 0
    private var ready = false
    private var searchSerial = 0
    private var pendingSearch: Runnable? = null
    private var textScale = 1f
    private var showingFavorites = false
    private var currentOffset = 0
    private var currentTotal = 0
    private var hasMoreResults = false
    private val favoriteEntries = LinkedHashMap<String, WordEntry>()
    private val recentSearches = mutableListOf<String>()
    private val appPreferences by lazy { getSharedPreferences("afra_preferences", MODE_PRIVATE) }

    private val green = Color.rgb(22, 118, 93)
    private val greenDark = Color.rgb(16, 91, 72)
    private val ink = Color.rgb(30, 45, 43)
    private val muted = Color.rgb(105, 123, 116)
    private val line = Color.rgb(225, 233, 228)
    private val paper = Color.WHITE
    private val pageBackground = Color.rgb(244, 247, 244)
    private val vazirmatnFamily: Typeface by lazy { ResourcesCompat.getFont(this, R.font.vazirmatn) ?: Typeface.DEFAULT }

    private fun appTypeface(style: Int = Typeface.NORMAL): Typeface = Typeface.create(vazirmatnFamily, style)

    private fun applyVazirmatn(view: View) {
        if (view is TextView) {
            val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
            view.typeface = appTypeface(style)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyVazirmatn(view.getChildAt(index))
        }
    }

    private fun showVazirmatnDialog(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.let { decor -> applyVazirmatn(decor) }
        }
        dialog.show()
        return dialog
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = WordsDatabase(this)
        loadLocalState()
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            contentDescription = "لوگوی اَفرا"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
        )

        val heading = TextView(this).apply {
            text = "اَفرا"
            textSize = 23f
            setTextColor(greenDark)
            typeface = appTypeface(Typeface.BOLD)
            gravity = Gravity.RIGHT
        }
        header.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(greenDark)
            background = rounded(paper, line, 1, 12)
            contentDescription = "بازکردن فهرست برنامه"
            isClickable = true
            isFocusable = true
            setOnClickListener { showAppMenu(this) }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(46), dp(42)))
        root.addView(header, matchWrap())

        val subtitle = TextView(this).apply {
            text = "جست‌وجوی برون‌خط (آفلاین) در واژگان مصوب فرهنگستان، فرهنگ املایی خط فارسی و دستور خط فارسی"
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, dp(3), 0, dp(9))
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
            background = rounded(paper, line, 1, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        approvedFilter = filterButton("واژگان مصوب")
        spellingFilter = filterButton("فرهنگ املایی")
        dasturFilter = filterButton("دستور خط")
        listOf(approvedFilter, spellingFilter, dasturFilter).forEachIndexed { index, button ->
            filters.addView(
                button,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (index > 0) marginStart = dp(3)
                }
            )
        }
        root.addView(filters, matchWrap())
        updateFilterStyles()

        exactMatchFilter = CheckBox(this).apply {
            text = "تطبیق دقیق واژه"
            textSize = 12f
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            buttonTintList = ColorStateList.valueOf(green)
            setTextColor(muted)
            minHeight = dp(40)
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

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(green)
            indeterminateTintList = ColorStateList.valueOf(green)
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            bottomMargin = dp(5)
        })


        adapter = EntryAdapter()
        results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            itemAnimator = null
        }
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        loadMoreButton = filterButton("نمایش نتایج بعدی").apply {
            background = rounded(paper, line, 1, 10)
            setTextColor(greenDark)
            visibility = View.GONE
            setOnClickListener { loadMoreResults() }
        }
        root.addView(loadMoreButton, matchWrap())

        applyVazirmatn(root)
        setContentView(root)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (showingFavorites) showingFavorites = false
                scheduleSearch()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                pendingSearch?.let(mainHandler::removeCallbacks)
                recordRecentSearch(searchInput.text?.toString().orEmpty())
                searchNow()
                true
            } else {
                false
            }
        }

        approvedFilter.setOnClickListener { setFilter(0) }
        spellingFilter.setOnClickListener { setFilter(1) }
        dasturFilter.setOnClickListener { setFilter(2) }
        exactMatchFilter.setOnCheckedChangeListener { _, _ -> scheduleSearch() }
    }

    private fun showAppMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "دربارهٔ اَفرا")
        popup.menu.add(0, 2, 1, "منابع و داده‌ها")
        popup.menu.add(0, 3, 2, "جست‌وجوهای اخیر")
        popup.menu.add(0, 4, 3, "نشان‌شده‌ها")
        popup.menu.add(0, 5, 4, "اندازۀ نوشته")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showAboutDialog()
                2 -> showSourcesDialog()
                3 -> showRecentSearches()
                4 -> showFavorites()
                5 -> showTextSizeDialog()
            }
            true
        }
        popup.show()
    }

    private fun showSourcesDialog() {
        val sources = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(4), dp(22), dp(4))
        }

        fun addSource(text: String, italic: Boolean = false) {
            val row = TextView(this).apply {
                this.text = if (italic) SpannableString(text).apply {
                    setSpan(android.text.style.StyleSpan(Typeface.ITALIC), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else text
                textSize = 15f
                setTextColor(ink)
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, dp(8), 0, dp(8))
            }
            sources.addView(row, matchWrap())
        }

        addSource("واژگان مصوب فرهنگستان (دفترهای ۱ تا ۲۰)")
        addSource("فرهنگ املایی خط فارسی", italic = true)
        addSource("دستور خط فارسی", italic = true)

showVazirmatnDialog(
        AlertDialog.Builder(this)
            .setTitle("منابع و داده‌ها")
            .setView(sources)
            .setPositiveButton("بستن", null)
            
        )
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "نامشخص"
        } catch (_: Exception) {
            "نامشخص"
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(4), dp(22), dp(4))
        }

        fun addDetail(text: String, clickable: Boolean = false, action: (() -> Unit)? = null) {
            val row = TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(if (clickable) green else ink)
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, dp(8), 0, dp(8))
                if (clickable) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { action?.invoke() }
                }
            }
            details.addView(row, matchWrap())
        }

        val description = TextView(this).apply {
            text = SpannableString("جست‌وجوی برون‌خط (آفلاین) در واژگان مصوب فرهنگستان (دفترهای ۱ تا ۲۰)، فرهنگ املایی خط فارسی و دستور خط فارسی").apply {
                val spellingTitle = "فرهنگ املایی خط فارسی"
                val guideTitle = "دستور خط فارسی"
                val spellingStart = indexOf(spellingTitle)
                val guideStart = indexOf(guideTitle)
                setSpan(android.text.style.StyleSpan(Typeface.ITALIC), spellingStart, spellingStart + spellingTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(Typeface.ITALIC), guideStart, guideStart + guideTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, 0, 0, dp(12))
        }
        details.addView(description, matchWrap())

        addDetail("نسخهٔ " + persianDigits(version))
        addDetail("سازنده: افشین فلاطونی")
        addDetail("کانال تلگرام سازنده: @Falatuni", clickable = true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Falatuni")))
        }

showVazirmatnDialog(
        AlertDialog.Builder(this)
            .setTitle("دربارهٔ اَفرا")
            .setView(details)
            .setPositiveButton("بستن", null)
            
        )
    }

    private fun loadLocalState() {
        textScale = appPreferences.getFloat("result_text_scale", 1f).coerceIn(0.9f, 1.3f)
        runCatching {
            val storedFavorites = JSONArray(appPreferences.getString("favorites", "[]"))
            for (index in 0 until storedFavorites.length()) {
                val item = storedFavorites.optJSONObject(index)
                if (item != null) {
                    val entry = WordEntry(
                        id = item.optLong("id"),
                        kind = item.optInt("kind"),
                        title = item.optString("title"),
                        english = item.optString("english"),
                        domain = item.optString("domain"),
                        definition = item.optString("definition"),
                        volumes = item.optString("volumes"),
                        notes = item.optString("notes"),
                        refTerms = item.optString("refTerms")
                    )
                    favoriteEntries[entryKey(entry)] = entry
                }
            }
        }
        runCatching {
            val storedSearches = JSONArray(appPreferences.getString("recent_searches", "[]"))
            for (index in 0 until storedSearches.length()) {
                val term = storedSearches.optString(index).trim()
                if (term.isNotBlank() && term !in recentSearches) recentSearches.add(term)
            }
        }
    }

    private fun entryKey(entry: WordEntry): String = listOf(
        entry.kind.toString(),
        entry.title,
        entry.english,
        entry.domain,
        entry.volumes,
        entry.definition.take(120)
    ).joinToString("\u001f")

    private fun isFavorite(entry: WordEntry): Boolean = entryKey(entry) in favoriteEntries

    private fun toggleFavorite(entry: WordEntry) {
        val key = entryKey(entry)
        if (key in favoriteEntries) favoriteEntries.remove(key) else favoriteEntries[key] = entry
        val stored = JSONArray()
        favoriteEntries.values.forEach { item ->
            stored.put(JSONObject().apply {
                put("id", item.id)
                put("kind", item.kind)
                put("title", item.title)
                put("english", item.english)
                put("domain", item.domain)
                put("definition", item.definition)
                put("volumes", item.volumes)
                put("notes", item.notes)
                put("refTerms", item.refTerms)
            })
        }
        appPreferences.edit().putString("favorites", stored.toString()).apply()
        if (showingFavorites) {
            adapter.submit(favoriteEntries.values.toList())
            status.text = "نشان‌شده‌ها · " + persianDigits(favoriteEntries.size) + " مدخل"
        } else {
            adapter.refresh()
        }
    }

    private fun recordRecentSearch(raw: String) {
        val term = raw.trim()
        if (term.length < 2) return
        recentSearches.remove(term)
        recentSearches.add(0, term)
        while (recentSearches.size > 10) recentSearches.removeAt(recentSearches.lastIndex)
        val stored = JSONArray()
        recentSearches.forEach { stored.put(it) }
        appPreferences.edit().putString("recent_searches", stored.toString()).apply()
    }

    private fun showRecentSearches() {
        if (recentSearches.isEmpty()) {
showVazirmatnDialog(
            AlertDialog.Builder(this)
                .setTitle("جست‌وجوهای اخیر")
                .setMessage("هنوز جست‌وجویی در تاریخچه ثبت نشده است.")
                .setPositiveButton("بستن", null)
                
        )
            return
        }
        val searches = recentSearches.toTypedArray()
showVazirmatnDialog(
        AlertDialog.Builder(this)
            .setTitle("جست‌وجوهای اخیر")
            .setItems(searches) { _, which ->
                showingFavorites = false
                searchInput.setText(searches[which])
                searchInput.setSelection(searchInput.text.length)
            }
            .setNeutralButton("پاک‌کردن تاریخچه") { _, _ ->
                recentSearches.clear()
                appPreferences.edit().putString("recent_searches", "[]").apply()
            }
            .setNegativeButton("بستن", null)
            
        )
    }

    private fun showFavorites() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        searchWork?.cancel(true)
        searchSerial += 1
        progress.visibility = View.GONE
        showingFavorites = true
        loadMoreButton.visibility = View.GONE
        adapter.submit(favoriteEntries.values.toList())
        results.scrollToPosition(0)
        status.text = if (favoriteEntries.isEmpty()) {
            "هنوز مدخلی نشان نشده است."
        } else {
            "نشان‌شده‌ها · " + persianDigits(favoriteEntries.size) + " مدخل"
        }
    }

    private fun showTextSizeDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(6), dp(24), dp(4))
        }
        val current = TextView(this).apply {
            gravity = Gravity.RIGHT
            setTextColor(muted)
            textSize = 14f
        }
        val seekBar = SeekBar(this).apply {
            max = 4
            progress = ((textScale - 0.9f) * 10f).toInt().coerceIn(0, 4)
        }
        fun updateScale(progressValue: Int) {
            textScale = 0.9f + progressValue * 0.1f
            val percent = 90 + progressValue * 10
            current.text = "اندازۀ نوشته: " + persianDigits(percent) + "٪"
            appPreferences.edit().putFloat("result_text_scale", textScale).apply()
            if (::adapter.isInitialized) adapter.refresh()
        }
        updateScale(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateScale(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(current, matchWrap())
        content.addView(seekBar, matchWrap())
showVazirmatnDialog(
        AlertDialog.Builder(this)
            .setTitle("اندازۀ نوشته‌های نتیجه")
            .setView(content)
            .setPositiveButton("بستن", null)
            
        )
    }

    private data class CopyField(val label: String, val value: String, val code: Boolean = false, val multiline: Boolean = false)

    private fun copyCardToClipboard(entry: WordEntry, button: Button) {
        val title = entry.title.ifBlank { entry.english }.trim()
        val type = when (entry.kind) {
            0 -> "واژگان مصوب فرهنگستان"
            1 -> "فرهنگ املایی خط فارسی"
            else -> "کتاب دستور خط فارسی"
        }
        val fields = mutableListOf<CopyField>()
        val englishTerms = splitEnglishTerms(entry.english)
        englishTerms.firstOrNull()?.let { fields += CopyField("برابر انگلیسی:", it, code = true) }
        englishTerms.drop(1).takeIf { it.isNotEmpty() }?.let {
            fields += CopyField("صورت‌های دیگر انگلیسی:", it.joinToString(", "), code = true)
        }

        if (entry.kind == 2) {
            if (entry.domain.isNotBlank()) fields += CopyField("بخش:", entry.domain)
            if (entry.definition.isNotBlank()) fields += CopyField("متن:", entry.definition.trim(), multiline = true)
            if (entry.volumes.isNotBlank()) {
                fields += CopyField("صفحه:", persianDigits(entry.volumes.replace(",", "،")))
            }
        } else {
            if (entry.domain.isNotBlank()) fields += CopyField("حوزه:", entry.domain)
            if (entry.kind == 0 && entry.definition.isNotBlank()) {
                val (definition, synonyms) = splitDefinitionAndSynonyms(entry.definition)
                val definitionRows = formatDefinitionRows(definition)
                if (definitionRows.isNotEmpty()) {
                    fields += CopyField("تعریف:", definitionRows.joinToString("\n"), multiline = true)
                }
                if (synonyms.isNotBlank()) fields += CopyField("مترادف:", synonyms)
            }
            if (entry.volumes.isNotBlank()) fields += CopyField("منبع:", formatVolumes(entry.volumes))
            if (entry.notes.isNotBlank()) fields += CopyField("یادداشت:", entry.notes.trim(), multiline = true)
        }

        val plainText = buildString {
            append("## ").append(title)
            append("\n\n").append(type)
            fields.forEach { field ->
                append("\n\n")
                if (field.multiline) {
                    append(field.label).append("\n\n").append(field.value)
                } else {
                    append(field.label).append(" ")
                    if (field.code) append("`").append(field.value).append("`") else append(field.value)
                }
            }
        }

        fun html(value: String): String = android.text.TextUtils.htmlEncode(value)
        val htmlText = buildString {
            append("<div dir=\"rtl\" style=\"font-family:Vazirmatn,sans-serif;line-height:1.8;color:#1e2d2b\">")
            append("<h2 style=\"margin:0 0 10px;color:#105b48;font-size:1.3em\">")
                .append(html(title)).append("</h2>")
            append("<p style=\"margin:0 0 10px;color:#697b74;font-size:.9em\">")
                .append(html(type)).append("</p>")
            fields.forEach { field ->
                if (field.multiline) {
                    append("<p style=\"margin:0 0 4px\"><strong>").append(html(field.label))
                        .append("</strong></p><p style=\"margin:0 0 10px\">")
                        .append(html(field.value).replace("\n", "<br>")).append("</p>")
                } else {
                    append("<p style=\"margin:0 0 10px\"><strong>").append(html(field.label))
                        .append("</strong> ")
                    if (field.code) {
                        append("<code dir=\"ltr\" style=\"font-family:Vazirmatn,sans-serif;background:#edf1f4;color:#31485c;padding:2px 5px;border-radius:4px\">")
                            .append(html(field.value)).append("</code>")
                    } else {
                        append(html(field.value))
                    }
                    append("</p>")
                }
            }
            append("</div>")
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newHtmlText(title, plainText, htmlText))
        val originalText = button.text
        button.text = "کپی شد"
        button.contentDescription = "اطلاعات کارت کپی شد"
        button.announceForAccessibility("اطلاعات کارت کپی شد")
        button.postDelayed({
            button.text = originalText
            button.contentDescription = "کپی اطلاعات کارت"
        }, 1600)
    }

    private fun loadMoreResults() {
        if (!ready || !hasMoreResults || showingFavorites) return
        val query = searchInput.text?.toString().orEmpty().trim()
        val filter = selectedKind
        val exactOnly = exactMatchFilter.isChecked
        val offset = currentOffset
        val serial = ++searchSerial
        searchWork?.cancel(true)
        loadMoreButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "در حال بارگذاری نتایج بیشتر…"
        searchWork = databaseExecutor.submit {
            val page = database.search(query, filter, exactOnly, offset, searchPageSize)
            mainHandler.post {
                if (serial != searchSerial) return@post
                progress.visibility = View.GONE
                loadMoreButton.isEnabled = true
                adapter.append(page.entries)
                currentOffset += page.entries.size
                currentTotal = page.total
                hasMoreResults = currentOffset < currentTotal
                updateLoadMoreButton()
                status.text = resultStatus(query)
            }
        }
    }

    private fun updateLoadMoreButton() {
        val remaining = (currentTotal - currentOffset).coerceAtLeast(0)
        loadMoreButton.visibility = if (!showingFavorites && hasMoreResults) View.VISIBLE else View.GONE
        loadMoreButton.isEnabled = true
        loadMoreButton.text = "نمایش " + persianDigits(minOf(searchPageSize, remaining)) + " نتیجۀ بعدی"
    }

    private fun resultStatus(query: String): String =
        persianDigits(currentOffset) + " از " + persianDigits(currentTotal) + " نتیجه برای «" + query + "»"

    private fun importDatabase() {
        progress.visibility = View.VISIBLE
        databaseExecutor.execute {
            try {
                database.importIfNeeded { message ->
                    mainHandler.post { status.text = message }
                }
                val count = database.totalCount()
                mainHandler.post {
                    ready = true
                    progress.visibility = View.GONE
                    status.text = "آمادهٔ جست‌وجو · " + persianDigits(count) + " مدخل"
                    showInitialMessage()
                    scheduleSearch()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    ready = false
                    progress.visibility = View.GONE
                                status.text = "آماده‌سازی پایگاه‌داده انجام نشد."
                    adapter.submit(emptyList())
                    showMessage("داده‌های همراه اپ خوانده نشد. اپ را دوباره نصب کنید.")
                }
            }
        }
    }

    private fun setFilter(kind: Int?) {
        showingFavorites = false
        selectedKind = kind
        updateFilterStyles()
        scheduleSearch()
    }

    private fun updateFilterStyles() {
        styleFilter(approvedFilter, selectedKind == 0)
        styleFilter(spellingFilter, selectedKind == 1)
        styleFilter(dasturFilter, selectedKind == 2)
    }

    private fun scheduleSearch() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        val task = Runnable { searchNow() }
        pendingSearch = task
        mainHandler.postDelayed(task, 350)
    }

    private fun searchNow() {
        if (!ready) return
        val query = searchInput.text?.toString().orEmpty().trim()
        showingFavorites = false
        val serial = ++searchSerial
        searchWork?.cancel(true)
        loadMoreButton.visibility = View.GONE
        loadMoreButton.isEnabled = true
        currentOffset = 0
        currentTotal = 0
        hasMoreResults = false
        if (query.isEmpty()) {
            progress.visibility = View.GONE
            adapter.submit(emptyList())
            status.text = "برای جست‌وجو، واژه‌ای بنویسید."
            return
        }
        if (query.count { it.isLetterOrDigit() } < 2) {
            progress.visibility = View.GONE
            adapter.submit(emptyList())
            status.text = "برای جست‌وجو دست‌کم دو نویسه بنویسید."
            return
        }
        adapter.submit(emptyList())
        results.scrollToPosition(0)
        status.text = "در حال جست‌وجو…"
        progress.visibility = View.VISIBLE
        val filter = selectedKind
        val exactOnly = exactMatchFilter.isChecked
        searchWork = databaseExecutor.submit {
            val page = database.search(query, filter, exactOnly, 0, searchPageSize)
            mainHandler.post {
                if (serial != searchSerial) return@post
                progress.visibility = View.GONE
                adapter.submit(page.entries)
                results.scrollToPosition(0)
                currentOffset = page.entries.size
                currentTotal = page.total
                hasMoreResults = currentOffset < currentTotal
                updateLoadMoreButton()
                status.text = if (page.total == 0) {
                    "برای «" + query + "» نتیجه‌ای پیدا نشد. املای واژه یا بخش دیگری از آن را جست‌وجو کنید."
                } else {
                    resultStatus(query)
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
        progress.visibility = View.GONE
        status.text = message
    }

    private fun filterButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setPadding(dp(5), 0, dp(5), 0)
        minHeight = 0
        minimumHeight = dp(44)
        stateListAnimator = null
    }

    private fun styleFilter(button: Button, selected: Boolean) {
        button.background = rounded(
            if (selected) Color.rgb(232, 244, 238) else Color.TRANSPARENT,
            Color.TRANSPARENT,
            0,
            9
        )
        button.setTextColor(if (selected) greenDark else muted)
        button.typeface = appTypeface(if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryHolder>() {
        private var items: List<WordEntry> = emptyList()

        fun submit(values: List<WordEntry>) {
            items = values
            notifyDataSetChanged()
        }

        fun append(values: List<WordEntry>) {
            if (values.isEmpty()) return
            val start = items.size
            items = items + values
            notifyItemRangeInserted(start, values.size)
        }

        fun refresh() {
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
            holder.bind(items[position], position + 1)
            applyVazirmatn(holder.itemView)
        }
    }

    private inner class EntryHolder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        fun bind(entry: WordEntry, resultNumber: Int) {
            card.removeAllViews()
            val cardActions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val favoriteButton = TextView(this@MainActivity).apply {
                text = if (isFavorite(entry)) "★" else "☆"
                textSize = 19f
                setTextColor(green)
                gravity = Gravity.CENTER
                minWidth = dp(38)
                minHeight = dp(38)
                contentDescription = if (isFavorite(entry)) "برداشتن از نشان‌شده‌ها" else "افزودن به نشان‌شده‌ها"
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleFavorite(entry) }
            }
            cardActions.addView(favoriteButton, LinearLayout.LayoutParams(dp(38), dp(38)))

            val copyButton = Button(this@MainActivity).apply {
                text = "کپی"
                textSize = 11f
                isAllCaps = false
                setTextColor(muted)
                gravity = Gravity.CENTER
                minHeight = dp(36)
                minimumHeight = dp(36)
                minWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                background = rounded(Color.TRANSPARENT, line, 1, 9)
                stateListAnimator = null
                contentDescription = "کپی اطلاعات کارت"
                val icon = getDrawable(R.drawable.ic_copy)?.mutate()?.apply {
                    setTint(muted)
                    setBounds(0, 0, dp(16), dp(16))
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(4)
                setOnClickListener { copyCardToClipboard(entry, this) }
            }
            cardActions.addView(copyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            cardActions.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))

            val number = TextView(this@MainActivity).apply {
                text = persianDigits(resultNumber)
                textSize = 11f * textScale
                setTextColor(green)
                gravity = Gravity.LEFT
                setPadding(0, 0, 0, dp(3))
            }
            cardActions.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(cardActions, matchWrap())

            val type = when (entry.kind) {
                0 -> "واژگان مصوب فرهنگستان"
                1 -> "فرهنگ املایی خط فارسی"
                else -> "کتاب دستور خط فارسی"
            }
            addText(card, type, 11f, muted, bold = false, top = 0, bottom = 4)

            val englishTerms = splitEnglishTerms(entry.english)
            val queryIsEnglish = searchInput.text?.toString()?.any {
                it in 'A'..'Z' || it in 'a'..'z'
            } == true
            val englishTitle = englishTerms.firstOrNull().orEmpty()
            val showEnglishTitle = entry.kind != 2 && queryIsEnglish &&
                englishTitle.isNotBlank() && entry.title.isNotBlank()

            addText(
                card,
                if (showEnglishTitle) englishTitle else entry.title.ifBlank { entry.english },
                19f,
                greenDark,
                bold = true,
                top = 0,
                bottom = 4,
                queryToHighlight = if (entry.kind == 2) searchInput.text?.toString() else null,
                latin = showEnglishTitle
            )

            if (showEnglishTitle) {
                addField(card, "برابر فارسی:", entry.title, highlighted = true)
            } else {
                englishTerms.firstOrNull()?.let {
                    addField(card, "برابر انگلیسی:", it, ltr = true)
                }
            }
            val otherEnglish = englishTerms.drop(1)
            val queryForOtherEnglish = searchInput.text?.toString()?.trim()?.takeIf { query ->
                showEnglishTitle &&
                    !englishTitle.contains(query, ignoreCase = true) &&
                    otherEnglish.any { it.contains(query, ignoreCase = true) }
            }
            if (otherEnglish.isNotEmpty()) {
                addField(
                    card,
                    "صورت‌های دیگر انگلیسی:",
                    otherEnglish.joinToString(", "),
                    ltr = true,
                    queryToHighlight = queryForOtherEnglish
                )
            }
            if (entry.kind == 2) {
                if (entry.domain.isNotBlank()) addField(card, "بخش:", entry.domain)
                if (entry.definition.isNotBlank()) {
                    addField(
                        card,
                        "متن:",
                        entry.definition,
                        queryToHighlight = searchInput.text?.toString(),
                        expandable = true
                    )
                }
                if (entry.volumes.isNotBlank()) {
                    addField(card, "صفحه:", persianDigits(entry.volumes.replace(",", "،")))
                }
            } else {
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
        }

        private fun addField(
            parent: LinearLayout,
            label: String,
            value: String,
            ltr: Boolean = false,
            queryToHighlight: String? = null,
            expandable: Boolean = false,
            highlighted: Boolean = false
        ) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = if (ltr) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(4), 0, 0)
            }
            val labelView = TextView(this@MainActivity).apply {
                text = label
                textSize = 12f * textScale
                setTextColor(muted)
                typeface = appTypeface(Typeface.BOLD)
                gravity = Gravity.RIGHT
            }
            if (ltr) {
                row.addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val valueView = TextView(this@MainActivity).apply {
                    text = queryToHighlight?.let { highlightEnglishMatch(value, it) } ?: value
                    textSize = 14f * textScale
                    setTextColor(Color.rgb(55, 79, 99))
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    gravity = Gravity.LEFT or Gravity.TOP
                    textDirection = View.TEXT_DIRECTION_LTR
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setTextIsSelectable(true)
                    setPadding(0, dp(2), 0, 0)
                }
                row.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                row.addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val valueView = TextView(this@MainActivity).apply {
                    text = queryToHighlight?.let { highlightQueryText(value, it) } ?: value
                    textSize = 14f * textScale
                    setTextColor(if (highlighted) greenDark else ink)
                    typeface = appTypeface(if (highlighted) Typeface.BOLD else Typeface.NORMAL)
                    gravity = Gravity.RIGHT
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    setTextIsSelectable(true)
                    maxLines = if (expandable && value.length > 240) 4 else Int.MAX_VALUE
                    ellipsize = if (expandable && value.length > 240) android.text.TextUtils.TruncateAt.END else null
                    setPadding(0, 0, dp(7), 0)
                }
                row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (expandable && value.length > 240) {
                    var expanded = false
                    val expandButton = TextView(this@MainActivity).apply {
                        text = "نمایش ادامۀ متن"
                        textSize = 12f * textScale
                        setTextColor(green)
                        gravity = Gravity.RIGHT
                        setPadding(0, dp(5), 0, 0)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            expanded = !expanded
                            valueView.maxLines = if (expanded) Int.MAX_VALUE else 4
                            valueView.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                            text = if (expanded) "بستن ادامهٔ متن" else "نمایش ادامۀ متن"
                        }
                    }
                    parent.addView(row, matchWrap())
                    parent.addView(expandButton, matchWrap())
                    return
                }
            }
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
        bottom: Int,
        queryToHighlight: String? = null,
        latin: Boolean = false
    ) {
        val text = TextView(this).apply {
            this.text = queryToHighlight?.let { highlightQueryText(value, it) } ?: value
            textSize = size * textScale
            setTextColor(color)
            gravity = if (latin) Gravity.LEFT else Gravity.RIGHT
            textDirection = if (latin) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = if (latin) View.TEXT_ALIGNMENT_VIEW_START else View.TEXT_ALIGNMENT_GRAVITY
            typeface = if (latin) {
                Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            } else {
                appTypeface(if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            setPadding(0, dp(top), 0, dp(bottom))
        }
        parent.addView(text, matchWrap())
    }

    private fun highlightQueryText(value: String, query: String): CharSequence {
        fun normalizeWithMap(input: String): Pair<String, List<Int>> {
            val normalized = StringBuilder()
            val originalIndexes = ArrayList<Int>()
            input.forEachIndexed { index, original ->
                val compatible = Normalizer.normalize(original.toString(), Normalizer.Form.NFKC)
                compatible.forEach { character ->
                    val mapped = when (character) {
                        'ي', 'ى' -> 'ی'
                        'ك' -> 'ک'
                        'ۀ', 'ة' -> 'ه'
                        else -> character
                    }
                    if (mapped in '\u064b'..'\u065f' || mapped == '\u0670' || mapped == 'ـ') return@forEach
                    if (mapped.isLetterOrDigit()) {
                        normalized.append(mapped.lowercaseChar())
                        originalIndexes.add(index)
                    }
                }
            }
            return normalized.toString() to originalIndexes
        }

        val (normalizedValue, originalIndexes) = normalizeWithMap(value)
        val normalizedQuery = normalizeWithMap(query).first
        if (normalizedQuery.isBlank() || originalIndexes.isEmpty()) return value

        val highlighted = SpannableString(value)
        var searchFrom = 0
        while (searchFrom < normalizedValue.length) {
            val matchStart = normalizedValue.indexOf(normalizedQuery, searchFrom)
            if (matchStart < 0) break
            val matchEnd = matchStart + normalizedQuery.length
            val originalStart = originalIndexes.getOrNull(matchStart)
            val originalEnd = originalIndexes.getOrNull(matchEnd - 1)?.plus(1)
            if (originalStart != null && originalEnd != null && originalStart < originalEnd) {
                highlighted.setSpan(
                    BackgroundColorSpan(Color.rgb(255, 236, 179)),
                    originalStart,
                    originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                highlighted.setSpan(
                    ForegroundColorSpan(greenDark),
                    originalStart,
                    originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            searchFrom = matchEnd
        }
        return highlighted
    }

    private fun highlightEnglishMatch(value: String, query: String): CharSequence {
        val needle = query.trim()
        if (needle.isBlank()) return value

        val highlighted = SpannableString(value)
        var start = 0
        while (start < value.length) {
            val matchStart = value.indexOf(needle, start, ignoreCase = true)
            if (matchStart < 0) break
            val matchEnd = matchStart + needle.length
            highlighted.setSpan(
                ForegroundColorSpan(greenDark),
                matchStart,
                matchEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = matchEnd
        }
        return highlighted
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
        searchWork?.cancel(true)
        databaseExecutor.shutdownNow()
        super.onDestroy()
    }
}
