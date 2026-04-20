package uk.coko.forge.kokey.emoji;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import uk.coko.forge.kokey.R;

import uk.coko.forge.kokey.keyboard.Key;
import uk.coko.forge.kokey.keyboard.Keyboard;
import uk.coko.forge.kokey.keyboard.MainKeyboardView;
import uk.coko.forge.kokey.latin.common.Constants;

/**
 * Transparent overlay drawn on top of MainKeyboardView during emoji search.
 * Dims keys that have no effect in search mode, leaving active ones at full opacity.
 */
final class EmojiSearchKeyDimOverlay extends View {

    private static final int DIM_ALPHA = 160; // 0–255

    private static final int[] PASS_THROUGH_CODES = {
            //Constants.CODE_DELETE,
            //Constants.CODE_ENTER,
            Constants.CODE_SHIFT_ENTER,
            //Constants.CODE_EMOJI,
            //Constants.CODE_SWITCH_ALPHA_SYMBOL,
            Constants.CODE_SYMBOL_SHIFT,
            //Constants.CODE_LANGUAGE_SWITCH,
            Constants.CODE_EMOJI_SEARCH,
            //Constants.CODE_SPACE,
            Constants.CODE_SHIFT,
            Constants.CODE_CAPSLOCK,
            Constants.CODE_SETTINGS,
            Constants.CODE_PASTE,
            Constants.CODE_ACTION_NEXT,
            Constants.CODE_ACTION_PREVIOUS,
            Constants.CODE_LEFT,
            Constants.CODE_RIGHT,
            Constants.CODE_TAB,
            ',', '_', '/', '.',
            '@', '#', '$', '%', '&', '-', '+', '(', ')', '*', '"', '\'', ';', ':', '!', '?',

    };

    private final Paint mDimPaint = new Paint();
    private MainKeyboardView mKeyboardView;

    EmojiSearchKeyDimOverlay(final Context context) {
        super(context);
        final android.content.res.TypedArray ta =
                context.obtainStyledAttributes(new int[]{ R.attr.keyboardViewBackground });
        mDimPaint.setColor(ta.getColor(0, Color.BLACK));
        ta.recycle();
        mDimPaint.setAlpha(DIM_ALPHA);
        setWillNotDraw(false);
    }

    void setKeyboard(final MainKeyboardView keyboardView) {
        mKeyboardView = keyboardView;
        keyboardView.getViewTreeObserver().addOnPreDrawListener(() -> {
            invalidate();
            return true;
        });
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (mKeyboardView == null) return;
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) return;
        final int paddingLeft = mKeyboardView.getPaddingLeft();
        final int paddingTop  = mKeyboardView.getPaddingTop();
        for (final Key key : keyboard.getSortedKeys()) {
            if (isPassThrough(key.getCode())) {
                final float left = key.getX() + paddingLeft;
                final float top  = key.getY() + paddingTop;
                canvas.drawRect(left, top, left + key.getWidth(), top + key.getHeight(), mDimPaint);
            }
        }
    }

    // Consume touches on dimmed keys (blocked) and forward pass-through key touches directly
    // to MainKeyboardView, since returning false sends to parent instead of the view below.
    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        final Keyboard keyboard = mKeyboardView == null ? null : mKeyboardView.getKeyboard();
        if (keyboard == null) return false;
        final float x = event.getX(), y = event.getY();
        final int pl = mKeyboardView.getPaddingLeft(), pt = mKeyboardView.getPaddingTop();
        for (final Key key : keyboard.getSortedKeys()) {
            final float l = key.getX() + pl, t = key.getY() + pt;
            if (x >= l && x <= l + key.getWidth() && y >= t && y <= t + key.getHeight()) {
                if (!isPassThrough(key.getCode())) {
                    return mKeyboardView.dispatchTouchEvent(event);
                }
                return true; // block dimmed key
            }
        }
        return false;
    }

    private static boolean isPassThrough(final int code) {
        for (final int c : PASS_THROUGH_CODES) {
            if (c == code) return true;
        }
        return false;
    }
}
