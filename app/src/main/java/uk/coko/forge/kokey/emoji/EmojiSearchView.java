package uk.coko.forge.kokey.emoji;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import uk.coko.forge.kokey.R;
import uk.coko.forge.kokey.latin.LatinIME;

/**
 * Self-contained emoji search UI: input bar + results strip.
 * Manages its own query state, key routing, and lazy search.
 */
public final class EmojiSearchView extends LinearLayout {

    private android.widget.EditText mSearchField;
    private EmojiSearchStripView mStrip;
    private EmojiPanelView mEmojiPanelView;
    private LatinIME mLatinIME;

    private final StringBuilder mQuery = new StringBuilder();
    private boolean mActive = false;

    public EmojiSearchView(final Context context) {
        super(context);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.emoji_search_view, this, true);
    }

    public EmojiSearchView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.emoji_search_view, this, true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSearchField = findViewById(R.id.emoji_search_field);
        mStrip       = findViewById(R.id.emoji_search_strip);

        findViewById(R.id.emoji_search_close).setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) close();
            return true;
        });

        findViewById(R.id.emoji_search_delete).setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP)
                onKey(uk.coko.forge.kokey.latin.common.Constants.CODE_DELETE);
            return true;
        });
    }

    public void setup(final LatinIME latinIME, final EmojiPanelView panelView, final int cellSize) {
        mLatinIME      = latinIME;
        mEmojiPanelView = panelView;
        mStrip.setup(cellSize, emoji -> mLatinIME.onTextInput(emoji));

        final android.view.ViewGroup.LayoutParams lp = mStrip.getLayoutParams();
        lp.height = cellSize;
        mStrip.setLayoutParams(lp);
    }

    public void show() {
        mQuery.setLength(0);
        mSearchField.setText("");
        mStrip.clearResults();
        mActive = true;
    }

    public void close() {
        mActive = false;
        mStrip.clearResults();
        mLatinIME.closeEmojiSearch();
    }

    public boolean isActive() { return mActive; }

    /** Routes a key press to the search query and triggers a lazy search. */
    public void onKey(final int codePoint) {
        if (codePoint == uk.coko.forge.kokey.latin.common.Constants.CODE_DELETE) {
            if (mQuery.length() > 0) mQuery.deleteCharAt(mQuery.length() - 1);
        } else if (Character.isLetterOrDigit(codePoint) || codePoint == ':') {
            mQuery.appendCodePoint(codePoint);
        } else if (codePoint == ' ') {
            if (mQuery.length() > 0 && mQuery.charAt(mQuery.length() - 1) != ' ') {
                mQuery.appendCodePoint(codePoint);
            }
        }
        final String query = mQuery.toString().trim();
        mSearchField.setText(query);
        mStrip.clearResults();
        if (!query.isEmpty()) {
            final int gen = mStrip.getGeneration();
            mEmojiPanelView.searchLazy(query, 15,
                    () -> mStrip.getGeneration() != gen,
                    chunk -> mStrip.appendChunk(chunk));
        }
    }
}
