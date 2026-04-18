#!/usr/bin/env python3
"""
Generates compact .bin emoji files per language.

Sources:
  - unicode.org/Public/emoji/15.0/emoji-test.txt  → emoji list, version, group
  - unicode-org/cldr annotations/{lang}.xml        → search tags per language

Binary format (version 2):
    [4 bytes] magic "EMJI"
    [1 byte]  format version: 2
    [1 byte]  number of categories (C)
    per category:
        [1 byte]  name length
        [N bytes] name in UTF-8
    [2 bytes] number of emojis (big-endian)
    per emoji:
        [1 byte]  emoji length in UTF-8 bytes
        [N bytes] emoji in UTF-8
        [1 byte]  emoji version bucket (3, 11, 12, 13, 14, 15)
        [1 byte]  category index (0..C-1)
        [1 byte]  number of tags
        per tag:
            [1 byte]  tag length in bytes
            [N bytes] tag in UTF-8

Requirements: none (stdlib only)

Usage:
    python3 generate_emoji_bin.py              # all languages
    python3 generate_emoji_bin.py en es fr     # specific languages
    python3 generate_emoji_bin.py --list       # list available languages
    python3 generate_emoji_bin.py en --dump    # dump first entries of generated file

Output:
    emoji-data/bin/cldr_<lang>.bin
"""

import os
import re
import sys
import struct
import urllib.request
import xml.etree.ElementTree as ET

# ── config ────────────────────────────────────────────────────────────────────

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/15.0/emoji-test.txt"
CLDR_URL       = "https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/{lang}.xml"

SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
OUTPUT_DIR  = os.path.join(SCRIPT_DIR, "bin")
CACHE_DIR   = os.path.join(SCRIPT_DIR, "cache")

FORMAT_MAGIC   = b"EMJI"
FORMAT_VERSION = 2
MAX_TAGS       = 15

# Maps Unicode group/subgroup → Kokey category name.
# "Animals & Nature" is split by subgroup: animal-* → animals, plant-* → nature.
GROUP_TO_CATEGORY = {
    "Smileys & Emotion": "smileys",
    "People & Body":     "people",
    "Animals & Nature":  None,       # split by subgroup below
    "Food & Drink":      "food",
    "Travel & Places":   "travel",
    "Activities":        "activities",
    "Objects":           "objects",
    "Symbols":           "symbols",
    "Flags":             "flags",
    "Component":         None,       # skip (skin tones, hair)
}

SUBGROUP_TO_CATEGORY = {
    # Animals & Nature split
    "animal-mammal":    "animals",
    "animal-bird":      "animals",
    "animal-amphibian": "animals",
    "animal-reptile":   "animals",
    "animal-marine":    "animals",
    "animal-bug":       "animals",
    "plant-flower":     "nature",
    "plant-other":      "nature",
    # Travel & Places subgroups that belong in nature (moons, stars, weather, water)
    "sky & weather":    "nature",
    "time":             "nature",
}

# Kokey category order (matches EmojiPanelView.java CATEGORIES array)
CATEGORIES = [
    "smileys", "people", "animals", "nature",
    "food", "travel", "activities", "objects", "symbols", "flags"
]

# Emoji version float → version bucket used in the app
def version_bucket(e_version: float) -> int:
    if e_version >= 15.0: return 15
    if e_version >= 14.0: return 14
    if e_version >= 13.0: return 13
    if e_version >= 12.0: return 12
    if e_version >= 11.0: return 11
    return 3  # everything up to E5.0 was in the original emoji set

# Android locale → CLDR filename when they differ
CLDR_CODE_MAP = {
    "nb": "no",  # Norwegian Bokmål
}

LANGUAGES = {
    "en": "English",       "ar": "Arabic",        "hy": "Armenian",
    "az": "Azerbaijani",   "bn": "Bengali",        "bg": "Bulgarian",
    "zh": "Chinese",       "hr": "Croatian",       "cs": "Czech",
    "da": "Danish",        "nl": "Dutch",          "fi": "Finnish",
    "fr": "French",        "ka": "Georgian",       "de": "German",
    "el": "Greek",         "he": "Hebrew",         "hi": "Hindi",
    "hu": "Hungarian",     "id": "Indonesian",     "it": "Italian",
    "ja": "Japanese",      "km": "Khmer",          "ko": "Korean",
    "lo": "Lao",           "lv": "Latvian",        "lt": "Lithuanian",
    "mk": "Macedonian",    "ms": "Malay",          "ml": "Malayalam",
    "mn": "Mongolian",     "ne": "Nepali",         "nb": "Norwegian",
    "fa": "Persian/Farsi", "pl": "Polish",         "pt": "Portuguese",
    "ro": "Romanian",      "ru": "Russian",        "sr": "Serbian",
    "si": "Sinhala",       "sk": "Slovak",         "es": "Spanish",
    "sw": "Swahili",       "sv": "Swedish",        "ta": "Tamil",
    "te": "Telugu",        "th": "Thai",           "tr": "Turkish",
    "uk": "Ukrainian",     "ur": "Urdu",           "uz": "Uzbek",
    "vi": "Vietnamese",    "cy": "Welsh",
}

