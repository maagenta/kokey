package uk.coko.forge.kokey.latin;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;


import org.xmlpull.v1.XmlPullParser;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

import uk.coko.forge.kokey.R;

public final class EmojiPanelView extends android.widget.FrameLayout {

    private int mBitmapSize = 48; // overridden in onFinishInflate with real pixel size

    private static final int API_EMOJI_11 = Build.VERSION_CODES.P;
    private static final int API_EMOJI_12 = Build.VERSION_CODES.Q;
    private static final int API_EMOJI_13 = Build.VERSION_CODES.R;
    private static final int API_EMOJI_14 = 32;
    private static final int API_EMOJI_15 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

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
    private List<List<Bitmap>> mCategoryBitmaps;
    private int mCurrentCategory = 0;

    private LatinIME mLatinIME;

    public EmojiPanelView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setLatinIME(final LatinIME latinIME) {
        mLatinIME = latinIME;
        // Start pre-rendering as soon as the keyboard is created
        preload();
    }

    private void preload() {
        if (mCategoryEmojis != null) return;
        final int bitmapSize = mBitmapSize;
        new Thread(() -> {
            final List<List<String>> categoryEmojis = new ArrayList<>();
            final List<List<Bitmap>> categoryBitmaps = new ArrayList<>();
            for (final String category : CATEGORIES) {
                final List<String> emojis = loadCategory(category);
                final List<Bitmap> bitmaps = new ArrayList<>(emojis.size());
                for (final String emoji : emojis) {
                    bitmaps.add(renderEmoji(emoji, bitmapSize));
                }
                categoryEmojis.add(emojis);
                categoryBitmaps.add(bitmaps);
            }
            post(() -> {
                mCategoryEmojis = categoryEmojis;
                mCategoryBitmaps = categoryBitmaps;
            });
        }).start();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        LayoutInflater.from(getContext()).inflate(R.layout.emoji_panel, this, true);

        mEmojiGridView = findViewById(R.id.emoji_grid_view);
        mTabContainer = findViewById(R.id.emoji_tab_container);

        final int cellSize = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        mBitmapSize = cellSize;
        final int columns = Math.max(getResources().getDisplayMetrics().widthPixels / cellSize, 6);
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
            // Already loaded — show immediately
            buildTabs();
            showCategory(0);
        } else {
            // Still loading — wait and retry
            post(this::initialize);
        }
    }

    private void buildTabs() {
        if (mTabContainer.getChildCount() > 0) return; // already built
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            final android.widget.ImageButton btn = new android.widget.ImageButton(getContext());
            btn.setImageResource(CATEGORY_ICONS[i]);
            btn.setBackground(null);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            final int size = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.MATCH_PARENT);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> showCategory(index));
            mTabContainer.addView(btn);
        }
    }

    private void showCategory(final int index) {
        mCurrentCategory = index;
        mEmojiGridView.setData(mCategoryEmojis.get(index), mCategoryBitmaps.get(index));
        mEmojiGridView.scrollToTop();
        for (int i = 0; i < mTabContainer.getChildCount(); i++) {
            mTabContainer.getChildAt(i).setAlpha(i == index ? 1.0f : 0.4f);
        }
    }

    private static Bitmap renderEmoji(final String emoji, final int size) {
        final TextPaint paint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size * 0.75f);
        final StaticLayout layout = StaticLayout.Builder
                .obtain(emoji, 0, emoji.length(), paint, size)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(1)
                .build();
        final Bitmap soft = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(soft);
        canvas.translate(0, (size - layout.getHeight()) / 2f);
        layout.draw(canvas);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Bitmap hard = soft.copy(Bitmap.Config.HARDWARE, false);
            soft.recycle();
            return hard;
        }
        return soft;
    }

    private List<String> loadCategory(final String category) {
        final List<String> result = new ArrayList<>();
        final int sdk = Build.VERSION.SDK_INT;
        loadFromXml(result, getResourceId("emojis_v3_" + category));
        if (sdk >= API_EMOJI_11) loadFromXml(result, getResourceId("emojis_v11_" + category));
        if (sdk >= API_EMOJI_12) loadFromXml(result, getResourceId("emojis_v12_" + category));
        if (sdk >= API_EMOJI_13) loadFromXml(result, getResourceId("emojis_v13_" + category));
        if (sdk >= API_EMOJI_14) loadFromXml(result, getResourceId("emojis_v14_" + category));
        if (sdk >= API_EMOJI_15) loadFromXml(result, getResourceId("emojis_v15_" + category));
        return result;
    }

    private int getResourceId(final String name) {
        return getResources().getIdentifier(name, "xml", getContext().getPackageName());
    }

    private void loadFromXml(final List<String> out, final int resId) {
        if (resId == 0) return;
        try (XmlResourceParser parser = getResources().getXml(resId)) {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.TEXT) {
                    splitEmojis(parser.getText(), out);
                }
                event = parser.next();
            }
        } catch (Exception e) {
            // skip malformed files
        }
    }

    private static void splitEmojis(final String text, final List<String> out) {
        if (text == null || text.isEmpty()) return;
        final BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            final String cluster = text.substring(start, end);
            if (!cluster.trim().isEmpty()) {
                out.add(cluster);
            }
        }
    }
}
