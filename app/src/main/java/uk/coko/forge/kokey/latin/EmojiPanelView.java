package uk.coko.forge.kokey.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import uk.coko.forge.kokey.compat.PreferenceManagerCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import uk.coko.forge.kokey.R;
import uk.coko.forge.kokey.latin.settings.Settings;

public final class EmojiPanelView extends android.widget.FrameLayout {

    private int mBitmapSize = 48; // overridden in onFinishInflate with real pixel size

    private static final String TAG = "EmojiPanelView";

    private static final byte[] BIN_MAGIC   = {'E', 'M', 'J', 'I'};
    private static final int    BIN_VERSION = 2;

    // Emoji version bucket → minimum Android API level
    private static final int[] VERSION_BUCKETS = { 3,  11,  12,  13,  14,  15 };
    private static final int[] VERSION_MIN_API = {
            1,                                   // v3  → all devices
            Build.VERSION_CODES.P,               // v11 → Android 9
            Build.VERSION_CODES.Q,               // v12 → Android 10
            Build.VERSION_CODES.R,               // v13 → Android 11
            32,                                  // v14 → Android 12L
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE // v15 → Android 14
    };

    // Loaded once from cldr_en.bin, shared across all loadCategory() calls
    private EmojiData mEmojiData;

    private static final String[] CATEGORIES = {
            "smileys", "people", "animals", "nature",
            "food", "travel", "activities", "objects", "symbols", "flags"
    };

    private static final int[] CATEGORY_ICONS = {
            R.drawable.ic_emoji_cat_smileys,
            R.drawable.ic_emoji_cat_people,
            R.drawable.ic_emoji_cat_animals,
            R.drawable.ic_emoji_cat_nature,
            R.drawable.ic_emoji_cat_food,
            R.drawable.ic_emoji_cat_travel,
            R.drawable.ic_emoji_cat_activities,
            R.drawable.ic_emoji_cat_objects,
            R.drawable.ic_emoji_cat_symbols,
            R.drawable.ic_emoji_cat_flags,
    };

    private EmojiGridView mEmojiGridView;
    private LinearLayout mTabContainer;

    private List<List<String>> mCategoryEmojis;
    private int mCurrentCategory = 0;
    // Smooth mode: pre-rendered bitmaps per category
    private List<List<Bitmap>> mCategoryBitmaps;
    // Light mode: lazy loaders per category
    private List<EmojiLazyLoader> mCategoryLoaders;

    private LatinIME mLatinIME;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            (prefs, key) -> {
                if (Settings.PREF_EMOJI_RENDERING.equals(key)) {
                    recycleCache();
                    preload();
                }
            };

    public EmojiPanelView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setLatinIME(final LatinIME latinIME) {
        mLatinIME = latinIME;
        preload();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PreferenceManagerCompat.getDeviceSharedPreferences(getContext())
                .registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        PreferenceManagerCompat.getDeviceSharedPreferences(getContext())
                .unregisterOnSharedPreferenceChangeListener(mPrefListener);
        super.onDetachedFromWindow();
    }

    private void recycleCache() {
        if (mCategoryBitmaps != null) {
            for (final List<Bitmap> bitmaps : mCategoryBitmaps) {
                for (final Bitmap b : bitmaps) {
                    if (b != null && !b.isRecycled()) b.recycle();
                }
            }
            mCategoryBitmaps = null;
        }
        if (mCategoryLoaders != null) {
            for (final EmojiLazyLoader loader : mCategoryLoaders) {
                loader.recycle();
            }
            mCategoryLoaders = null;
        }
        mCategoryEmojis = null;
    }

