package com.tacke.music.ui.base

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewbinding.ViewBinding

/**
 * Activity 基类
 * 统一处理 Edge-to-Edge 沉浸式状态栏适配
 */
abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = createBinding()
        setContentView(binding.root)

        // 设置 Edge-to-Edge 沉浸式状态栏
        setupEdgeToEdge()

        // 初始化视图
        initViews()

        // 设置监听器
        setupListeners()

        // 观察数据
        observeData()
    }

    /**
     * 创建 ViewBinding
     */
    abstract fun createBinding(): VB

    /**
     * 初始化视图
     */
    abstract fun initViews()

    /**
     * 设置监听器
     */
    abstract fun setupListeners()

    /**
     * 观察数据
     */
    abstract fun observeData()

    /**
     * 设置 Edge-to-Edge 沉浸式状态栏
     * 适配 Android 16 的强制 Edge-to-Edge 模式
     */
    private fun setupEdgeToEdge() {
        // 启用 Edge-to-Edge 模式
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置状态栏和导航栏为透明
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 处理系统栏 insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )

            // 为根视图设置 padding，避免内容被系统栏遮挡
            view.updatePadding(
                top = insets.top,
                bottom = insets.bottom
            )

            // 处理子视图的 insets
            handleChildViewInsets(view, insets)

            windowInsets
        }
    }

    /**
     * 处理子视图的 insets
     * 可以根据需要重写此方法来自定义子视图的处理逻辑
     */
    protected open fun handleChildViewInsets(rootView: View, insets: androidx.core.graphics.Insets) {
        // 默认不处理，子类可以重写
    }

    /**
     * 为指定视图设置顶部 padding（状态栏高度）
     */
    protected fun View.setStatusBarPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }
    }

    /**
     * 为指定视图设置底部 padding（导航栏高度）
     */
    protected fun View.setNavigationBarPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    /**
     * 为指定视图设置左右 padding（适配刘海屏）
     */
    protected fun View.setDisplayCutoutPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = insets.left,
                right = insets.right
            )
            windowInsets
        }
    }
}
