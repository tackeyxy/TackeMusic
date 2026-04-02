package com.tacke.music.util

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tacke.music.R
import com.tacke.music.ui.MainActivity
import com.tacke.music.ui.PlayerActivity
import com.tacke.music.ui.ProfileActivity
import com.tacke.music.ui.SettingsActivity

/**
 * 导航栏帮助类
 * 用于处理横屏模式下左侧导航栏的交互
 */
class NavigationHelper(private val activity: Activity) {

    private var lastNavClickTime = 0L
    private val NAV_CLICK_DEBOUNCE = 500L // 500ms 防抖

    /**
     * 设置左侧导航栏点击事件
     * @param currentNavIndex 当前页面的导航索引 (0:首页, 1:播放, 2:我的)
     */
    fun setupSideNavigation(
        navHome: View?,
        navDiscover: View?,
        navProfile: View?,
        navSettings: View?,
        ivNavHome: ImageView?,
        ivNavDiscover: ImageView?,
        ivNavProfile: ImageView?,
        tvNavHome: TextView?,
        tvNavDiscover: TextView?,
        tvNavProfile: TextView?,
        currentNavIndex: Int
    ) {
        // 设置当前选中状态
        updateNavSelection(
            navHome, navDiscover, navProfile,
            ivNavHome, ivNavDiscover, ivNavProfile,
            tvNavHome, tvNavDiscover, tvNavProfile,
            currentNavIndex
        )

        // 首页导航
        navHome?.setOnClickListener {
            if (isNavClickValid() && currentNavIndex != 0) {
                val intent = Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }

        // 播放导航
        navDiscover?.setOnClickListener {
            if (isNavClickValid() && currentNavIndex != 1) {
                PlayerActivity.startEmpty(activity)
            }
        }

        // 我的导航
        navProfile?.setOnClickListener {
            if (isNavClickValid() && currentNavIndex != 2) {
                val intent = Intent(activity, ProfileActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }

        // 设置导航
        navSettings?.setOnClickListener {
            if (isNavClickValid()) {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
        }
    }

    /**
     * 更新导航栏选中状态
     */
    private fun updateNavSelection(
        navHome: View?,
        navDiscover: View?,
        navProfile: View?,
        ivNavHome: ImageView?,
        ivNavDiscover: ImageView?,
        ivNavProfile: ImageView?,
        tvNavHome: TextView?,
        tvNavDiscover: TextView?,
        tvNavProfile: TextView?,
        selectedIndex: Int
    ) {
        val selectedColor = activity.getColor(R.color.primary)
        val unselectedColor = activity.getColor(R.color.text_secondary)

        // 更新文字颜色
        tvNavHome?.setTextColor(if (selectedIndex == 0) selectedColor else unselectedColor)
        tvNavDiscover?.setTextColor(if (selectedIndex == 1) selectedColor else unselectedColor)
        tvNavProfile?.setTextColor(if (selectedIndex == 2) selectedColor else unselectedColor)

        // 更新图标
        ivNavHome?.setImageResource(if (selectedIndex == 0) R.drawable.ic_home_filled else R.drawable.ic_home)
        ivNavDiscover?.setImageResource(if (selectedIndex == 1) R.drawable.ic_discover_filled else R.drawable.ic_discover)
        ivNavProfile?.setImageResource(if (selectedIndex == 2) R.drawable.ic_profile_filled else R.drawable.ic_profile)

        // 更新图标颜色
        ivNavHome?.setColorFilter(if (selectedIndex == 0) selectedColor else unselectedColor)
        ivNavDiscover?.setColorFilter(if (selectedIndex == 1) selectedColor else unselectedColor)
        ivNavProfile?.setColorFilter(if (selectedIndex == 2) selectedColor else unselectedColor)

        // 更新背景
        navHome?.background = if (selectedIndex == 0) {
            ContextCompat.getDrawable(activity, R.drawable.bg_nav_item_selected)
        } else {
            ContextCompat.getDrawable(activity, R.drawable.bg_nav_item_normal)
        }
        navDiscover?.background = if (selectedIndex == 1) {
            ContextCompat.getDrawable(activity, R.drawable.bg_nav_item_selected)
        } else {
            ContextCompat.getDrawable(activity, R.drawable.bg_nav_item_normal)
        }
        navProfile?.background = if (selectedIndex == 2) {
            ContextCompat.getDrawable(activity, R.drawable.bg_nav_item_selected)
        } else {
            ContextCompat.getDrawable(activity, R.drawable.bg_nav_item_normal)
        }
    }

    private fun isNavClickValid(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNavClickTime > NAV_CLICK_DEBOUNCE) {
            lastNavClickTime = currentTime
            return true
        }
        return false
    }
}
