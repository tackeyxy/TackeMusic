package com.tacke.music.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-Edge 沉浸式状态栏帮助类
 * 统一处理所有 Activity 的沉浸式状态栏适配
 */
object EdgeToEdgeHelper {

    /**
     * 设置 Activity 的 Edge-to-Edge 沉浸式状态栏
     *
     * @param activity 当前 Activity
     * @param rootView 根视图
     * @param toolbarView 顶部工具栏视图（可选，会自动添加状态栏高度 padding）
     * @param bottomView 底部视图（可选，会自动添加导航栏高度 padding）
     */
    fun setupEdgeToEdge(
        activity: Activity,
        rootView: View,
        toolbarView: View? = null,
        bottomView: View? = null
    ) {
        // 启用 Edge-to-Edge 模式
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        // 设置状态栏和导航栏为透明
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 处理系统栏 insets
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )

            // 为根视图设置 padding，避免内容被系统栏遮挡
            view.updatePadding(
                top = insets.top,
                bottom = insets.bottom
            )

            windowInsets
        }

        // 为顶部工具栏设置状态栏高度 padding
        toolbarView?.let { toolbar ->
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                view.updatePadding(top = insets.top)
                windowInsets
            }
        }

        // 为底部视图设置导航栏高度 padding
        bottomView?.let { bottom ->
            ViewCompat.setOnApplyWindowInsetsListener(bottom) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                view.updatePadding(bottom = insets.bottom)
                windowInsets
            }
        }
    }

    /**
     * 仅为根视图设置 Edge-to-Edge（不处理工具栏和底部视图）
     */
    fun setupEdgeToEdgeSimple(activity: Activity, rootView: View) {
        setupEdgeToEdge(activity, rootView, null, null)
    }

    /**
     * 为指定视图设置状态栏高度 padding
     */
    fun setStatusBarPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = insets.top)
            windowInsets
        }
    }

    /**
     * 为指定视图设置导航栏高度 padding
     */
    fun setNavigationBarPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    /**
     * 为指定视图设置刘海屏左右 padding
     */
    fun setDisplayCutoutPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(
                left = insets.left,
                right = insets.right
            )
            windowInsets
        }
    }
}
