package com.tacke.music.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 歌词列表装饰器
 * 添加顶部和底部间距，使歌词可以滚动到中间位置
 */
class LyricsItemDecoration : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val itemCount = state.itemCount
        val parentHeight = parent.height
        
        // 计算中间偏移量（RecyclerView 高度的一半）
        val centerOffset = parentHeight / 2

        // 第一个 item 添加顶部间距，使其可以滚动到中间
        if (position == 0) {
            outRect.top = centerOffset - 60 // 减去歌词行高度的一半
        }

        // 最后一个 item 添加底部间距，使其可以滚动到中间
        if (position == itemCount - 1) {
            outRect.bottom = centerOffset - 60
        }
    }
}
