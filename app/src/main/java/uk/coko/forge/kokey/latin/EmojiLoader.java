package uk.coko.forge.kokey.latin;

import android.graphics.Bitmap;
import java.util.List;

interface EmojiLoader {
    void setEmojis(List<String> emojis, int cellSize);
    Bitmap get(int index);
    void recycle();
}
