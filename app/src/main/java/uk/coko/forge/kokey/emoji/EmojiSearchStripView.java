package uk.coko.forge.kokey.emoji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * A single-row horizontal strip that shows emoji search results.
 * Renders pre-rasterized bitmaps via hardware layer for smooth scrolling.
 * Appears above the keyboard when search mode is active.
 */
public final class EmojiSearchStripView extends View {

    public interface OnEmojiClickListener {
        void onEmojiClick(String emoji);
    }

    private List<String>  mEmojis  = new ArrayList<>();
    private List<Bitmap>  mBitmaps = new ArrayList<>();
    private int           mCellSize = 1;
    private int           mScrollX  = 0;
    private int           mTotalWidth = 0;
    private volatile int  mGeneration = 0;

    private final OverScroller  mScroller;
    private VelocityTracker     mVelocityTracker;
    private final int           mTouchSlop;
    private final int           mMinFlingVelocity;
    private final int           mMaxFlingVelocity;
    private final Paint         mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private float mDownX, mDownY, mLastX;
    private boolean mIsScrolling = false;
    private OnEmojiClickListener mListener;

    public EmojiSearchStripView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mScroller = new OverScroller(context);
        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop        = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
    }

    public void setup(final int cellSize, final OnEmojiClickListener listener) {
        mCellSize = cellSize;
        mListener = listener;
    }

    /**
     * Appends a chunk of emojis to the strip.
     * Must be called from a background thread — renders bitmaps inline,
     * then posts the UI update. Discards the chunk if the generation changed.
     */
    public void appendChunk(final List<String> emojis) {
        final int gen = mGeneration;
        final int cellSize = mCellSize;
        final List<Bitmap> bitmaps = new ArrayList<>(emojis.size());
        for (final String e : emojis) {
            if (gen != mGeneration) return;
            bitmaps.add(EmojiRenderer.render(e, cellSize));
        }
        post(() -> {
            if (gen != mGeneration) return;
            mEmojis.addAll(emojis);
            mBitmaps.addAll(bitmaps);
            mTotalWidth = mEmojis.size() * cellSize;
            invalidate();
        });
    }

    public void clearResults() {
        mGeneration++;
        final List<Bitmap> old = mBitmaps;
        mEmojis     = new ArrayList<>();
        mBitmaps    = new ArrayList<>();
        mScrollX    = 0;
        mTotalWidth = 0;
        invalidate();
        for (final Bitmap bmp : old) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }

    public int getGeneration() { return mGeneration; }

    // ── drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(final Canvas canvas) {
        final int count = mBitmaps.size();
        if (count == 0) return;

        final int firstCol = mScrollX / mCellSize;
        final int lastCol  = Math.min(count - 1,
                (mScrollX + getWidth() + mCellSize - 1) / mCellSize);

        for (int col = firstCol; col <= lastCol; col++) {
            final Bitmap bmp = mBitmaps.get(col);
            if (bmp != null) {
                canvas.drawBitmap(bmp, col * mCellSize - mScrollX, 0, mPaint);
            }
        }
    }

    // ── touch ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (mVelocityTracker == null) mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mScroller.forceFinished(true);
                mDownX      = event.getX();
                mDownY      = event.getY();
                mLastX      = event.getX();
                mIsScrolling = false;
                setLayerType(LAYER_TYPE_HARDWARE, null);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!mIsScrolling && Math.abs(event.getX() - mDownX) > mTouchSlop) {
                    mIsScrolling = true;
                }
                if (mIsScrolling) {
                    final int maxScroll = Math.max(0, mTotalWidth - getWidth());
                    mScrollX = Math.max(0, Math.min(
                            mScrollX + (int)(mLastX - event.getX()), maxScroll));
                    invalidate();
                }
                mLastX = event.getX();
                return true;

            case MotionEvent.ACTION_UP:
                if (mIsScrolling) {
                    mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
                    final int vx = (int) mVelocityTracker.getXVelocity();
                    if (Math.abs(vx) > mMinFlingVelocity) {
                        mScroller.fling(mScrollX, 0, -vx, 0,
                                0, Math.max(0, mTotalWidth - getWidth()), 0, 0);
                        postInvalidateOnAnimation();
                    }
                } else {
                    // Tap — find which emoji was tapped
                    final int col = (mScrollX + (int) mDownX) / mCellSize;
                    if (col >= 0 && col < mEmojis.size() && mListener != null) {
                        mListener.onEmojiClick(mEmojis.get(col));
                    }
                }
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                setLayerType(LAYER_TYPE_NONE, null);
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mScrollX = mScroller.getCurrX();
            invalidate();
        } else {
            setLayerType(LAYER_TYPE_NONE, null);
        }
    }
}
