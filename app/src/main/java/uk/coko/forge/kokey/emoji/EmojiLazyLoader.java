package uk.coko.forge.kokey.emoji;

import android.graphics.Bitmap;
import android.util.LruCache;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EmojiLazyLoader implements EmojiLoader {

    private static final int CACHE_SIZE = 80;

    private final LruCache<Integer, Bitmap> mCache = new LruCache<>(CACHE_SIZE);
    private final Set<Integer> mPending = ConcurrentHashMap.newKeySet();
    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Runnable mInvalidateCallback;

    private List<String> mEmojis;
    private int mCellSize;

    EmojiLazyLoader(final Runnable invalidateCallback) {
        mInvalidateCallback = invalidateCallback;
    }

    @Override
    public void setEmojis(final List<String> emojis, final int cellSize) {
        mEmojis = emojis;
        mCellSize = cellSize;
        mCache.evictAll();
        mPending.clear();
    }

    @Override
    public Bitmap get(final int index) {
        final Bitmap cached = mCache.get(index);
        if (cached != null) return cached;

        if (mExecutor.isShutdown()) {
            mExecutor = Executors.newSingleThreadExecutor();
        }
        if (mPending.add(index)) {
            final String emoji = mEmojis.get(index);
            final int size = mCellSize;
            mExecutor.submit(() -> {
                final Bitmap bmp = EmojiRenderer.render(emoji, size, false);
                mCache.put(index, bmp);
                mPending.remove(index);
                mInvalidateCallback.run();
            });
        }
        return null;
    }

    @Override
    public void recycle() {
        mExecutor.shutdownNow();
        mCache.evictAll();
        mPending.clear();
    }
}
