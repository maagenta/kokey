package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.xmlpull.v1.XmlPullParser;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

import rkr.simplekeyboard.inputmethod.R;

public final class EmojiPanelView extends android.widget.FrameLayout {

    // Emoji version thresholds (Android API level → Emoji version)
    // Emoji 3  → API 24+ (Android 7.0, bundled via system font)
    // Emoji 11 → API 28+ (Android 9)
    // Emoji 12 → API 29+ (Android 10)
    // Emoji 13 → API 30+ (Android 11)
    // Emoji 14 → API 32+ (Android 12L)
    // Emoji 15 → API 34+ (Android 14)
    private static final int API_EMOJI_11 = Build.VERSION_CODES.P;      // 28
    private static final int API_EMOJI_12 = Build.VERSION_CODES.Q;      // 29
    private static final int API_EMOJI_13 = Build.VERSION_CODES.R;      // 30
    private static final int API_EMOJI_14 = 32;
    private static final int API_EMOJI_15 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE; // 34

    private static final String[] CATEGORIES = {
            "smileys", "people", "animals", "nature",
            "food", "travel", "activities", "objects", "symbols", "flags"
    };

    private static final String[] CATEGORY_ICONS = {
            "😀", "👋", "🐶", "🌿",
            "🍔", "✈️", "⚽", "💡", "🔣", "🏳️"
    };

    private RecyclerView mRecyclerView;
    private LinearLayout mTabContainer;
    private EmojiAdapter mAdapter;

    // Per-category emoji lists, built once on first show
    private List<List<String>> mCategoryEmojis;
    private int mCurrentCategory = 0;

    private LatinIME mLatinIME;

    public EmojiPanelView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setLatinIME(final LatinIME latinIME) {
        mLatinIME = latinIME;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        LayoutInflater.from(getContext()).inflate(R.layout.emoji_panel, this, true);

        mRecyclerView = findViewById(R.id.emoji_recycler_view);
        mTabContainer = findViewById(R.id.emoji_tab_container);

        final int columns = getResources().getDisplayMetrics().widthPixels
                / getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
        mRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), Math.max(columns, 6)));

        mAdapter = new EmojiAdapter(new ArrayList<>(), emoji -> {
            if (mLatinIME != null) mLatinIME.onTextInput(emoji);
        });
        mRecyclerView.setAdapter(mAdapter);
    }

    /** Called when the panel becomes visible — lazy-loads emoji data and builds tabs. */
    public void initialize() {
        if (mCategoryEmojis != null) return; // already loaded
        mCategoryEmojis = new ArrayList<>();
        for (final String category : CATEGORIES) {
            mCategoryEmojis.add(loadCategory(category));
        }
        buildTabs();
        showCategory(0);
    }

    private void buildTabs() {
        mTabContainer.removeAllViews();
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            final android.widget.Button btn = new android.widget.Button(getContext());
            btn.setText(CATEGORY_ICONS[i]);
            btn.setTextSize(18f);
            btn.setBackground(null);
            final int size = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.MATCH_PARENT);
            btn.setLayoutParams(lp);
            btn.setGravity(Gravity.CENTER);
            btn.setOnClickListener(v -> showCategory(index));
            mTabContainer.addView(btn);
        }
    }

    private void showCategory(final int index) {
        mCurrentCategory = index;
        if (mCategoryEmojis != null && index < mCategoryEmojis.size()) {
            mAdapter.setEmojis(mCategoryEmojis.get(index));
            mRecyclerView.scrollToPosition(0);
        }
        // Highlight selected tab
        for (int i = 0; i < mTabContainer.getChildCount(); i++) {
            mTabContainer.getChildAt(i).setAlpha(i == index ? 1.0f : 0.4f);
        }
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