# ── data structures ───────────────────────────────────────────────────────────

class EmojiEntry:
    __slots__ = ("char", "version", "category")
    def __init__(self, char: str, version: int, category: str):
        self.char     = char
        self.version  = version
        self.category = category

# ── download / parse ──────────────────────────────────────────────────────────

def download(url: str, dest: str) -> bool:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest):
        return True
    try:
        print(f"  Downloading {url}")
        urllib.request.urlretrieve(url, dest)
        return True
    except Exception as e:
        print(f"  ERROR downloading {url}: {e}")
        if os.path.exists(dest): os.remove(dest)
        return False


def parse_emoji_test(path: str) -> list[EmojiEntry]:
    """
    Parse emoji-test.txt → list of EmojiEntry with char, version, category.
    Only includes fully-qualified emojis. Skips Components.
    """
    entries = []
    current_group    = None
    current_subgroup = None

    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip()

            m = re.match(r"^# group: (.+)$", line)
            if m:
                current_group    = m.group(1)
                current_subgroup = None
                continue

            m = re.match(r"^# subgroup: (.+)$", line)
            if m:
                current_subgroup = m.group(1)
                continue

            if not line or line.startswith("#"):
                continue

            # Data line:  1F600 ; fully-qualified # 😀 E1.0 grinning face
            m = re.match(r"^([0-9A-F ]+)\s+;\s+(\S+)\s+#\s+(\S+)\s+E([\d.]+)", line)
            if not m:
                continue

            status  = m.group(2)
            char    = m.group(3)
            e_ver   = float(m.group(4))

            if status != "fully-qualified":
                continue

            # Resolve category — subgroup overrides group when present in the map
            category = SUBGROUP_TO_CATEGORY.get(current_subgroup)
            if category is None:
                category = GROUP_TO_CATEGORY.get(current_group)
            if not category:
                continue

            entries.append(EmojiEntry(char, version_bucket(e_ver), category))

    return entries


def parse_cldr(path: str) -> dict[str, list[str]]:
    """Parse CLDR annotations XML → dict: emoji_char → [tag, ...]"""
    result = {}
    tree = ET.parse(path)
    for ann in tree.getroot().iter("annotation"):
        if ann.get("type") == "tts" or ann.text is None:
            continue
        char = ann.get("cp", "")
        if not char:
            continue
        tags = [t.strip() for t in ann.text.split("|") if t.strip()]
        if tags:
            result[char] = tags[:MAX_TAGS]
    return result

# ── write / verify / dump ─────────────────────────────────────────────────────

def write_bin(entries: list[EmojiEntry], tags: dict[str, list[str]], out_path: str) -> int:
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    cat_index = {c: i for i, c in enumerate(CATEGORIES)}

    with open(out_path, "wb") as f:
        # Header
        f.write(FORMAT_MAGIC)
        f.write(struct.pack("B", FORMAT_VERSION))

        # Category list
        f.write(struct.pack("B", len(CATEGORIES)))
        for cat in CATEGORIES:
            b = cat.encode("utf-8")
            f.write(struct.pack("B", len(b)))
            f.write(b)

        # Emoji count
        f.write(struct.pack(">H", len(entries)))

        # Entries
        for entry in entries:
            emoji_bytes = entry.char.encode("utf-8")
            if len(emoji_bytes) > 255:
                continue

            entry_tags  = tags.get(entry.char, [])
            tag_list    = []
            for tag in entry_tags:
                tb = tag.encode("utf-8")
                if len(tb) <= 255:
                    tag_list.append(tb)

            f.write(struct.pack("B", len(emoji_bytes)))
            f.write(emoji_bytes)
            f.write(struct.pack("B", entry.version))
            f.write(struct.pack("B", cat_index[entry.category]))
            f.write(struct.pack("B", len(tag_list)))
            for tb in tag_list:
                f.write(struct.pack("B", len(tb)))
                f.write(tb)

    return len(entries)


def verify_bin(path: str) -> bool:
    try:
        with open(path, "rb") as f:
            if f.read(4) != FORMAT_MAGIC: return False
            ver      = struct.unpack("B", f.read(1))[0]
            if ver != FORMAT_VERSION: return False
            num_cats = struct.unpack("B", f.read(1))[0]
            for _ in range(num_cats):
                f.read(struct.unpack("B", f.read(1))[0])
            count = struct.unpack(">H", f.read(2))[0]
            for _ in range(count):
                f.read(struct.unpack("B", f.read(1))[0])  # emoji
                f.read(2)                                   # version + cat_index
                num_tags = struct.unpack("B", f.read(1))[0]
                for _ in range(num_tags):
                    f.read(struct.unpack("B", f.read(1))[0])
        return True
    except Exception as e:
        print(f"  Verify error: {e}")
        return False


