package uk.coko.forge.kokey.emoji;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import uk.coko.forge.kokey.R;
import uk.coko.forge.kokey.keyboard.MainKeyboardView;
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
    private EmojiSearchKeyDimOverlay mDimOverlay;

    private final Handler mDeleteHandler = new Handler();
    private static final int DELETE_DELAY_INITIAL = 400;
    private static final int DELETE_DELAY_REPEAT  = 50;
    private final Runnable mDeleteRunnable = new Runnable() {
        @Override public void run() {
            onKey(uk.coko.forge.kokey.latin.common.Constants.CODE_DELETE);
            mDeleteHandler.postDelayed(this, DELETE_DELAY_REPEAT);
        }
    };

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
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    onKey(uk.coko.forge.kokey.latin.common.Constants.CODE_DELETE);
                    mDeleteHandler.postDelayed(mDeleteRunnable, DELETE_DELAY_INITIAL);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mDeleteHandler.removeCallbacks(mDeleteRunnable);
                    break;
            }
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
        attachDimOverlay();
    }

    public void close() {
        mActive = false;
        mStrip.clearResults();
        detachDimOverlay();
        mLatinIME.closeEmojiSearch();
    }

    private void attachDimOverlay() {
        post(() -> {
            if (!mActive) return;
            final MainKeyboardView kbView = mLatinIME.getMainKeyboardView();
            if (kbView == null || kbView.getKeyboard() == null) return;

            // InputView is the FrameLayout root — two levels up from this view
            final ViewGroup inputView = (ViewGroup) getParent().getParent();
            if (!(inputView instanceof FrameLayout)) return;

            final int[] kbPos   = new int[2]; kbView.getLocationInWindow(kbPos);
            final int[] rootPos = new int[2]; inputView.getLocationInWindow(rootPos);

            mDimOverlay = new EmojiSearchKeyDimOverlay(getContext());
            mDimOverlay.setKeyboard(kbView);

            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    kbView.getWidth(), kbView.getHeight());
            lp.leftMargin = kbPos[0] - rootPos[0];
            lp.topMargin  = kbPos[1] - rootPos[1];
            inputView.addView(mDimOverlay, lp);
        });
    }

    private void detachDimOverlay() {
        if (mDimOverlay != null && mDimOverlay.getParent() != null) {
            ((ViewGroup) mDimOverlay.getParent()).removeView(mDimOverlay);
        }
        mDimOverlay = null;
    }

    @Override
    protected void onVisibilityChanged(final View changedView, final int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) detachDimOverlay();
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
