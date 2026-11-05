package ir.afshin.emla

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
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
    DATABASE_NAME,
    null,
    DATABASE_SCHEMA_VERSION
) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS entries")
        db.execSQL("DROP TABLE IF EXISTS app_meta")
        createSchema(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
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

    /**
     * The full SQLite database is generated during the APK build. First launch only copies
     * that ready-to-use database into app storage instead of parsing and indexing JSON.
     */
    fun importIfNeeded(progress: (String) -> Unit) {
        progress("در حال نصب پایگاه‌دادهٔ آفلاین…")
        val target = appContext.getDatabasePath(DATABASE_NAME)
        if (!hasCurrentData(target)) {
            close()
            target.parentFile?.mkdirs()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            target.delete()

            val temporary = File(target.parentFile, "$DATABASE_NAME.tmp")
            temporary.delete()
            appContext.assets.open(BUNDLED_DATABASE_ASSET).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
        writableDatabase.close()
    }

    private fun hasCurrentData(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM app_meta WHERE key = 'data_version'", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == DATA_VERSION
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun totalCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun search(query: String, kindFilter: Int?, exactOnly: Boolean): List<WordEntry> {
        val queryNorm = normalize(query)
        if (queryNorm.isEmpty()) return emptyList()

        val filterClause = if (kindFilter == null) "" else " AND kind = ?"
        val limitClause = if (exactOnly) "" else " LIMIT 1200"
        val sql = """
            SELECT id, kind, title, english, domain, definition, volumes, notes
            FROM entries
            WHERE search_norm LIKE ?$filterClause
            ORDER BY
                CASE
                    WHEN title_norm = ? OR english_norm = ? THEN 0
                    WHEN title_norm LIKE ? OR english_norm LIKE ? THEN 1
                    ELSE 2
                END,
                title COLLATE NOCASE$limitClause
        """.trimIndent()
        val args = ArrayList<String>().apply {
            add("%$queryNorm%")
            kindFilter?.let { add(it.toString()) }
            add(queryNorm)
            add(queryNorm)
            add("$queryNorm%")
            add("$queryNorm%")
        }

        val candidates = ArrayList<Pair<Int, WordEntry>>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
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
                if (!exactOnly || hasExactTerm(item.title, queryNorm) || hasExactTerm(item.english, queryNorm)) {
                    val rank = minOf(titleRank(item.title, query), titleRank(item.english, query))
                    candidates.add(rank to item)
                }
            }
        }
        return candidates
            .sortedWith(compareBy<Pair<Int, WordEntry>> { it.first }.thenBy { it.second.title.lowercase(Locale.ROOT) })
            .take(120)
            .map { it.second }
    }

    private fun hasExactTerm(value: String, queryNorm: String): Boolean {
        if (normalize(value) == queryNorm) return true
        val termPattern = Regex("[\\p{L}\\p{M}\\p{N}_]+(?:\\u200c[\\p{L}\\p{M}\\p{N}_]+)*")
        return termPattern.findAll(value).any { normalize(it.value) == queryNorm }
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

    companion object {
        private const val DATABASE_NAME = "emla-words.db"
        private const val DATABASE_SCHEMA_VERSION = 1
        private const val DATA_VERSION = "2"
        private const val BUNDLED_DATABASE_ASSET = "emla-words.db"
    }
}
