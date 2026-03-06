package com.tacke.music.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 状态栏工具类
 * 用于处理状态栏高度适配和沉浸式状态栏
 */
object StatusBarUtil {

    /**
     * 获取状态栏高度
     */
    fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            // 默认状态栏高度约为24dp
            (24 * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * 获取导航栏高度
     */
    fun getNavigationBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    /**
     * 设置状态栏占位View的高度
     */
    fun setStatusBarHeight(view: View) {
        view.layoutParams.height = getStatusBarHeight(view.context)
        view.requestLayout()
    }

    /**
     * 设置沉浸式状态栏（内容延伸到状态栏下方）
     */
    fun setImmersiveStatusBar(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                controller.systemBarsBehavior = 
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /**
     * 设置状态栏文字颜色为深色（适用于浅色背景）
     */
    fun setLightStatusBar(activity: Activity, isLight: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                if (isLight) {
                    controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                } else {
                    controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val decorView = activity.window.decorView
            val systemUiVisibility = decorView.systemUiVisibility
            decorView.systemUiVisibility = if (isLight) {
                systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }

    /**
     * 设置导航栏文字颜色为深色（适用于浅色背景）
     */
    fun setLightNavigationBar(activity: Activity, isLight: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                if (isLight) {
                    controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                } else {
                    controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val decorView = activity.window.decorView
            val systemUiVisibility = decorView.systemUiVisibility
            decorView.systemUiVisibility = if (isLight) {
                systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    /**
     * 为View设置系统栏padding
     */
    fun applySystemBarInsets(view: View, applyTop: Boolean = true, applyBottom: Boolean = true) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topInset = if (applyTop) systemBars.top else 0
            val bottomInset = if (applyBottom) systemBars.bottom else 0
            v.setPadding(v.paddingLeft, topInset, v.paddingRight, bottomInset)
            insets
        }
    }
}
