package com.tacke.music.ui.base

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tacke.music.util.StatusBarUtil

/**
 * 基础Activity类
 * 统一处理状态栏适配
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupStatusBar()
    }

    /**
     * 设置状态栏
     * 子类可以重写此方法来自定义状态栏样式
     */
    protected open fun setupStatusBar() {
        // 默认设置沉浸式状态栏
        StatusBarUtil.setImmersiveStatusBar(this)
        StatusBarUtil.setLightStatusBar(this, true)
        StatusBarUtil.setLightNavigationBar(this, true)
    }

    /**
     * 为View添加状态栏高度padding
     */
    protected fun applyStatusBarInsets(view: View) {
        StatusBarUtil.applySystemBarInsets(view, applyTop = true, applyBottom = false)
    }

    /**
     * 设置状态栏占位View的高度
     */
    protected fun setStatusBarHeight(view: View) {
        StatusBarUtil.setStatusBarHeight(view)
    }
}
