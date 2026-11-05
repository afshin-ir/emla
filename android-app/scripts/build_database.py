#!/usr/bin/env python3
"""Build the read-only offline SQLite database bundled with the Android app."""
import gzip
import json
import re
import sqlite3
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA_FILE = ROOT / "words-new-data.json.gz"
SPELLING_FILE = ROOT / "dictionary.json"
OUTPUT = ROOT / "android-app/app/src/main/assets/emla-words.db"
DATA_VERSION = "2"

SUBJECT_LABELS = {
    "اپتیک": "فیزیک- اپتیک",
    "الیاف": "مهندسی بسپار- الیاف",
    "باغبانی": "کشاورزی- علوم باغبانی",
    "برق": "مهندسی برق",
    "بسپار": "مهندسی بسپار",
    "بسته‌بندی": "مهندسی بسپار- مجموعهٔ بسته‌بندی",
    "پ. پزشکی": "علوم پایۀ پزشکی",
    "پست": "مهندسی مخابرات - پست",
    "پلاستیک": "مهندسی بسپار-پلاستیک",
    "تجسمی": "هنرهای تجسمی",
    "تشریحی": "علوم تشریحی",
    "ج. سیاسی": "جغرافیای سیاسی",
    "جانوری": "زیست‌شناسی- علوم جانوری",
    "جَوّ": "علوم جوّ",
    "جوشکاری": "جوشکاری و آزمایش‌های غیرمخرب",
    "چسب": "مهندسی بسپار ـ چسب",
    "حرارتی": "مهندسی حرارتی و برودتی",
    "خودرو": "قطعات و اجزای خودرو",
    "دارویی": "علوم دارویی",
    "دامپزشکی": "دامپزشکی - پاتوبیولوژی",
    "دامی": "کشاورزی- علوم دامی",
    "درون‌شهری": "حمل‌ونقل درون‌شهری- جاده‌ای",
    "دریایی": "حمل‌ونقل دریایی",
    "دندان‌پزشکی": "پزشکی-دندان‌پزشکی",
    "ر. استثنائی": "روان‌شناسی کودکان استثنایی",
    "ر. عمومی": "روابط‌عمومی و تبلیغات بازرگانی",
    "رنگ": "مهندسی بسپار ـ علوم و فنّاوری رنگ",
    "ریلی": "حمل‌ونقل ریلی",
    "زبان": "زبان‌شناسی",
    "زراعت": "کشاورزی - زراعت و اصلاح نباتات",
    "زیست": "زیست‌شناسی",
    "ژن": "زیست‌شناسی - ژن‌شناسی و زیست‌فنّاوری",
    "سلامت": "علوم سلامت",
    "سیاسی": "علوم سیاسی و روابط بین‌الملل",
    "سینما": "سینما و تلویزیون",
    "فاوا": "فنّاوری اطلاعات و ارتباطات",
    "فنّاوری غذا": "علوم و فنّاوری غذا",
    "قهوه‌پژوهی": "علوم و فنّاوری غذا- قهوه‌پژوهی",
    "کتابداری": "علوم کتابداری و اطلاع‌رسانی",
    "گ. دارویی": "گیاهان دارویی",
    "گردشگری": "گردشگری و جهانگردی",
    "گیاهی": "زیست‌شناسی- علوم گیاهی",
    "لاستیک": "مهندسی بسپار- لاستیک",
    "م. پروژه": "مدیریت-مدیریت پروژه",
    "م. پزشکی": "مهندسی پزشکی",
    "م. سلامت": "مدیریت سلامت",
    "م. شیمی": "مهندسی شیمی",
    "م. فنّاوری": "مدیریت فنّاوری",
    "متالورژی": "مهندسی مواد و متالورژی",
    "محیط‌زیست": "مهندسی محیط‌زیست و انرژی",
    "مخابرات": "مهندسی مخابرات",
    "مط. جنگل": "مهندسی منابع طبیعی- محیط‌زیست و جنگل",
    "مط. حیات‌وحش": "مهندسی منابع طبیعی - شاخهٔ حیات‌وحش",
    "مط. محیط‌زیست": "مهندسی منابع طبیعی - علوم جنگل و محیط‌زیست",
    "معماری": "مهندسی معماری و شهرسازی",
    "مکانیک": "مهندسی مکانیک",
    "مهندسی": "علوم مهندسی",
    "میکرب": "زیست‌شناسی - میکرب‌شناسی",
    "نانو": "نانوفنّاوری",
    "نجوم رصدی": "نجوم رصدی و آشکارسازها",
    "نظامی": "علوم نظامی",
    "نقشه‌برداری": "مهندسی نقشه‌برداری",
    "نمایشی": "هنرهای نمایشی",
    "هوایی": "حمل‌ونقل هوایی",
}
INVISIBLES = re.compile("[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]")
SEPARATORS = re.compile("[,،;؛]")
DIACRITICS = re.compile("[\u064b-\u065f\u0670ـ]")
TOKEN_RE = re.compile(r"[\w\u0600-\u06ff]+(?:\u200c[\w\u0600-\u06ff]+)*", re.UNICODE)


