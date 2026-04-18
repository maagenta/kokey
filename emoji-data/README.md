# emoji-data

Scripts for generating compact binary emoji files used by the keyboard.

Each `.bin` file contains the full emoji dataset for one language: emoji characters,
Unicode version, display category, and CLDR search tags.

**Sources:**
- `unicode.org/Public/emoji/15.0/emoji-test.txt` — emoji list, version, and group
- `unicode-org/cldr annotations/{lang}.xml` — search tags per language

---

## Files

```
emoji-data/
├── generate_emoji_bin.py   ← main script
├── bin/                    ← generated output files
│   ├── cldr_en.bin         ← deployed to app res/raw/
│   ├── cldr_es.bin
│   ├── debug_en.xml        ← human-readable debug output (--xml-debug)
│   └── ...
└── cache/                  ← cached downloads (safe to delete to force refresh)
    ├── emoji-test.txt
    ├── en.xml
    └── ...
```

---

## Usage

```bash
# Generate .bin for all supported languages
python3 generate_emoji_bin.py

# Generate .bin for specific languages only
python3 generate_emoji_bin.py en es fr de

# List all supported language codes
python3 generate_emoji_bin.py --list

# Print first entries of generated .bin file (verify contents)
python3 generate_emoji_bin.py en --dump

# Generate a human-readable XML alongside the .bin (for debugging)
python3 generate_emoji_bin.py en --xml-debug

# Flags can be combined
python3 generate_emoji_bin.py en es --dump --xml-debug
```

No external dependencies — stdlib only.

---

## Deploying to the app

After generating, copy the English file to `res/raw/`:

```bash
cp emoji-data/bin/cldr_en.bin app/src/main/res/raw/cldr_en.bin
```

The app reads `res/raw/cldr_en.bin` at runtime via `EmojiPanelView`.

---

## Supported languages

53 languages matching Kokey keyboard layouts. Run `--list` to see all codes and names.

Notable mapping: `nb` (Norwegian Bokmål) downloads from CLDR's `no.xml` since CLDR uses a different filename.

---

## Binary file format (version 2)

### Header

| Size | Description |
|------|-------------|
| 4 bytes | Magic: `EMJI` (ASCII) |
| 1 byte  | Format version: `2` |
| 1 byte  | Number of categories (C) |
| per category: 1 byte length + N bytes UTF-8 | Category name |
| 2 bytes | Number of emojis (big-endian uint16) |

### Emoji entry (repeated per emoji)

| Size | Description |
|------|-------------|
| 1 byte  | Emoji length in bytes (L) |
| L bytes | Emoji in UTF-8 |
| 1 byte  | Version bucket: `3`, `11`, `12`, `13`, `14`, or `15` |
| 1 byte  | Category index (0..C-1) |
| 1 byte  | Number of search tags (T) |
| per tag: 1 byte length + N bytes UTF-8 | Tag string |

### Version buckets → Android API

| Bucket | Unicode versions | Min Android API |
|--------|-----------------|-----------------|
| 3  | E0.6 – E5.0 | All devices |
| 11 | E11.0       | API 28 (Android 9) |
| 12 | E12.0–12.1  | API 29 (Android 10) |
| 13 | E13.0–13.1  | API 30 (Android 11) |
| 14 | E14.0       | API 32 (Android 12L) |
| 15 | E15.0–15.1  | API 34 (Android 14) |

### Category mapping

Unicode groups are mapped to Kokey categories. Notable overrides:

| Unicode subgroup | Kokey category | Reason |
|-----------------|----------------|--------|
| `sky & weather` | `nature` | Original keyboard grouped weather with nature |
| `time`          | `nature` | Moon phases grouped with nature |
| `plant-flower`  | `nature` | Split from Animals & Nature group |
| `plant-other`   | `nature` | Split from Animals & Nature group |
| `animal-*`      | `animals` | Split from Animals & Nature group |
| `Component`     | *(skipped)* | Skin tones and hair modifiers |

---

## Updating for new emoji versions

Delete the cache and re-run to fetch the latest data:

```bash
rm -rf emoji-data/cache/
python3 emoji-data/generate_emoji_bin.py en
cp emoji-data/bin/cldr_en.bin app/src/main/res/raw/cldr_en.bin
```
