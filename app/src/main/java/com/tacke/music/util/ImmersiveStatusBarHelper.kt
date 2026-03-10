package com.tacke.music.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout

/**
 * 沉浸式状态栏帮助类
 * 提供统一的沉浸式状态栏实现方式，便于后续改造其他页面
 *
 * 使用示例：
 * 1. 基础沉浸式（内容延伸到状态栏）：
 *    ImmersiveStatusBarHelper.setup(this)
 *
 * 2. 带渐变背景的沉浸式：
 *    ImmersiveStatusBarHelper.setupWithGradientBackground(this, R.id.appBar)
 *
 * 3. 全屏沉浸式（隐藏状态栏）：
 *    ImmersiveStatusBarHelper.setupFullscreen(this)
 */
object ImmersiveStatusBarHelper {

    /**
     * 沉浸式状态栏模式
     */
    enum class Mode {
        /**
         * 基础模式：透明状态栏，内容延伸到状态栏下方
         */
        TRANSPARENT,

        /**
         * 渐变背景模式：适用于顶部有渐变背景的页面
         */
        GRADIENT_BACKGROUND,

        /**
         * 全屏模式：隐藏状态栏和导航栏
         */
        FULLSCREEN,

        /**
         * 仅透明状态栏：不改变导航栏
         */
        STATUS_BAR_ONLY
    }

    /**
     * 基础设置 - 透明状态栏和导航栏
     *
     * @param activity 当前Activity
     * @param lightStatusBar 状态栏图标是否为深色（浅色背景时用true）
     * @param lightNavigationBar 导航栏图标是否为深色
     */
    fun setup(
        activity: Activity,
        lightStatusBar: Boolean = true,
        lightNavigationBar: Boolean = true
    ) {
        val window = activity.window

        // 启用 Edge-to-Edge 模式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置状态栏和导航栏透明
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // 设置状态栏和导航栏图标颜色
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatusBar
            isAppearanceLightNavigationBars = lightNavigationBar
        }
    }

    /**
     * 设置沉浸式状态栏 - 带顶部渐变背景
     * 适用于顶部有渐变背景的页面（如更新页面）
     *
     * @param activity 当前Activity
     * @param headerViewId 顶部背景视图的ID
     * @param contentViewId 内容视图的ID（可选，用于设置底部padding）
     */
    fun setupWithGradientBackground(
        activity: Activity,
        headerViewId: Int? = null,
        contentViewId: Int? = null
    ) {
        val window = activity.window

        // 启用 Edge-to-Edge 模式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置状态栏和导航栏透明
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // 状态栏图标为白色（因为顶部是渐变背景）
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        // 处理顶部视图的padding
        headerViewId?.let { id ->
            val headerView = activity.findViewById<View>(id)
            headerView?.let { view ->
                ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                    v.updatePadding(top = insets.top)
                    windowInsets
                }
            }
        }

        // 处理内容视图的底部padding
        contentViewId?.let { id ->
            val contentView = activity.findViewById<View>(id)
            contentView?.let { view ->
                ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                    v.updatePadding(bottom = insets.bottom)
                    windowInsets
                }
            }
        }
    }

    /**
     * 设置全屏沉浸式（隐藏状态栏和导航栏）
     * 适用于播放器页面等需要全屏的场景
     *
     * @param activity 当前Activity
     */
    fun setupFullscreen(activity: Activity) {
        val window = activity.window

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置状态栏和导航栏透明
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // 隐藏状态栏和导航栏
        val decorView = window.decorView
        val flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decorView.systemUiVisibility = flags
    }

    /**
     * 仅设置状态栏透明（不改变导航栏）
     *
     * @param activity 当前Activity
     * @param lightStatusBar 状态栏图标是否为深色
     */
    fun setupStatusBarOnly(
        activity: Activity,
        lightStatusBar: Boolean = true
    ) {
        val window = activity.window

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatusBar
        }
    }

    /**
     * 为指定视图设置状态栏高度padding
     *
     * @param view 需要设置padding的视图
     */
    fun setStatusBarPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = insets.top)
            windowInsets
        }
    }

    /**
     * 为指定视图设置导航栏高度padding
     *
     * @param view 需要设置padding的视图
     */
    fun setNavigationBarPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    /**
     * 为指定视图设置系统栏padding（状态栏+导航栏）
     *
     * @param view 需要设置padding的视图
     * @param applyTop 是否应用顶部padding
     * @param applyBottom 是否应用底部padding
     */
    fun setSystemBarsPadding(
        view: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = if (applyTop) insets.top else v.paddingTop,
                bottom = if (applyBottom) insets.bottom else v.paddingBottom
            )
            windowInsets
        }
    }

    /**
     * 为AppBarLayout设置沉浸式状态栏
     * 适用于使用Toolbar的页面
     *
     * @param activity 当前Activity
     * @param appBarLayout AppBarLayout实例
     * @param lightStatusBar 状态栏图标是否为深色
     */
    fun setupWithAppBar(
        activity: Activity,
        appBarLayout: AppBarLayout,
        lightStatusBar: Boolean = true
    ) {
        setup(activity, lightStatusBar)

        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }
    }

    /**
     * 更新状态栏图标颜色
     *
     * @param activity 当前Activity
     * @param lightStatusBar 是否为深色图标
     */
    fun updateStatusBarIconColor(activity: Activity, lightStatusBar: Boolean) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = lightStatusBar
        }
    }

    /**
     * 更新导航栏图标颜色
     *
     * @param activity 当前Activity
     * @param lightNavigationBar 是否为深色图标
     */
    fun updateNavigationBarIconColor(activity: Activity, lightNavigationBar: Boolean) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightNavigationBars = lightNavigationBar
        }
    }
}