def normalize(value):
    value = unicodedata.normalize("NFKC", str(value or ""))
    value = value.translate(str.maketrans({"ي": "ی", "ى": "ی", "ك": "ک", "ۀ": "ه", "ة": "ه"}))
    value = DIACRITICS.sub("", value).lower()
    return "".join(ch for ch in value if ch.isalnum())


def cell(row, index):
    if index >= len(row) or row[index] is None:
        return ""
    value = row[index]
    if isinstance(value, list):
        return "، ".join(str(item) for item in value if item is not None and str(item).strip())
    return str(value)


def domain_terms(value):
    raw = INVISIBLES.sub("", str(value or "")).strip()
    raw = raw.strip("[]").strip()
    terms = []
    for part in SEPARATORS.split(raw):
        term = INVISIBLES.sub("", part).rstrip("\u200c").strip().strip("[]").strip()
        if not term or term.startswith("←"):
            continue
        if term == "شیمی م. شیمی":
            terms.extend(("شیمی", "م. شیمی"))
        else:
            terms.append(term)
    return list(dict.fromkeys(SUBJECT_LABELS.get(term, term) for term in terms))


def display_volumes(value):
    if isinstance(value, list):
        return "، ".join(str(v) for v in value if v is not None)
    return str(value or "")


def insert(db, kind, title, english, domain, definition, volumes, notes, extras=""):
    searchable = " ".join((title, english, domain, definition, volumes, notes, extras))
    db.execute(
        """INSERT INTO entries
           (kind,title,english,domain,definition,volumes,notes,title_norm,english_norm,search_norm)
           VALUES (?,?,?,?,?,?,?,?,?,?)""",
        (kind, title, english, domain, definition, volumes, notes,
         normalize(title), normalize(english), normalize(searchable)),
    )


def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.unlink(missing_ok=True)
    db = sqlite3.connect(OUTPUT)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        CREATE TABLE entries (
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
        );
        CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    with gzip.open(DATA_FILE, "rt", encoding="utf-8") as stream:
        rows = json.load(stream)
    batch = []
    for row in rows:
        title, source, domains, english, definition, extra_one, extra_two, volumes, notes = (
            cell(row, i) for i in range(9)
        )
        full_domain = "، ".join(domain_terms(domains))
        batch.append((0, title, english, full_domain, definition,
                      display_volumes(row[7] if len(row) > 7 else ""), notes,
                      " ".join(x for x in (source, extra_one, extra_two) if x)))
    db.executemany(
        """INSERT INTO entries
           (kind,title,english,domain,definition,volumes,notes,title_norm,english_norm,search_norm)
           VALUES (?,?,?,?,?,?,?,?,?,?)""",
        ((kind, title, english, domain, definition, volumes, notes,
          normalize(title), normalize(english),
          normalize(" ".join((title, english, domain, definition, volumes, notes, extras))))
         for kind, title, english, domain, definition, volumes, notes, extras in batch),
    )
    del batch

    with SPELLING_FILE.open(encoding="utf-8") as stream:
        spelling = json.load(stream).get("entries", [])
    db.executemany(
        """INSERT INTO entries
           (kind,title,english,domain,definition,volumes,notes,title_norm,english_norm,search_norm)
           VALUES (1,?,?,?,'','','',?,'',?)""",
        ((str(term), "", "", normalize(term), normalize(term)) for term in spelling if str(term).strip()),
    )
    db.execute("CREATE INDEX entries_title_norm ON entries(title_norm)")
    db.execute("CREATE INDEX entries_english_norm ON entries(english_norm)")
    db.execute("CREATE INDEX entries_kind ON entries(kind)")
    db.execute("INSERT INTO app_meta(key,value) VALUES('data_version',?)", (DATA_VERSION,))
    db.execute("PRAGMA user_version=1")
    db.commit()
    count = db.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
    db.close()
    print(f"Built {OUTPUT} ({count} entries, {OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
