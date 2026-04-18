#!/usr/bin/env python3
"""
Generates a unified emoji_data.xml from the 60 existing split XML files.
Adds search tags for each emoji using the 'emoji' library.

Requirements:
    pip install emoji regex

Output:
    app/src/main/res/xml/emoji_data.xml

Usage:
    cd <project root>
    python3 scripts/generate_emoji_xml.py
"""

import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter

# ── dependency check ────────────────────────────────────────────────────────

try:
    import regex
    def split_emoji_string(text):
        """Split a run of emojis into individual grapheme clusters."""
        return [g for g in regex.findall(r'\X', text) if g.strip()]
except ImportError:
    sys.exit("Missing dependency: pip install regex")

try:
    import emoji as emoji_lib
except ImportError:
    sys.exit("Missing dependency: pip install emoji")

# ── config ───────────────────────────────────────────────────────────────────

SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
XML_DIR     = os.path.join(PROJECT_DIR, "app", "src", "main", "res", "xml")
OUTPUT_FILE = os.path.join(XML_DIR, "emoji_data.xml")

VERSIONS    = [3, 11, 12, 13, 14, 15]
CATEGORIES  = ["smileys", "people", "animals", "food", "travel",
               "activities", "objects", "symbols", "flags"]

DICT_SIZE   = 256   # max words in dictionary (fits in 1 byte index)
MAX_TAGS    = 15    # tags per emoji

# ── helpers ──────────────────────────────────────────────────────────────────

STOP_WORDS = {
    "a", "an", "the", "and", "or", "of", "for", "with",
    "in", "on", "at", "to", "by", "as", "is", "it", "no",
    "up", "do", "be", "my", "we"
}

def get_tags(char: str) -> list[str]:
    """Return up to MAX_TAGS English keywords for an emoji character."""
    data = emoji_lib.EMOJI_DATA.get(char, {})
    words = []

    # Primary name  e.g. "grinning face"
    name = data.get("en", "")
    if name:
        # Clean format: "grinning face" or "grinning_face"
        clean = name.strip(":").replace("_", " ").lower()
        words.extend(clean.split())

    # Aliases  e.g. [":grinning:", ":smile:"]
    for alias in data.get("alias", []):
        # Strip surrounding colons, split on underscore/space
        clean = alias.strip(":").replace("_", " ").lower()
        words.extend(clean.split())

    # Deduplicate, filter stop words and punctuation
    seen = set()
    result = []
    for w in words:
        w = w.strip(".:,!?-_()")
        if len(w) > 1 and w not in seen and w not in STOP_WORDS:
            seen.add(w)
            result.append(w)
        if len(result) == MAX_TAGS:
            break

    return result


def read_emoji_xml(path: str) -> list[str]:
    """Parse one XML file and return list of emoji grapheme clusters."""
    if not os.path.exists(path):
        return []
    try:
        tree = ET.parse(path)
        text = "".join(tree.getroot().itertext())
        return split_emoji_string(text)
    except ET.ParseError:
        return []


def build_dictionary(all_tags: list[list[str]]) -> dict[str, int]:
    """
    Pick the DICT_SIZE most frequent tag words and assign 1-based IDs.
    ID 0 is reserved as a separator / escape marker.
    """
    counter = Counter(word for tags in all_tags for word in tags)
    top_words = [word for word, _ in counter.most_common(DICT_SIZE)]
    return {word: idx + 1 for idx, word in enumerate(top_words)}  # IDs 1-256


def encode_tags(tags: list[str], dictionary: dict[str, int]) -> str:
    """
    Encode a tag list as a comma-separated string.
    Words in the dictionary are stored as their numeric ID.
    Words not in the dictionary are stored as literal text.

    Example:  ["face", "happy", "escocia"]  →  "1,3,escocia"
    """
    parts = []
    for word in tags:
        parts.append(str(dictionary[word]) if word in dictionary else word)
    return ",".join(parts)

# ── main ─────────────────────────────────────────────────────────────────────

def main():
    # 1. Read all existing XML files
    print("Reading existing XML files...")
    entries = []  # list of (version, category, emoji_char)
    for version in VERSIONS:
        for category in CATEGORIES:
            filename = f"emojis_v{version}_{category}.xml"
            path = os.path.join(XML_DIR, filename)
            emojis = read_emoji_xml(path)
            for char in emojis:
                entries.append((version, category, char))

    print(f"  Found {len(entries)} emojis across {len(VERSIONS) * len(CATEGORIES)} files")

    # 2. Collect tags for every emoji
    print("Collecting tags...")
    all_tags = []
    for _, _, char in entries:
        all_tags.append(get_tags(char))

    no_tags = sum(1 for t in all_tags if not t)
    print(f"  {len(all_tags) - no_tags}/{len(all_tags)} emojis have tags")

    # 3. Build dictionary from most frequent words
    print(f"Building {DICT_SIZE}-word dictionary...")
    dictionary = build_dictionary(all_tags)
    print(f"  Top 5 words: {list(dictionary.keys())[:5]}")

    # 4. Write unified XML
    print(f"Writing {OUTPUT_FILE}...")

    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append('<emoji_data>')
    lines.append('')

    # Dictionary section
    lines.append('    <dictionary>')
    for word, idx in dictionary.items():
        lines.append(f'        <w id="{idx}">{word}</w>')
    lines.append('    </dictionary>')
    lines.append('')

    # Emojis section
    lines.append('    <emojis>')
    for (version, category, char), tags in zip(entries, all_tags):
        encoded = encode_tags(tags, dictionary)
        tag_attr = f' t="{encoded}"' if encoded else ''
        lines.append(f'        <e v="{version}" c="{category}"{tag_attr}>{char}</e>')
    lines.append('    </emojis>')
    lines.append('')
    lines.append('</emoji_data>')

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    # 5. Stats
    size_kb = os.path.getsize(OUTPUT_FILE) / 1024
    print(f"  Done. File size: {size_kb:.1f} KB")
    print(f"  Emojis: {len(entries)}")
    print(f"  Dictionary entries: {len(dictionary)}")
    print()
    print("Next step: update EmojiPanelView.java to read emoji_data.xml")
    print("           and delete the 60 individual XML files.")


if __name__ == "__main__":
    main()
