package ir.afshin.emla

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale

data class WordEntry(
    val id: Long,
    val kind: Int,
    val title: String,
    val english: String,
    val domain: String,
    val definition: String,
    val volumes: String,
    val notes: String
)

class WordsDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "emla-words.db",
    null,
    1
) {
    private val appContext = context.applicationContext
    private val dataVersion = "1"

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind INTEGER NOT NULL,
                title TEXT NOT NULL,
                english TEXT NOT NULL,
                domain TEXT NOT NULL,
                definition TEXT NOT NULL,
                volumes TEXT NOT NULL,
                notes TEXT NOT NULL,
                title_norm TEXT NOT NULL,
                english_norm TEXT NOT NULL,
                search_norm TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX entries_title_norm ON entries(title_norm)")
        db.execSQL("CREATE INDEX entries_english_norm ON entries(english_norm)")
        db.execSQL("CREATE INDEX entries_kind ON entries(kind)")
        db.execSQL("CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS entries")
        db.execSQL("DROP TABLE IF EXISTS app_meta")
        onCreate(db)
    }

    fun importIfNeeded(progress: (String) -> Unit) {
        val db = writableDatabase
        if (readMeta(db, "data_version") == dataVersion) return

        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            progress("در حال آماده‌سازی واژگان مصوب…")
            importApprovedWords(db, progress)
            progress("در حال افزودن فرهنگ املایی…")
            importSpellingDictionary(db, progress)
            val values = ContentValues().apply {
                put("key", "data_version")
                put("value", dataVersion)
            }
            db.insertWithOnConflict("app_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun totalCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun search(query: String, kindFilter: Int?): List<WordEntry> {
        val queryNorm = normalize(query)
        if (queryNorm.isEmpty()) return emptyList()

        val db = readableDatabase
        val kindValue = kindFilter ?: -1
        val sql = """
            SELECT id, kind, title, english, domain, definition, volumes, notes,
                   title_norm, english_norm
            FROM entries
            WHERE search_norm LIKE ? AND (? = -1 OR kind = ?)
            ORDER BY
                CASE
                    WHEN title_norm = ? OR english_norm = ? THEN 0
                    WHEN title_norm LIKE ? OR english_norm LIKE ? THEN 1
                    ELSE 2
                END,
                title COLLATE NOCASE
            LIMIT 600
        """.trimIndent()
        val candidates = ArrayList<Pair<Int, WordEntry>>()
        db.rawQuery(
            sql,
            arrayOf(
                "%" + queryNorm + "%",
                kindValue.toString(),
                kindValue.toString(),
                queryNorm,
                queryNorm,
                queryNorm + "%",
                queryNorm + "%"
            )
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val item = WordEntry(
                    id = cursor.getLong(0),
                    kind = cursor.getInt(1),
                    title = cursor.getString(2).orEmpty(),
                    english = cursor.getString(3).orEmpty(),
                    domain = cursor.getString(4).orEmpty(),
                    definition = cursor.getString(5).orEmpty(),
                    volumes = cursor.getString(6).orEmpty(),
                    notes = cursor.getString(7).orEmpty()
                )
                val rank = minOf(titleRank(item.title, query), titleRank(item.english, query))
                candidates.add(rank to item)
            }
        }
        return candidates
            .sortedWith(compareBy<Pair<Int, WordEntry>> { it.first }.thenBy { it.second.title.lowercase(Locale.ROOT) })
            .take(120)
            .map { it.second }
    }

    private fun titleRank(raw: String, query: String): Int {
        if (raw.isBlank()) return 9
        val q = normalize(query)
        val separated = normalizeWithSeparators(raw)
        if (separated == q) return 0
        if (separated.startsWith(q)) {
            val rest = separated.substring(q.length)
            if (rest.isEmpty()) return 0
            return when {
                rest.startsWith("\u200c") -> 1
                rest.startsWith(" ") -> 2
                rest.startsWith("-") || rest.startsWith("–") -> 3
                !rest.first().isLetterOrDigit() -> 3
                else -> 4
            }
        }
        return if (normalize(raw).contains(q)) 5 else 9
    }

    private fun importApprovedWords(db: SQLiteDatabase, progress: (String) -> Unit) {
        var count = 0
        appContext.assets.open("words-new-data.json").use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).use { input ->
                JsonReader(input).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val element = JsonParser.parseReader(reader)
                        if (element.isJsonArray) {
                            val row = element.asJsonArray
                            val title = cell(row, 0)
                            val source = cell(row, 1)
                            val domain = cell(row, 2)
                            val english = cell(row, 3)
                            val definition = cell(row, 4)
                            val extraOne = cell(row, 5)
                            val extraTwo = cell(row, 6)
                            val volumes = cell(row, 7)
                            val notes = cell(row, 8)
                            insertEntry(
                                db = db,
                                kind = 0,
                                title = title,
                                english = english,
                                domain = domain,
                                definition = definition,
                                volumes = volumes,
                                notes = notes,
                                searchExtras = listOf(source, extraOne, extraTwo).filter { it.isNotBlank() }.joinToString(" ")
                            )
                        }
                        count++
                        if (count % 1000 == 0) progress("آماده‌سازی واژگان مصوب: " + count.toString())
                    }
                    reader.endArray()
                }
            }
        }
        progress("واژگان مصوب آماده شد: " + count.toString())
    }

    private fun importSpellingDictionary(db: SQLiteDatabase, progress: (String) -> Unit) {
        val root = appContext.assets.open("dictionary.json").bufferedReader(Charsets.UTF_8).use {
            JsonParser.parseReader(it)
        }
        val entries = root.asJsonObject.getAsJsonArray("entries") ?: JsonArray()
        var count = 0
        for (entry in entries) {
            val title = primitiveText(entry)
            if (title.isNotBlank()) {
                insertEntry(
                    db = db,
                    kind = 1,
                    title = title,
                    english = "",
                    domain = "",
                    definition = "صورت ثبت‌شده در فرهنگ املایی خط فارسی",
                    volumes = "",
                    notes = "",
                    searchExtras = ""
                )
            }
            count++
            if (count % 5000 == 0) progress("آماده‌سازی فرهنگ املایی: " + count.toString())
        }
        progress("فرهنگ املایی آماده شد: " + count.toString())
    }

    private fun insertEntry(
        db: SQLiteDatabase,
        kind: Int,
        title: String,
        english: String,
        domain: String,
        definition: String,
        volumes: String,
        notes: String,
        searchExtras: String
    ) {
        val allText = listOf(title, english, domain, definition, volumes, notes, searchExtras).joinToString(" ")
        val values = ContentValues().apply {
            put("kind", kind)
            put("title", title)
            put("english", english)
            put("domain", domain)
            put("definition", definition)
            put("volumes", volumes)
            put("notes", notes)
            put("title_norm", normalize(title))
            put("english_norm", normalize(english))
            put("search_norm", normalize(allText))
        }
        db.insertOrThrow("entries", null, values)
    }

    private fun readMeta(db: SQLiteDatabase, key: String): String? {
        db.rawQuery("SELECT value FROM app_meta WHERE key = ?", arrayOf(key)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun cell(row: JsonArray, index: Int): String {
        if (index >= row.size()) return ""
        val value = row.get(index)
        if (value == null || value.isJsonNull) return ""
        if (value.isJsonArray) {
            return value.asJsonArray.map { primitiveText(it) }.filter { it.isNotBlank() }.joinToString("، ")
        }
        return primitiveText(value)
    }

    private fun primitiveText(value: JsonElement?): String {
        if (value == null || value.isJsonNull) return ""
        if (value.isJsonPrimitive) return value.asString
        if (value.isJsonArray) {
            return value.asJsonArray.map { primitiveText(it) }.filter { it.isNotBlank() }.joinToString("، ")
        }
        return value.toString()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace('ۀ', 'ه')
            .replace('ة', 'ه')
            .replace(Regex("[\\u064b-\\u065f\\u0670ـ]"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun normalizeWithSeparators(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace('ۀ', 'ه')
            .replace('ة', 'ه')
            .replace(Regex("[\\u064b-\\u065f\\u0670ـ]"), "")
            .lowercase(Locale.ROOT)
            .trim()
}
