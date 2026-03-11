package com.tacke.music.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.ui.LyricSettingsActivity

/**
 * 歌词列表适配器
 */
class LyricsAdapter(
    private val onLyricClick: (Int) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private var lyrics: List<String> = emptyList()
    private var currentPosition = -1

    fun submitList(newLyrics: List<String>) {
        lyrics = newLyrics
        notifyDataSetChanged()
    }

    fun setCurrentPosition(position: Int) {
        if (position == currentPosition) return
        val previousPosition = currentPosition
        currentPosition = position
        if (previousPosition >= 0 && previousPosition < lyrics.size) {
            notifyItemChanged(previousPosition)
        }
        if (position >= 0 && position < lyrics.size) {
            notifyItemChanged(position)
        }
    }

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLyricLine: TextView = itemView.findViewById(R.id.tvLyricLine)

        fun bind(lyric: String, position: Int) {
            tvLyricLine.text = lyric

            // 获取用户设置的歌词颜色
            val lyricColor = LyricSettingsActivity.getPlayerLyricColor(itemView.context)

            // 当前播放的歌词高亮显示（使用自定义颜色）
            if (position == currentPosition) {
                tvLyricLine.setTextColor(lyricColor)
                tvLyricLine.textSize = 22f
                tvLyricLine.alpha = 1.0f
                tvLyricLine.paint.isFakeBoldText = true
                // 添加轻微缩放效果
                itemView.scaleX = 1.08f
                itemView.scaleY = 1.08f
            } else {
                // 非当前歌词使用白色显示
                tvLyricLine.setTextColor(Color.WHITE)
                tvLyricLine.textSize = 16f
                tvLyricLine.paint.isFakeBoldText = false
                // 根据距离当前歌词的远近设置透明度
                val distance = kotlin.math.abs(position - currentPosition)
                tvLyricLine.alpha = when {
                    distance == 1 -> 0.6f
                    distance == 2 -> 0.4f
                    distance == 3 -> 0.25f
                    else -> 0.15f
                }
                // 恢复原始大小
                itemView.scaleX = 1.0f
                itemView.scaleY = 1.0f
            }

            itemView.setOnClickListener { onLyricClick(position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        holder.bind(lyrics[position], position)
    }

    override fun getItemCount(): Int = lyrics.size
}
