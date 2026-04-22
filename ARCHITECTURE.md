# KoKey Architecture

## Table of Contents

1. [Overview](#overview)
2. [Package Structure](#package-structure)
3. [Layer Architecture](#layer-architecture)
4. [Core Components](#core-components)
   - [LatinIME](#1-latinime--the-entry-point)
   - [InputLogic](#2-inputlogic--input-processing)
   - [KeyboardSwitcher](#3-keyboardswitcher--state-machine)
   - [PointerTracker](#4-pointertracker--touch-tracking)
   - [KeyboardView / MainKeyboardView](#5-keyboardview--mainkeyboardview--rendering)
   - [RichInputConnection](#6-richinputconnection--editor-bridge)
5. [Emoji Subsystem](#emoji-subsystem)
   - [EmojiPanelView](#1-emojipanelview)
   - [EmojiSearchView](#2-emojisearchview)
   - [EmojiLazyLoader](#3-emojilazy-loader)
   - [EmojiRenderer](#4-emojirenderer)
6. [Settings System](#settings-system)
7. [Keyboard Layout System](#keyboard-layout-system)
   - [KeyboardLayoutSet](#keyboardlayoutset)
   - [KeyboardBuilder](#keyboardbuilder)
   - [Key and KeyboardParams](#key-and-keyboardparams)
8. [View Hierarchy](#view-hierarchy)
9. [Data Flow](#data-flow)
   - [Normal Key Press](#normal-key-press)
   - [Long Press (More Keys)](#long-press-more-keys)
   - [Emoji Panel Open](#emoji-panel-open)
   - [Emoji Search from Long Press](#emoji-search-from-long-press)
10. [Key Design Patterns](#key-design-patterns)
11. [Thread Model](#thread-model)
12. [Constants and Key Codes](#constants-and-key-codes)
13. [ProGuard / R8 Considerations](#proguard--r8-considerations)

---

## Overview

KoKey is an Android soft keyboard built on top of AOSP LatinIME. It is implemented as an `InputMethodService` — Android's mechanism for custom keyboards. The app has no external dependencies; everything is built on the Android SDK.

The architecture follows a layered design:

- **Service layer** — `LatinIME` as the IME entry point
- **Logic layer** — `InputLogic` for input processing, `KeyboardSwitcher` for state management
- **Input layer** — `PointerTracker` for touch tracking
- **View layer** — `KeyboardView`, `MainKeyboardView`, `EmojiPanelView`, `EmojiSearchView`
- **Editor layer** — `RichInputConnection` for communicating with the target app

---

## Package Structure

```
uk.coko.forge.kokey
├── compat/             Compatibility utilities for older Android versions
├── emoji/              Emoji panel, search, rendering, and data loading
├── event/              Event and InputTransaction models
├── keyboard/           Core keyboard views, input tracking, and layout
│   └── internal/       Internal state machines, builders, and helpers
├── latin/              IME service, input connection, settings, and utils
│   ├── common/         Constants, utilities (StringUtils, LocaleUtils, etc.)
│   ├── define/         Debug flags
│   ├── inputlogic/     Input processing (InputLogic)
│   ├── settings/       Preferences, SettingsValues, settings fragments
│   └── utils/          Miscellaneous utilities
```

---

## Layer Architecture

```
┌──────────────────────────────────────────────────────┐
│                    Android IME Framework              │
│              (InputMethodService callbacks)           │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│                    LatinIME.java                      │
│         Service layer — lifecycle, routing            │
└───────┬──────────────┬────────────────┬──────────────┘
        │              │                │
┌───────▼──────┐ ┌─────▼──────┐ ┌──────▼──────────────┐
│  InputLogic  │ │ Keyboard   │ │   RichInputMethod    │
│  (processing)│ │ Switcher   │ │   Manager (subtypes) │
└───────┬──────┘ └─────┬──────┘ └──────────────────────┘
        │              │
┌───────▼──────┐ ┌─────▼──────────────────────────────┐
│ RichInput    │ │ MainKeyboardView / EmojiPanelView /  │
│ Connection   │ │ EmojiSearchView                     │
│ (editor ops) │ └─────┬──────────────────────────────┘
└──────────────┘       │
               ┌───────▼────────┐
               │ PointerTracker  │
               │ (touch events)  │
               └────────────────┘
```

---

## Core Components

### 1. LatinIME — The Entry Point

**File:** `latin/LatinIME.java`

`LatinIME` extends `InputMethodService` and is the root of the entire keyboard. Android calls its lifecycle methods when the keyboard is needed.

#### Lifecycle

| Callback | Purpose |
|---|---|
| `onCreate()` | Initialize Settings, RichInputMethodManager, KeyboardSwitcher |
| `onCreateInputView()` | Inflate `input_view.xml`, wire up EmojiPanelView, EmojiSearchView |
| `onStartInputView(EditorInfo, boolean)` | Load keyboard for the active editor, refresh settings |
| `onFinishInputView(boolean)` | Clean up resources |
| `onConfigurationChanged()` | Handle rotation and theme changes |

#### Key Responsibilities

- Holds references to `InputLogic`, `KeyboardSwitcher`, `mEmojiSearchView`, `mInputView`
- Implements `KeyboardActionListener` — receives key events from `MainKeyboardView`
- Dispatches key codes to `InputLogic.onCodeInput()`
- Controls emoji panel lifecycle via `showEmojiPanel()`, `hideEmojiPanel()`, `showEmojiSearch()`, `showEmojiSearchFromPanel()`
- Handles `onCustomRequest()` for special codes like `CUSTOM_CODE_EMOJI_SEARCH`
- Posts async operations via `UIHandler` (shift state updates, memory deallocation)

#### UIHandler

Internal `Handler` subclass for deferred UI operations:
- `MSG_UPDATE_SHIFT_STATE` — recalculate shift after cursor movement
- `MSG_DEALLOCATE_MEMORY` — free offscreen buffers when keyboard is hidden

---

### 2. InputLogic — Input Processing

**File:** `latin/inputlogic/InputLogic.java`

Receives key codes and translates them into text editor operations. Contains no UI code.

#### Entry Point

```
LatinIME.onCodeInput()
  → InputLogic.onCodeInput(SettingsValues, Event)
      → handleFunctionalEvent()   (for special keys)
      → handleNonFunctionalEvent() (for regular characters)
          → handleNonSeparatorEvent()
          → handleSeparatorEvent()
```

#### Special Key Routing

| Code | Handler |
|---|---|
| `CODE_DELETE` | `handleBackspaceEvent()` — delete char or selection |
| `CODE_SHIFT` | `handleShiftEvent()` — update shift state |
| `CODE_SWITCH_ALPHA_SYMBOL` | `handleSymbolsEvent()` |
| `CODE_EMOJI` | `onEmojiKeyPressed()` → `mLatinIME.showEmojiPanel()` |
| `CODE_SETTINGS` | `onSettingsKeyPressed()` |
| `CODE_ACTION_NEXT/PREVIOUS` | `performEditorAction()` |
| `CODE_LANGUAGE_SWITCH` | `switchToNextSubtype()` |

#### InputTransaction

Every `onCodeInput` call returns an `InputTransaction` object that describes what changed — mainly whether the shift state needs to be updated. `LatinIME` reads this and schedules shift updates via `UIHandler`.

---

### 3. KeyboardSwitcher — State Machine

**File:** `keyboard/KeyboardSwitcher.java`

Singleton that manages which keyboard is currently displayed and handles all state transitions (shift, symbols, emoji panel).

#### Keyboard States

```
Alphabet (normal)
  ↕ shift press
Alphabet Shifted
  ↕ double tap shift
Alphabet Shift Locked
  ↕ ?123 key
Symbols
  ↕ shift press
Symbols Shifted
```

The state logic lives in `KeyboardState.java`, which implements a formal state machine. `KeyboardSwitcher` implements `KeyboardState.SwitchActions` to receive state change commands.

#### Emoji Panel Control

```java
showEmojiPanel()   // sets EmojiPanelView VISIBLE, hides MainKeyboardView
hideEmojiPanel()   // sets EmojiPanelView GONE, shows MainKeyboardView
```

The emoji panel is an overlay in `FrameLayout` — it does not replace the keyboard view; it covers it. This is critical: `EmojiSearchView` sits inside the `LinearLayout` below the `EmojiPanelView`, so the emoji panel must be explicitly hidden before search can be shown.

#### KeyboardLayoutSet

`KeyboardSwitcher` builds a `KeyboardLayoutSet` during `loadKeyboard()`, which encapsulates all keyboard variants for the current locale and editor type (email, URL, password, etc.).

---

### 4. PointerTracker — Touch Tracking

**File:** `keyboard/PointerTracker.java`

One `PointerTracker` instance exists per active touch pointer (finger). The static pool is managed by `PointerTrackerQueue`.

#### Touch Event Flow

```
MainKeyboardView.onTouchEvent(MotionEvent)
  → processMotionEvent()
      → PointerTracker.processMotionEvent()
          → onDownEvent()    ACTION_DOWN
          → onMoveEvent()    ACTION_MOVE
          → onUpEvent()      ACTION_UP / ACTION_CANCEL
```

#### Key Detection

On every significant movement, `KeyDetector.detectHitKey()` finds which `Key` is under the pointer, using proximity correction to favor nearby valid keys.

#### Long Press

When a key is held past `keyLongpressTimeout` (from `TimerHandler`), `onLongPressed()` fires:

- For most keys → `showMoreKeysKeyboard()` displays a popup with alternate characters
- For the emoji key (if `sLongPressEmojiSearchEnabled`) → fires `CUSTOM_CODE_EMOJI_SEARCH` via `onCustomRequest()`

#### Emoji Search Guard

```java
// Suppresses more-keys popups during emoji search
private static boolean sEmojiSearchActive = false;
public static void setEmojiSearchActive(boolean active) { ... }
```

When `sEmojiSearchActive` is true, `showMoreKeysKeyboard()` returns early, preventing popups from appearing over the search UI.

#### Minimum Press Duration (Emoji Key)

```java
private static final long EMOJI_KEY_MIN_PRESS_MS = 170;
// In detectAndSendKey():
if (code == Constants.CODE_EMOJI
        && System.currentTimeMillis() - mStartTime < EMOJI_KEY_MIN_PRESS_MS) {
    return; // ignore accidental taps
}
```

---

### 5. KeyboardView / MainKeyboardView — Rendering

#### KeyboardView

**File:** `keyboard/KeyboardView.java`

Base view class responsible for drawing the keyboard keys. Uses an offscreen bitmap buffer for performance.

**Rendering pipeline:**
1. `onDraw(Canvas)` checks if a full redraw or partial redraw is needed
2. `onDrawKeyboard(Canvas)` iterates over keys, clips to dirty rectangle
3. `onDrawKey(Key, Canvas, Paint)` draws background and top visuals for each key
4. `onDrawKeyTopVisuals()` draws label, hint label, and icon

**Number hints:**

Controlled by `mShowNumberHints`. When false, digit-only hint labels are skipped during rendering. Setting is read from `Settings.readShowNumberHints()` in `setKeyboard()`.

#### MainKeyboardView

**File:** `keyboard/MainKeyboardView.java`

Extends `KeyboardView` and adds:
- Touch input handling (delegates to `PointerTracker`)
- More-keys panel display and dismissal
- Language label on spacebar
- Key preview popups via `DrawingPreviewPlacerView`
- Double-tap shift detection
- Long-press/repeat timer management via `TimerHandler`

Reads `PREF_LONG_PRESS_EMOJI_SEARCH` in `setKeyboard()` and calls `PointerTracker.setLongPressEmojiSearchEnabled()`.

---

### 6. RichInputConnection — Editor Bridge

**File:** `latin/RichInputConnection.java`

Wraps Android's `InputConnection` with caching and extra utilities. All text operations go through here.

- Maintains a local cache of surrounding text (`EDITOR_CONTENTS_CACHE_SIZE = 1024` chars) to avoid expensive IPC round-trips
- Provides `commitText()`, `deleteSurroundingText()`, `setSelection()`, `performEditorAction()`
- Handles cursor position tracking independently of editor callbacks
- Gracefully handles apps that do not implement `InputConnection` correctly

---

## Emoji Subsystem

### 1. EmojiPanelView

**File:** `emoji/EmojiPanelView.java`

Full-screen emoji picker with category tabs.

#### Structure

```
EmojiPanelView (FrameLayout)
├── EmojiGridView               — scrollable emoji grid
├── HorizontalScrollView        — category tab bar
│   └── category tab buttons    — one per category
├── [keyboard button]           — returns to QWERTY
└── [search button]             — opens emoji search
```

#### Categories

`Smileys · People · Animals · Nature · Food · Travel · Activities · Objects · Symbols · Flags`

#### Rendering Modes

| Mode | Class | How it works |
|---|---|---|
| Smooth | `EmojiSmoothLoader` (internal) | Pre-renders all category bitmaps on background thread at init |
| Light (default) | `EmojiLazyLoader` | Renders bitmaps on demand as grid scrolls, caches up to 80 |

Mode is controlled by `Settings.PREF_EMOJI_RENDERING`. Default is `light`.

#### Binary Emoji Data

Emoji metadata is stored in a compact binary file (`cldr_en.bin`) instead of XML files. `loadBin()` reads it from assets, `parseBin(ByteBuffer)` extracts emoji strings and their CLDR tags for search. This replaced 60 individual XML files.

#### Emoji Version Filtering

Each emoji is associated with a Unicode version, which maps to a minimum Android API level. Emojis that require a higher API than the device's SDK are excluded from the panel.

#### Search Interface

```java
void searchLazy(String query, int chunkSize, CancelCheck cancel, SearchChunkCallback cb)
```

Called by `EmojiSearchView`. Executes on a background thread, delivers results in chunks to avoid blocking the UI.

---

### 2. EmojiSearchView

**File:** `emoji/EmojiSearchView.java`

Overlay search UI that appears above the keyboard.

#### Layout

```
EmojiSearchView (LinearLayout)
├── EditText (search field)
├── EmojiSearchStripView (result strip — horizontally scrollable bitmaps)
└── [delete button]
```

#### Activation Flow

```
LatinIME.showEmojiSearch()
  → mEmojiSearchView.setVisibility(VISIBLE)
  → mEmojiSearchView.show()
      → attachDimOverlay()   (dims keyboard behind search)
      → focus search field
```

#### Deactivation Flow

```
EmojiSearchView.close()
  → detachDimOverlay()
  → LatinIME.hideEmojiSearch()
      → mEmojiSearchActive = false
      → PointerTracker.setEmojiSearchActive(false)
      → setVisibility(GONE)
```

#### EmojiSearchStripView

**File:** `emoji/EmojiSearchStripView.java`

Renders search results as a horizontal strip of pre-rasterized emoji bitmaps on a hardware layer for smooth scrolling. Tapping an emoji inserts it and closes search.

#### Dim Overlay

`EmojiSearchKeyDimOverlay` is a semi-transparent view attached to the `WindowManager` that dims the keyboard area while search is active.

---

### 3. EmojiLazy Loader

**File:** `emoji/EmojiLazyLoader.java`

Implements `EmojiLoader` for light rendering mode.

```
EmojiGridView.onDraw()
  → EmojiLazyLoader.get(index)
      → if cached: return Bitmap
      → if executor shut down: recreate executor
      → if not pending: submit render task
          → EmojiRenderer.render(emoji, size)
          → cache result
          → invoke invalidate callback
      → return null (placeholder)
```

**Cache:** `LruCache<Integer, Bitmap>` with capacity of 80 entries.

**Executor lifecycle:** `recycle()` calls `shutdownNow()`. On next `get()`, if `mExecutor.isShutdown()`, a new `SingleThreadExecutor` is created. This prevents `RejectedExecutionException` when the panel is reopened after closing.

---

### 4. EmojiRenderer

**File:** `emoji/EmojiRenderer.java`

Renders a single emoji string to a `Bitmap` using Android's text rendering stack. Handles emoji sequences (ZWJ sequences, flags, skin tones) that span multiple code points.

---

## Settings System

**File:** `latin/settings/Settings.java`

Singleton. Initialized in `LatinIME.onCreate()`.

#### Preference Keys (selected)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `pref_auto_cap` | boolean | true | Auto-capitalization |
| `pref_show_emoji_key` | boolean | true | Show emoji key |
| `pref_show_number_row` | boolean | false | Show number row |
| `pref_show_special_chars` | boolean | false | Show special char row |
| `pref_show_number_hints` | boolean | false | Show digit hints on letter keys |
| `pref_long_press_emoji_search` | boolean | true | Long press emoji key opens search |
| `pref_emoji_rendering` | string | "light" | Smooth or lazy emoji rendering |
| `pref_keyboard_height_scale` | int | 100 | Keyboard height percentage |
| `pref_keyboard_color` | string | — | Custom keyboard color |
| `pref_space_swipe` | boolean | true | Swipe space to move cursor |

#### SettingsValues

A snapshot of all settings, created by `Settings.loadSettings(InputAttributes)`. Passed by value to components that need it, so there are no threading issues from mid-operation changes. `LatinIME` reloads this snapshot on every `onStartInputView`.

#### MDM Restrictions

`Settings` also integrates with Android's `RestrictionsManager` to support Mobile Device Management (enterprise deployments). Admins can lock languages, keyboard themes, or specific preferences.

#### Settings Fragments

| Fragment | Screen |
|---|---|
| `SettingsFragment` | Root screen |
| `PreferencesSettingsFragment` | Main preferences (layout, emoji, hints) |
| `AppearanceSettingsFragment` | Theme and colors |
| `KeyPressSettingsFragment` | Key press feedback |
| `LanguagesSettingsFragment` | Language / subtype management |
| `ThemeSettingsFragment` | Theme selection |

---

## Keyboard Layout System

### KeyboardLayoutSet

**File:** `keyboard/KeyboardLayoutSet.java`

Built by `KeyboardSwitcher` for the current locale and editor type. Contains all keyboard variants: alphabet, symbols, symbols-shifted, number, phone, etc. Each variant is built lazily on first access.

### KeyboardBuilder

**File:** `keyboard/internal/KeyboardBuilder.java`

Parses keyboard XML resource files (in `res/xml/`) and builds a `Keyboard` object. Handles:
- Row and key definitions
- Key styles and style inheritance
- More-keys (popup characters) specifications
- Keyboard-wide parameters (height, padding, touch slop)

### Key and KeyboardParams

- **`Key.java`** — a single key: code, label, icon, bounds, more-keys, visual attributes, action flags
- **`KeyboardParams.java`** — all parameters needed to build a keyboard: dimensions, keys list, proximity info

### Key Action Flags

Relevant flags for special behavior:

| Flag | Meaning |
|---|---|
| `noKeyPreview` | Don't show press popup |
| `enableLongPress` | Allow long-press timer to fire |
| `isRepeatable` | Fire repeat events while held |
| `isSticky` | Key stays active after release (modifier keys) |

The emoji key uses `noKeyPreview|enableLongPress` to support long-press → search.

---

## View Hierarchy

```
InputView (FrameLayout)
│
├── LinearLayout (vertical, gravity=bottom)
│   ├── EmojiSearchView          GONE by default
│   │   ├── EditText             search field
│   │   ├── EmojiSearchStripView result strip
│   │   └── delete button
│   │
│   └── MainKeyboardView         VISIBLE by default
│       └── DrawingPreviewPlacerView  (key popups, floating overlays)
│
└── EmojiPanelView               GONE by default
    ├── EmojiGridView
    ├── HorizontalScrollView (category tabs)
    ├── keyboard return button
    └── search button
```

**Important:** `EmojiPanelView` is a sibling of the `LinearLayout`, sitting on top in the `FrameLayout`. It covers everything including `EmojiSearchView`. Before showing emoji search, `EmojiPanelView` must be explicitly hidden.

---

## Data Flow

### Normal Key Press

```
1. User touches key
2. MainKeyboardView.onTouchEvent(MotionEvent)
3. PointerTracker.processMotionEvent()
4. PointerTracker detects key on ACTION_UP
5. PointerTracker.callListenerOnCodeInput()
6. LatinIME.onCodeInput(Event)
7. InputLogic.onCodeInput(SettingsValues, Event)
8. RichInputConnection.commitText(char)  →  target app receives text
9. InputTransaction returned to LatinIME
10. UIHandler.MSG_UPDATE_SHIFT_STATE
11. KeyboardSwitcher.requestUpdatingShiftState()
12. MainKeyboardView.setKeyboard() / invalidateKey(shift key)
```

### Long Press (More Keys)

```
1. User holds key past keyLongpressTimeout
2. TimerHandler fires long-press message
3. PointerTracker.onLongPressed()
4. DrawingProxy.showMoreKeysKeyboard(key, tracker)
5. MoreKeysKeyboard built from key.moreKeys
6. MoreKeysKeyboardView shown as popup
7. User slides to selection and releases
8. MoreKeysKeyboardView.onCodeInput()  →  same path as normal key press
```

### Emoji Panel Open

```
1. User taps emoji key (short press, ≥ 170ms)
2. PointerTracker fires CODE_EMOJI
3. InputLogic.onEmojiKeyPressed()
4. LatinIME.showEmojiPanel()
5. KeyboardSwitcher.showEmojiPanel()
   → EmojiPanelView.setVisibility(VISIBLE)
   → MainKeyboardView.setVisibility(GONE)
   → EmojiPanelView.initialize()
```

### Emoji Search from Long Press

```
1. User holds emoji key past longpressTimeout
2. PointerTracker.onLongPressed()
   → sLongPressEmojiSearchEnabled == true
   → sListener.onCustomRequest(CUSTOM_CODE_EMOJI_SEARCH)
3. LatinIME.onCustomRequest(CUSTOM_CODE_EMOJI_SEARCH)
   → !mEmojiSearchActive guard
   → showEmojiSearch()
       → mEmojiSearchActive = true
       → PointerTracker.setEmojiSearchActive(true)
       → EmojiSearchView.setVisibility(VISIBLE)
       → EmojiSearchView.show()
```

### Emoji Search from Panel

```
1. User taps search button in EmojiPanelView
2. LatinIME.showEmojiSearchFromPanel()
   → hideEmojiPanel()       ← must hide panel first (it covers search)
   → showEmojiSearch()
```

---

## Key Design Patterns

### Singleton

`Settings`, `KeyboardSwitcher`, `RichInputMethodManager` all use singleton pattern with `getInstance()` / `init(Context)`.

### State Machine

`KeyboardState.java` is a formal state machine for keyboard mode transitions. `KeyboardSwitcher` implements `KeyboardState.SwitchActions` and receives commands like `setAlphabetKeyboard()`, `setSymbolsKeyboard()`, `setShiftLocked()`.

### Observer / Listener

- `KeyboardActionListener` — interface implemented by `LatinIME`, called by `PointerTracker`
- `SharedPreferences.OnSharedPreferenceChangeListener` — `Settings` listens for preference changes
- `BroadcastReceiver` — `Settings` listens for MDM restriction changes

### Object Pool

`PointerTracker` maintains a sparse array of tracker instances keyed by pointer ID. Instances are reused across touch events.

### Strategy

Emoji rendering uses two strategies behind the `EmojiLoader` interface: `EmojiLazyLoader` (light mode) and the smooth pre-renderer. The strategy is selected at init time based on `Settings.readEmojiSmoothRendering()`.

### Double Buffering

`KeyboardView` maintains an offscreen `Bitmap` + `Canvas`. Only invalidated keys are redrawn; the rest is blitted from the buffer. This prevents full keyboard redraws on every key press.

### Handler / Message Passing

`LatinIME.UIHandler` defers non-urgent UI updates (shift state recalculation, memory deallocation) to avoid doing them synchronously in the hot path of key input.

---

## Thread Model

| Thread | Responsibilities |
|---|---|
| Main (UI) thread | All view rendering, touch events, `LatinIME` callbacks, `InputLogic`, `RichInputConnection` |
| `UIHandler` (Looper on main thread) | Deferred UI tasks: shift state updates, memory cleanup |
| Emoji preload thread | Smooth mode: pre-renders all category bitmaps on startup |
| `EmojiLazyLoader` executor | Light mode: renders individual emoji bitmaps on demand |
| Emoji search thread | `EmojiPanelView.searchLazy()` runs on background thread, posts chunks to UI |

All callbacks from background threads that touch views must post to the main thread. `EmojiLazyLoader`'s `mInvalidateCallback` and search chunk callbacks follow this rule.

---

## Constants and Key Codes

**File:** `latin/common/Constants.java`

Key codes use negative integers for special keys to avoid colliding with Unicode character values.

| Constant | Value | Meaning |
|---|---|---|
| `CODE_SHIFT` | -1 | Shift key |
| `CODE_CAPSLOCK` | -2 | Caps lock |
| `CODE_SWITCH_ALPHA_SYMBOL` | -3 | Toggle alphabet/symbols |
| `CODE_DELETE` | -5 | Backspace |
| `CODE_SETTINGS` | -6 | Open settings |
| `CODE_PASTE` | -7 | Paste clipboard |
| `CODE_ACTION_NEXT` | -8 | Next field |
| `CODE_ACTION_PREVIOUS` | -9 | Previous field |
| `CODE_LANGUAGE_SWITCH` | -10 | Switch subtype/language |
| `CODE_SHIFT_ENTER` | -11 | Shift + Enter |
| `CODE_SYMBOL_SHIFT` | -12 | Symbols shift |
| `CODE_EMOJI` | -13 | Open emoji panel |
| `CODE_EMOJI_SEARCH` | -17 | Open emoji search |
| `CUSTOM_CODE_EMOJI_SEARCH` | 2 | Custom request: long-press → search |

---

## ProGuard / R8 Considerations

`proguard-rules.pro` contains rules critical to the release build:

```proguard
# Keep R inner classes so resource IDs accessed at runtime are not stripped
-keep class uk.coko.forge.kokey.R$* { *; }

# LocaleResourceUtils uses R.class.getPackage().getName() to look up resources
# by name at runtime via getIdentifier(). If R8 renames the package,
# getIdentifier() returns 0 and the app crashes with Resources$NotFoundException.
-keeppackagenames uk.coko.forge.kokey.**

# Settings fragments referenced by name in preference XML
-keep class uk.coko.forge.kokey.latin.settings.SettingsFragment
-keep class uk.coko.forge.kokey.latin.settings.LanguagesSettingsFragment
-keep class uk.coko.forge.kokey.latin.settings.SingleLanguageSettingsFragment
```

Without `-keeppackagenames`, R8 renames `uk.coko.forge.kokey` to a short obfuscated name. `LocaleResourceUtils.getLocaleResourceId()` calls `R.class.getPackage().getName()` to get the package name for `getIdentifier()` — if that name is wrong, all locale resource lookups return 0 and the app crashes.
