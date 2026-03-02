package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.api.PlaylistTag

class PlaylistTagAdapter(
    private val onTagClick: (PlaylistTag) -> Unit
) : RecyclerView.Adapter<PlaylistTagAdapter.TagViewHolder>() {

    private var tags: List<PlaylistTag> = emptyList()
    private var selectedPosition = 0

    fun submitList(newTags: List<PlaylistTag>) {
        tags = newTags
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        val previousPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(previousPosition)
        notifyItemChanged(selectedPosition)
    }

    fun getSelectedTag(): PlaylistTag? {
        return if (tags.isNotEmpty() && selectedPosition < tags.size) {
            tags[selectedPosition]
        } else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_tag, parent, false)
        return TagViewHolder(view)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        holder.bind(tags[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = tags.size

    inner class TagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: CardView = itemView.findViewById(R.id.cardTag)
        private val tvTagName: TextView = itemView.findViewById(R.id.tvTagName)

        fun bind(tag: PlaylistTag, isSelected: Boolean) {
            tvTagName.text = tag.name

            if (isSelected) {
                cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.primary))
                tvTagName.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
            } else {
                cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.surface))
                tvTagName.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
            }

            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    setSelectedPosition(adapterPosition)
                    onTagClick(tag)
                }
            }
        }
    }
}
