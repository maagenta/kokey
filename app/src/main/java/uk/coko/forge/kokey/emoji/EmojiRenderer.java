package uk.coko.forge.kokey.emoji;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

final class EmojiRenderer {
    private EmojiRenderer() {}

    static Bitmap render(final String emoji, final int size) {
        return render(emoji, size, true);
    }

    static Bitmap render(final String emoji, final int size, final boolean hardware) {
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
        if (hardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Bitmap hard = soft.copy(Bitmap.Config.HARDWARE, false);
            soft.recycle();
            return hard;
        }
        return soft;
    }
}
