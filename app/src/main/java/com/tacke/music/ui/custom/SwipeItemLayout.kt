package com.tacke.music.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * 简化的列表项布局，仅支持点击监听
 * 左滑删除功能已移除，改用多选模式下的批量操作
 */
class SwipeItemLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var contentView: View? = null
    var onClickListener: (() -> Unit)? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (childCount >= 1) {
            contentView = getChildAt(0)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                onClickListener?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
