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

import java.util.Collections;
import java.util.List;

public final class EmojiGridView extends View {

    public interface OnEmojiClickListener {
        void onEmojiClick(String emoji);
    }

    private List<Bitmap> mBitmaps = null;
    private EmojiLoader mLoader = null;
    private List<String> mEmojis = Collections.emptyList();
    private int mColumns = 6;
    private int mCellSize = 1;
    private int mScrollY = 0;
    private int mTotalHeight = 0;

    private final OverScroller mScroller;
    private VelocityTracker mVelocityTracker;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private final int mMaxFlingVelocity;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private float mDownX, mDownY, mLastY;
    private boolean mIsScrolling = false;
    private OnEmojiClickListener mListener;

    public EmojiGridView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mScroller = new OverScroller(context);
        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
    }

    public void setup(final int cellSize, final int columns, final OnEmojiClickListener listener) {
        mCellSize = cellSize;
        mColumns = columns;
        mListener = listener;
    }

    public void setData(final List<String> emojis, final List<Bitmap> bitmaps) {
        mEmojis = emojis;
        mBitmaps = bitmaps;
        mLoader = null;
        mScrollY = 0;
        final int rows = (emojis.size() + mColumns - 1) / mColumns;
        mTotalHeight = rows * mCellSize;
        invalidate();
    }

    public void setData(final List<String> emojis, final EmojiLoader loader) {
        mEmojis = emojis;
        mBitmaps = null;
        mLoader = loader;
        loader.setEmojis(emojis, mCellSize);
        mScrollY = 0;
        final int rows = (emojis.size() + mColumns - 1) / mColumns;
        mTotalHeight = rows * mCellSize;
        invalidate();
    }

    public boolean hasData() {
        return !mEmojis.isEmpty();
    }

    public void scrollToTop() {
        mScrollY = 0;
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int count = mEmojis.size();
        if (count == 0) return;
        final int firstRow = mScrollY / mCellSize;
        final int lastRow = (mScrollY + getHeight()) / mCellSize;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = 0; col < mColumns; col++) {
                final int index = row * mColumns + col;
                if (index >= count) return;
                final Bitmap bmp = mBitmaps != null ? mBitmaps.get(index)
                        : mLoader != null ? mLoader.get(index) : null;
                if (bmp != null) {
                    canvas.drawBitmap(bmp, col * mCellSize, row * mCellSize - mScrollY, mPaint);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (mVelocityTracker == null) mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mScroller.forceFinished(true);
                mDownX = event.getX();
                mDownY = event.getY();
                mLastY = event.getY();
                mIsScrolling = false;
                setLayerType(LAYER_TYPE_HARDWARE, null);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!mIsScrolling && Math.abs(event.getY() - mDownY) > mTouchSlop) {
                    mIsScrolling = true;
                }
                if (mIsScrolling) {
                    final int maxScroll = Math.max(0, mTotalHeight - getHeight());
                    mScrollY = Math.max(0, Math.min(mScrollY + (int)(mLastY - event.getY()), maxScroll));
                    invalidate();
                }
                mLastY = event.getY();
                return true;

            case MotionEvent.ACTION_UP:
                if (mIsScrolling) {
                    mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
                    final int vy = (int) mVelocityTracker.getYVelocity();
                    if (Math.abs(vy) > mMinFlingVelocity) {
                        mScroller.fling(0, mScrollY, 0, -vy,
                                0, 0, 0, Math.max(0, mTotalHeight - getHeight()));
                        postInvalidateOnAnimation();
                    }
                } else {
                    final int col = (int)(mDownX / mCellSize);
                    final int row = (mScrollY + (int) mDownY) / mCellSize;
                    final int index = row * mColumns + col;
                    if (index >= 0 && index < mEmojis.size() && mListener != null) {
                        mListener.onEmojiClick(mEmojis.get(index));
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
            mScrollY = mScroller.getCurrY();
            invalidate();
        } else {
            setLayerType(LAYER_TYPE_NONE, null);
        }
    }
}
