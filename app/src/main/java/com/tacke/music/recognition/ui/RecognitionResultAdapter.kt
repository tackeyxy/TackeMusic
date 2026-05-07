package com.tacke.music.recognition.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.Song

class RecognitionResultAdapter(
    private val onPlayClick: (Song) -> Unit,
    private val onFavoriteClick: (Song, Boolean) -> Unit,
    private val onDownloadClick: (Song) -> Unit,
    private val onAddToPlaylistClick: (Song) -> Unit
) : RecyclerView.Adapter<RecognitionResultAdapter.SongViewHolder>() {

    private var kuwoSong: Song? = null
    private var neteaseSong: Song? = null
    
    // 状态缓存
    private var kuwoFavoriteState: Boolean = false
    private var neteaseFavoriteState: Boolean = false

    fun setSongs(kuwo: Song?, netease: Song?) {
        kuwoSong = kuwo
        neteaseSong = netease
        // 重置状态
        kuwoFavoriteState = false
        neteaseFavoriteState = false
        notifyDataSetChanged()
    }
    
    fun updateFavoriteState(song: Song, isFavorite: Boolean) {
        when (song.platform.uppercase()) {
            "KUWO" -> kuwoFavoriteState = isFavorite
            "NETEASE" -> neteaseFavoriteState = isFavorite
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        var count = 0
        if (kuwoSong != null) count++
        if (neteaseSong != null) count++
        return count
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            position == 0 && kuwoSong != null -> TYPE_KUWO
            else -> TYPE_NETEASE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recognition_result, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = when (getItemViewType(position)) {
            TYPE_KUWO -> kuwoSong
            else -> neteaseSong
        }
        val isFavorite = when (getItemViewType(position)) {
            TYPE_KUWO -> kuwoFavoriteState
            else -> neteaseFavoriteState
        }
        song?.let { holder.bind(it, getItemViewType(position), isFavorite) }
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        private val btnFavorite: ImageButton = itemView.findViewById(R.id.btnFavorite)
        private val btnDownload: ImageButton = itemView.findViewById(R.id.btnDownload)
        private val btnAddToPlaylist: ImageButton = itemView.findViewById(R.id.btnAddToPlaylist)

        fun bind(song: Song, type: Int, isFavorite: Boolean) {
            // 设置音源图标
            val sourceIcon = when (type) {
                TYPE_KUWO -> R.drawable.ic_kuwo_logo
                else -> R.drawable.ic_netease_logo
            }
            ivSource.setImageResource(sourceIcon)

            // 设置歌曲信息
            tvSongName.text = song.name
            tvArtist.text = song.artists

            // 加载封面
            if (!song.coverUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(song.coverUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.ic_music_note)
            }

            // 设置收藏按钮状态
            updateFavoriteIcon(isFavorite)

            // 设置按钮点击事件
            btnPlay.setOnClickListener { onPlayClick(song) }
            btnFavorite.setOnClickListener { onFavoriteClick(song, isFavorite) }
            btnDownload.setOnClickListener { onDownloadClick(song) }
            btnAddToPlaylist.setOnClickListener { onAddToPlaylistClick(song) }
        }
        
        private fun updateFavoriteIcon(isFavorite: Boolean) {
            val iconRes = if (isFavorite) {
                R.drawable.ic_heart
            } else {
                R.drawable.ic_heart_outline
            }
            btnFavorite.setImageResource(iconRes)
            btnFavorite.setColorFilter(
                if (isFavorite) {
                    itemView.context.getColor(R.color.error)
                } else {
                    itemView.context.getColor(R.color.text_secondary)
                }
            )
        }
    }

    companion object {
        const val TYPE_KUWO = 0
        const val TYPE_NETEASE = 1
    }
}
