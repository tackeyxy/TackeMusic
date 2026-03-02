package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.databinding.ItemRecentPlayBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentPlayAdapter(
    private val onItemClick: (RecentPlay) -> Unit,
    private val onItemLongClick: (RecentPlay) -> Boolean
) : ListAdapter<RecentPlay, RecentPlayAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentPlayBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecentPlayBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(recentPlay: RecentPlay) {
            // 歌曲名和艺术家在一行展示
            binding.tvSongInfo.text = "${recentPlay.name} - ${recentPlay.artists}"
            binding.tvPlayTime.text = formatTime(recentPlay.playedAt)

            binding.root.setOnClickListener {
                onItemClick(recentPlay)
            }

            binding.root.setOnLongClickListener {
                onItemLongClick(recentPlay)
            }
        }

        private fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecentPlay>() {
        override fun areItemsTheSame(oldItem: RecentPlay, newItem: RecentPlay): Boolean {
            return oldItem.id == newItem.id && oldItem.playedAt == newItem.playedAt
        }

        override fun areContentsTheSame(oldItem: RecentPlay, newItem: RecentPlay): Boolean {
            return oldItem == newItem
        }
    }
}
