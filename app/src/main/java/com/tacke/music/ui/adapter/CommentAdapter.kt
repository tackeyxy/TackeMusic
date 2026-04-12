package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.tacke.music.R
import com.tacke.music.data.model.Comment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 评论适配器 - 支持显示加载更多状态
 */
class CommentAdapter : ListAdapter<CommentAdapter.ListItem, RecyclerView.ViewHolder>(CommentDiffCallback()) {

    companion object {
        private const val TYPE_COMMENT = 0
        private const val TYPE_LOADING = 1
    }

    private var isLoadingMore = false
    private var currentComments: List<Comment> = emptyList()

    /**
     * 列表项类型
     */
    sealed class ListItem {
        data class CommentItem(val comment: Comment) : ListItem()
        object LoadingItem : ListItem()
    }

    /**
     * 设置加载更多状态
     */
    fun setLoadingMore(loading: Boolean) {
        if (isLoadingMore == loading) return

        isLoadingMore = loading
        refreshList()
    }

    /**
     * 提交评论数据
     */
    fun submitComments(comments: List<Comment>) {
        currentComments = comments
        refreshList()
    }

    /**
     * 刷新列表
     */
    private fun refreshList() {
        val items = mutableListOf<ListItem>()
        items.addAll(currentComments.map { ListItem.CommentItem(it) })
        if (isLoadingMore) {
            items.add(ListItem.LoadingItem)
        }
        submitList(items.toList())
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ListItem.CommentItem -> TYPE_COMMENT
            is ListItem.LoadingItem -> TYPE_LOADING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_COMMENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_comment, parent, false)
                CommentViewHolder(view)
            }
            TYPE_LOADING -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_comment_loading, parent, false)
                LoadingViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CommentViewHolder -> {
                val item = getItem(position) as ListItem.CommentItem
                holder.bind(item.comment)
            }
            is LoadingViewHolder -> {
                // 加载项不需要绑定数据
            }
        }
    }

    /**
     * 评论ViewHolder
     */
    class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvUserName: TextView = itemView.findViewById(R.id.tvUserName)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        fun bind(comment: Comment) {
            // 加载用户头像 - 使用缩略图优化
            val avatarUrl = comment.userAvatar
            if (!avatarUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(avatarUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_album_default)
                    .error(R.drawable.ic_album_default)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(100, 100) // 限制图片大小
                    .thumbnail(0.5f)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_album_default)
            }

            // 设置用户名
            tvUserName.text = comment.userName ?: "未知用户"

            // 设置评论内容
            tvContent.text = comment.content ?: ""

            // 设置时间
            tvTime.text = formatTime(comment.time)

            // 设置点赞数
            val likeCount = comment.likeCount ?: 0
            tvLikeCount.text = formatLikeCount(likeCount)
            tvLikeCount.visibility = if (likeCount > 0) View.VISIBLE else View.GONE
        }

        private fun formatTime(time: Any?): String {
            return when (time) {
                is Long -> {
                    try {
                        dateFormat.format(Date(time))
                    } catch (e: Exception) {
                        ""
                    }
                }
                is String -> time
                else -> ""
            }
        }

        private fun formatLikeCount(count: Int): String {
            return when {
                count >= 10000 -> String.format("%.1f万", count / 10000.0)
                count > 0 -> count.toString()
                else -> ""
            }
        }
    }

    /**
     * 加载更多ViewHolder
     */
    class LoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
    }

    /**
     * DiffCallback
     */
    class CommentDiffCallback : DiffUtil.ItemCallback<ListItem>() {
        override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return when {
                oldItem is ListItem.CommentItem && newItem is ListItem.CommentItem ->
                    oldItem.comment.id == newItem.comment.id
                oldItem is ListItem.LoadingItem && newItem is ListItem.LoadingItem -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return when {
                oldItem is ListItem.CommentItem && newItem is ListItem.CommentItem ->
                    oldItem.comment == newItem.comment
                oldItem is ListItem.LoadingItem && newItem is ListItem.LoadingItem -> true
                else -> false
            }
        }
    }
}