def dump_bin(path: str, limit: int = 8):
    with open(path, "rb") as f:
        f.read(4)  # magic
        f.read(1)  # version
        num_cats = struct.unpack("B", f.read(1))[0]
        cats = []
        for _ in range(num_cats):
            cats.append(f.read(struct.unpack("B", f.read(1))[0]).decode())
        count = struct.unpack(">H", f.read(2))[0]
        print(f"  categories: {cats}")
        print(f"  emojis: {count}")
        for i in range(min(limit, count)):
            char    = f.read(struct.unpack("B", f.read(1))[0]).decode("utf-8")
            ver     = struct.unpack("B", f.read(1))[0]
            cat_idx = struct.unpack("B", f.read(1))[0]
            n_tags  = struct.unpack("B", f.read(1))[0]
            t = [f.read(struct.unpack("B", f.read(1))[0]).decode() for _ in range(n_tags)]
            print(f"  {char}  v{ver}  {cats[cat_idx]}  {t}")
        if count > limit:
            print(f"  ... {count - limit} more")


def write_xml_debug(entries: list[EmojiEntry], tags: dict[str, list[str]], out_path: str):
    """
    Write a human-readable XML debug file showing all emoji data.
    Groups emojis by category, shows version and tags per emoji.
    """
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append(f'<!-- Debug output: {len(entries)} emojis across {len(CATEGORIES)} categories -->')
    lines.append('<emoji_data>')

    current_cat = None
    for entry in entries:
        if entry.category != current_cat:
            if current_cat is not None:
                lines.append(f'    </category>')
            current_cat = entry.category
            lines.append(f'')
            lines.append(f'    <category name="{current_cat}">')
        tag_list = tags.get(entry.char, [])
        tag_str  = ", ".join(tag_list)
        lines.append(f'        <e v="{entry.version}" t="{tag_str}">{entry.char}</e>')

    if current_cat is not None:
        lines.append(f'    </category>')

    lines.append('')
    lines.append('</emoji_data>')

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    size_kb = os.path.getsize(out_path) / 1024
    print(f"  XML debug → {out_path}  ({size_kb:.1f} KB)")

# ── main ──────────────────────────────────────────────────────────────────────

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]

    if "--list" in flags:
        print("Available languages:")
        for code, name in sorted(LANGUAGES.items()):
            print(f"  {code:6}  {name}")
        return

    langs = args if args else list(LANGUAGES.keys())
    for lang in langs:
        if lang not in LANGUAGES:
            print(f"Unknown language: {lang}. Use --list to see available.")
            sys.exit(1)

    # Download emoji-test.txt once
    emoji_test_path = os.path.join(CACHE_DIR, "emoji-test.txt")
    if not download(EMOJI_TEST_URL, emoji_test_path):
        sys.exit("Could not download emoji-test.txt")

    print("Parsing emoji-test.txt...")
    all_emojis = parse_emoji_test(emoji_test_path)
    print(f"  {len(all_emojis)} fully-qualified emojis across {len(CATEGORIES)} categories\n")

    results = []
    for lang in langs:
        print(f"[{lang}] {LANGUAGES[lang]}")

        cldr_code = CLDR_CODE_MAP.get(lang, lang)
        cldr_path = os.path.join(CACHE_DIR, f"{lang}.xml")
        if not download(CLDR_URL.format(lang=cldr_code), cldr_path):
            results.append((lang, False, 0, 0))
            continue

        tags     = parse_cldr(cldr_path)
        out_path = os.path.join(OUTPUT_DIR, f"cldr_{lang}.bin")
        count    = write_bin(all_emojis, tags, out_path)
        size_kb  = os.path.getsize(out_path) / 1024
        valid    = verify_bin(out_path)

        print(f"  {'OK' if valid else 'CORRUPTED'}  {count} emojis  {size_kb:.1f} KB  →  {out_path}")
        if valid and "--dump" in flags:
            dump_bin(out_path)
        if valid and "--xml-debug" in flags:
            xml_path = os.path.join(OUTPUT_DIR, f"debug_{lang}.xml")
            write_xml_debug(all_emojis, tags, xml_path)
        results.append((lang, valid, count, size_kb))
        print()

    ok       = [r for r in results if r[1]]
    failed   = [r for r in results if not r[1]]
    total_kb = sum(r[3] for r in ok)
    print("─" * 50)
    print(f"Done: {len(ok)} OK, {len(failed)} failed")
    print(f"Total size: {total_kb:.1f} KB ({total_kb/1024:.2f} MB)")
    if failed:
        print(f"Failed: {[r[0] for r in failed]}")


if __name__ == "__main__":
    main()
