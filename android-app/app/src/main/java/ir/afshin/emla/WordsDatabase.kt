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
    val notes: String,
    val refTerms: String
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
                ref_terms TEXT NOT NULL,
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
            SELECT id, kind, title, english, domain, definition, volumes, notes, ref_terms
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
                    notes = cursor.getString(7).orEmpty(),
                    refTerms = cursor.getString(8).orEmpty()
                )
                if (!exactOnly || hasExactTerm(item.title, queryNorm) || hasExactTerm(item.english, queryNorm) || hasExactTerm(item.refTerms, queryNorm)) {
                    val rank = entryRank(item, query)
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

    private fun entryRank(entry: WordEntry, query: String): Int {
        val titleTerms = splitTerms(entry.title)
        val englishTerms = splitTerms(entry.english)
        val refs = splitTerms(entry.refTerms)
        val primaryEnglish = englishTerms.firstOrNull().orEmpty()
        val alternateEnglish = englishTerms.drop(1)
        val q = normalize(query)

        if (titleTerms.any { normalize(it) == q } || normalize(primaryEnglish) == q) return 0
        if (alternateEnglish.any { normalize(it) == q }) return 1

        val titleRank = titleTerms.minOfOrNull { termMatchRank(it, query) } ?: Int.MAX_VALUE
        val primaryRank = if (primaryEnglish.isBlank()) Int.MAX_VALUE else termMatchRank(primaryEnglish, query)
        val primary = minOf(titleRank, primaryRank)
        if (primary < Int.MAX_VALUE) return primary + 1
        if (titleTerms.any { normalize(it).contains(q) } || normalize(primaryEnglish).contains(q)) return 9

        val alternateRank = alternateEnglish.minOfOrNull { termMatchRank(it, query) } ?: Int.MAX_VALUE
        if (alternateRank < Int.MAX_VALUE) return alternateRank + 8
        if (alternateEnglish.any { normalize(it).contains(q) }) return 16
        if (refs.any { normalize(it) == q }) return 18
        if (refs.any { normalize(it).startsWith(q) }) return 19
        return 30
    }

    private fun termMatchRank(raw: String, query: String): Int {
        val q = normalize(query)
        val term = raw.trim()
        if (normalize(term) == q) return 0
        val boundary = Regex("[\\p{L}\\p{M}\\p{N}_]+(?:\\u200c[\\p{L}\\p{M}\\p{N}_]+)*")
        var best = Int.MAX_VALUE
        for (match in boundary.findAll(term)) {
            val token = normalize(match.value)
            if (token.isEmpty()) continue
            val before = term.substring(0, match.range.first)
            val after = term.substring(match.range.last + 1)
            val starts = before.isBlank() || before.trim().all { !it.isLetterOrDigit() }
            val hyphenBoundary = before.endsWith("-") || before.endsWith("–") || after.startsWith("-") || after.startsWith("–")
            val rank = when {
                token == q && starts && hyphenBoundary -> 3
                token == q && starts -> 2
                token == q -> 4
                token.startsWith(q) -> 6
                token.contains(q) -> 7
                else -> Int.MAX_VALUE
            }
            best = minOf(best, rank)
        }
        if (best != Int.MAX_VALUE) return best
        return if (normalize(term).contains(q)) 9 else Int.MAX_VALUE
    }

    private fun splitTerms(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return if (Regex(",\\s*(?:and|or)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            listOf(text)
        } else {
            text.split(Regex("[,،;؛]")).map(String::trim).filter(String::isNotEmpty)
        }
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
        private const val DATABASE_SCHEMA_VERSION = 2
        private const val DATA_VERSION = "3"
        private const val BUNDLED_DATABASE_ASSET = "emla-words.db"
    }
}
