package rkr.simplekeyboard.inputmethod.latin;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public final class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder> {

    public interface OnEmojiClickListener {
        void onEmojiClick(String emoji);
    }

    private List<String> mEmojis;
    private final OnEmojiClickListener mListener;

    public EmojiAdapter(final List<String> emojis, final OnEmojiClickListener listener) {
        mEmojis = emojis;
        mListener = listener;
    }

    public void setEmojis(final List<String> emojis) {
        mEmojis = emojis;
        notifyDataSetChanged();
    }

    @Override
    public EmojiViewHolder onCreateViewHolder(final ViewGroup parent, final int viewType) {
        final TextView tv = new TextView(parent.getContext());
        final int size = parent.getContext().getResources()
                .getDimensionPixelSize(android.R.dimen.app_icon_size);
        tv.setLayoutParams(new RecyclerView.LayoutParams(size, size));
        tv.setTextSize(24f);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        return new EmojiViewHolder(tv);
    }

    @Override
    public void onBindViewHolder(final EmojiViewHolder holder, final int position) {
        final String emoji = mEmojis.get(position);
        holder.mTextView.setText(emoji);
        holder.mTextView.setOnClickListener(v -> {
            if (mListener != null) mListener.onEmojiClick(emoji);
        });
    }

    @Override
    public int getItemCount() {
        return mEmojis == null ? 0 : mEmojis.size();
    }

    static final class EmojiViewHolder extends RecyclerView.ViewHolder {
        final TextView mTextView;

        EmojiViewHolder(final TextView tv) {
            super(tv);
            mTextView = tv;
        }
    }
}
