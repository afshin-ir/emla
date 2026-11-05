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
DATA_VERSION = "3"

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


def split_terms(value):
    return [part.strip() for part in SEPARATORS.split(str(value or "")) if part.strip()]


def split_english_terms(value):
    raw = str(value or "").strip()
    return [raw] if re.search(r",\s*(?:and|or)\s+", raw, re.I) else split_terms(raw)


def collect_indexed_bases(rows):
    suffixes, plain = {}, set()
    for row in rows:
        for field in (cell(row, 0), cell(row, 3)):
            for term in split_terms(field):
                match = re.match(r"^(.*?)([0-9۰-۹٠-٩]+)$", term.strip())
                if match and match.group(1).strip():
                    suffixes.setdefault(normalize(match.group(1)), set()).add(match.group(2))
                else:
                    key = normalize(term)
                    if key:
                        plain.add(key)
    return {base for base, numbers in suffixes.items() if len(numbers) > 1 or base in plain}


def strip_index(value, indexed_bases):
    raw = str(value or "").strip()
    match = re.match(r"^(.*?)(\s*[0-9۰-۹٠-٩]+)$", raw)
    if not match:
        return raw
    base = match.group(1).strip()
    return base if re.search(r"\s[0-9۰-۹٠-٩]+$", raw) or normalize(base) in indexed_bases else raw


def visible_persian_terms(value, indexed_bases):
    terms = split_terms(value)
    def is_index(term):
        match = re.match(r"^(.*?)(\s*[0-9۰-۹٠-٩]+)$", term.strip())
        return bool(match and match.group(1).strip() and normalize(match.group(1)) in indexed_bases
                    and not re.search(r"\s[0-9۰-۹٠-٩]+$", term))
    has_plain = any(not is_index(term) for term in terms)
    chosen = [term for term in terms if not is_index(term)] if has_plain else terms
    result, seen = [], set()
    for term in chosen:
        clean = strip_index(term, indexed_bases)
        key = normalize(clean)
        if key and key not in seen:
            seen.add(key)
            result.append(clean)
    return result


def link_reference_rows(rows, records, indexed_bases):
    english_index, persian_index = {}, {}
    for record in records:
        for term in record["english_terms"]:
            english_index.setdefault(normalize(strip_index(term, indexed_bases)), []).append(record)
        for term in record["persian_terms"]:
            persian_index.setdefault(normalize(strip_index(term, indexed_bases)), []).append(record)

    def choose(index, key, source_volumes):
        available = list(dict.fromkeys(id(target) for target in index.get(normalize(key), [])))
        targets = {id(target): target for target in records}
        available = [targets[item] for item in available]
        if not available:
            return None
        same_book = [target for target in available
                     if not source_volumes or not target["volume_list"]
                     or any(v in target["volume_list"] for v in source_volumes)]
        pool = same_book or available
        return pool[0] if len(pool) == 1 else None

    for row in rows:
        title, source, _, english, _, extra_one, extra_two = (cell(row, i) for i in range(7))
        if title.strip() and english.strip():
            continue
        source_volumes = row[7] if len(row) > 7 and isinstance(row[7], list) else []
        raw_english = extra_one.strip()
        arrow = re.search(r"\s*(?:→|->)\s*", raw_english)
        if arrow:
            left = raw_english[:arrow.start()].lstrip("*").strip()
            right = raw_english[arrow.end():].strip()
            target = choose(english_index, right, source_volumes)
            if target and left:
                target["ref_terms"].append(strip_index(left, indexed_bases))
            continue
        persian_arrow = re.match(r"^←\s*(.+)$", source.strip())
        if persian_arrow and (title.strip() or extra_two.strip()):
            target = choose(persian_index, strip_index(persian_arrow.group(1), indexed_bases), source_volumes)
            if target:
                for alias in split_terms(title or extra_two):
                    target["ref_terms"].append(strip_index(alias, indexed_bases))


def insert(db, kind, title, english, domain, definition, volumes, notes, extras="", ref_terms=""):
    searchable = " ".join((title, english, extras, ref_terms))
    db.execute(
        """INSERT INTO entries
           (kind,title,english,domain,definition,volumes,notes,ref_terms,title_norm,english_norm,search_norm)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        (kind, title, english, domain, definition, volumes, notes, ref_terms,
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
            ref_terms TEXT NOT NULL,
            title_norm TEXT NOT NULL,
            english_norm TEXT NOT NULL,
            search_norm TEXT NOT NULL
        );
        CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    with gzip.open(DATA_FILE, "rt", encoding="utf-8") as stream:
        rows = json.load(stream)
    indexed_bases = collect_indexed_bases(rows)
    records = []
    for row in rows:
        title, source, domains, english, definition, extra_one, extra_two, volumes, notes = (
            cell(row, i) for i in range(9)
        )
        if not title.strip() or not english.strip():
            continue
        display_title = "، ".join(visible_persian_terms(title, indexed_bases))
        english_terms = list(dict.fromkeys(
            strip_index(term, indexed_bases) for term in split_english_terms(english)
        ))
        records.append({
            "title": display_title or title,
            "english": ", ".join(term for term in english_terms if term),
            "domain": "، ".join(domain_terms(domains)),
            "definition": definition,
            "volumes": display_volumes(row[7] if len(row) > 7 else ""),
            "notes": notes,
            "extras": " ".join(x for x in (source, extra_one, extra_two, title, english) if x),
            "persian_terms": split_terms(title),
            "english_terms": split_english_terms(english),
            "volume_list": row[7] if len(row) > 7 and isinstance(row[7], list) else [],
            "ref_terms": [],
        })
    link_reference_rows(rows, records, indexed_bases)
    for record in records:
        ref_terms = list(dict.fromkeys(term for term in record["ref_terms"] if term.strip()))
        insert(db, 0, record["title"], record["english"], record["domain"],
               record["definition"], record["volumes"], record["notes"],
               record["extras"], "، ".join(ref_terms))
    del records

    with SPELLING_FILE.open(encoding="utf-8") as stream:
        spelling = json.load(stream).get("entries", [])
    db.executemany(
        """INSERT INTO entries
           (kind,title,english,domain,definition,volumes,notes,ref_terms,title_norm,english_norm,search_norm)
           VALUES (1,?,?,?,'','','','',?,'',?)""",
        ((str(term), "", "", normalize(term), normalize(term)) for term in spelling if str(term).strip()),
    )
    db.execute("CREATE INDEX entries_title_norm ON entries(title_norm)")
    db.execute("CREATE INDEX entries_english_norm ON entries(english_norm)")
    db.execute("CREATE INDEX entries_kind ON entries(kind)")
    db.execute("INSERT INTO app_meta(key,value) VALUES('data_version',?)", (DATA_VERSION,))
    db.execute("PRAGMA user_version=2")
    db.commit()
    count = db.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
    db.close()
    print(f"Built {OUTPUT} ({count} entries, {OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