    private void preload() {
        if (mCategoryEmojis != null) return;

        final boolean smooth = Settings.getInstance().getCurrent().mEmojiSmoothRendering;
        final int bitmapSize = mBitmapSize;

        if (smooth) {
            new Thread(() -> {
                final List<List<String>> categoryEmojis = new ArrayList<>();
                final List<List<Bitmap>> categoryBitmaps = new ArrayList<>();
                for (final String category : CATEGORIES) {
                    final List<String> emojis = loadCategory(category);
                    final List<Bitmap> bitmaps = new ArrayList<>(emojis.size());
                    for (final String emoji : emojis) {
                        bitmaps.add(EmojiRenderer.render(emoji, bitmapSize));
                    }
                    categoryEmojis.add(emojis);
                    categoryBitmaps.add(bitmaps);
                }
                post(() -> {
                    mCategoryEmojis = categoryEmojis;
                    mCategoryBitmaps = categoryBitmaps;
                });
            }).start();
        } else {
            new Thread(() -> {
                final List<List<String>> categoryEmojis = new ArrayList<>();
                final List<EmojiLazyLoader> categoryLoaders = new ArrayList<>();
                for (final String category : CATEGORIES) {
                    categoryEmojis.add(loadCategory(category));
                    categoryLoaders.add(new EmojiLazyLoader(mEmojiGridView::postInvalidate));
                }
                post(() -> {
                    mCategoryEmojis = categoryEmojis;
                    mCategoryLoaders = categoryLoaders;
                });
            }).start();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        LayoutInflater.from(getContext()).inflate(R.layout.emoji_panel, this, true);

        mEmojiGridView = findViewById(R.id.emoji_grid_view);
        mTabContainer = findViewById(R.id.emoji_tab_container);

        final int minCellPx = (int)(36 * getResources().getDisplayMetrics().density);
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int columns = Math.max(screenWidth / minCellPx, 6);
        final int cellSize = screenWidth / columns;
        mBitmapSize = cellSize;
        mEmojiGridView.setup(cellSize, columns, emoji -> {
            if (mLatinIME != null) mLatinIME.onTextInput(emoji);
        });

        findViewById(R.id.emoji_keyboard_btn).setOnClickListener(v -> {
            if (mLatinIME != null) mLatinIME.toggleEmojiPanel();
        });

        findViewById(R.id.emoji_backspace_btn).setOnClickListener(v -> {
            if (mLatinIME != null) mLatinIME.onCodeInput(
                    uk.coko.forge.kokey.latin.common.Constants.CODE_DELETE,
                    0, 0, false);
        });
    }

    public void initialize() {
        if (mCategoryEmojis != null) {
            buildTabs();
            showCategory(0);
        } else {
            post(this::initialize);
        }
    }

    private void buildTabs() {
        if (mTabContainer.getChildCount() > 0) return;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            final android.widget.ImageButton btn = new android.widget.ImageButton(getContext());
            btn.setImageResource(CATEGORY_ICONS[i]);
            btn.setBackground(null);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> showCategory(index));
            mTabContainer.addView(btn);
        }
    }

    private void showCategory(final int index) {
        if (index == mCurrentCategory && mEmojiGridView.hasData()) return;
        mCurrentCategory = index;
        if (mCategoryBitmaps != null) {
            mEmojiGridView.setData(mCategoryEmojis.get(index), mCategoryBitmaps.get(index));
        } else {
            mEmojiGridView.setData(mCategoryEmojis.get(index), mCategoryLoaders.get(index));
        }
        mEmojiGridView.scrollToTop();
        for (int i = 0; i < mTabContainer.getChildCount(); i++) {
            mTabContainer.getChildAt(i).setAlpha(i == index ? 1.0f : 0.4f);
        }
    }

    private List<String> loadCategory(final String category) {
        if (mEmojiData == null) mEmojiData = loadBin();
        final int sdk = Build.VERSION.SDK_INT;
        final List<String> result = new ArrayList<>();
        for (int i = 0; i < mEmojiData.count; i++) {
            if (!category.equals(mEmojiData.categories[mEmojiData.catIndex[i]])) continue;
            if (sdk < minApiForVersion(mEmojiData.version[i])) continue;
            result.add(mEmojiData.emoji[i]);
        }
        return result;
    }

    private int minApiForVersion(final int versionBucket) {
        for (int i = 0; i < VERSION_BUCKETS.length; i++) {
            if (VERSION_BUCKETS[i] == versionBucket) return VERSION_MIN_API[i];
        }
        return 1;
    }

    private EmojiData loadBin() {
        final int resId = getResources().getIdentifier(
                "cldr_en", "raw", getContext().getPackageName());
        if (resId == 0) {
            Log.e(TAG, "cldr_en.bin not found in res/raw");
            return new EmojiData(new String[0], new String[0], new int[0], new int[0], 0);
        }
        try (InputStream in = getResources().openRawResource(resId)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return parseBin(ByteBuffer.wrap(buf.toByteArray()));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cldr_en.bin", e);
            return new EmojiData(new String[0], new String[0], new int[0], new int[0], 0);
        }
    }

    private static EmojiData parseBin(final ByteBuffer buf) {
        // Verify magic
        for (final byte b : BIN_MAGIC) {
            if (buf.get() != b) { Log.e(TAG, "Bad magic"); return null; }
        }
        if ((buf.get() & 0xFF) != BIN_VERSION) { Log.e(TAG, "Unsupported version"); return null; }

        // Categories
        final int numCats = buf.get() & 0xFF;
        final String[] categories = new String[numCats];
        for (int i = 0; i < numCats; i++) {
            final byte[] b = new byte[buf.get() & 0xFF];
            buf.get(b);
            categories[i] = new String(b, StandardCharsets.UTF_8);
        }

        // Emojis
        final int count = buf.getShort() & 0xFFFF;
        final String[] emoji    = new String[count];
        final int[]    version  = new int[count];
        final int[]    catIndex = new int[count];

        for (int i = 0; i < count; i++) {
            final byte[] eb = new byte[buf.get() & 0xFF];
            buf.get(eb);
            emoji[i]    = new String(eb, StandardCharsets.UTF_8);
            version[i]  = buf.get() & 0xFF;
            catIndex[i] = buf.get() & 0xFF;
            // Skip tags (search not implemented yet)
            final int numTags = buf.get() & 0xFF;
            for (int t = 0; t < numTags; t++) {
                final int tagLen = buf.get() & 0xFF;
                buf.position(buf.position() + tagLen);
            }
        }
        return new EmojiData(categories, emoji, version, catIndex, count);
    }

    private static final class EmojiData {
        final String[] categories;
        final String[] emoji;
        final int[]    version;
        final int[]    catIndex;
        final int      count;

        EmojiData(String[] categories, String[] emoji, int[] version, int[] catIndex, int count) {
            this.categories = categories;
            this.emoji      = emoji;
            this.version    = version;
            this.catIndex   = catIndex;
            this.count      = count;
        }
    }
}
