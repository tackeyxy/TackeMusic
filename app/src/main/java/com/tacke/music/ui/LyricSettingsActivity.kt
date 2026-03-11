package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.tacke.music.R
import com.tacke.music.databinding.ActivityLyricSettingsBinding

class LyricSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricSettingsBinding

    companion object {
        const val PREFS_NAME = "lyric_settings"
        const val KEY_PLAYER_LYRIC_COLOR = "player_lyric_color"
        const val KEY_FLOATING_LYRIC_COLOR = "floating_lyric_color"
        const val KEY_FLOATING_LYRICS_ENABLED = "floating_lyrics_enabled"
        const val KEY_FLOATING_LYRIC_SIZE = "floating_lyric_size"

        // 默认歌词颜色（青色）
        const val DEFAULT_LYRIC_COLOR = 0xFF00CED1.toInt()
        // 默认歌词大小（100%）
        const val DEFAULT_LYRIC_SIZE = 100

        // 预设歌词颜色列表
        val PRESET_LYRIC_COLORS = listOf(
            0xFF00CED1.toInt(), // 深青色 (默认)
            0xFFFF4081.toInt(), // 粉红色
            0xFF4CAF50.toInt(), // 绿色
            0xFFFF9800.toInt(), // 橙色
            0xFF9C27B0.toInt(), // 紫色
            0xFF2196F3.toInt(), // 蓝色
            0xFFFFEB3B.toInt(), // 黄色
            0xFFF44336.toInt(), // 红色
            0xFFFFFFFF.toInt(), // 白色
            0xFF00BCD4.toInt(), // 青色
            0xFFE91E63.toInt(), // 玫红
            0xFF8BC34A.toInt()  // 浅绿
        )

        // 获取播放页歌词颜色（兼容旧版本）
        fun getPlayerLyricColor(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // 先尝试获取新的key，如果没有则尝试旧的key
            return prefs.getInt(KEY_PLAYER_LYRIC_COLOR, 0).let {
                if (it == 0) {
                    // 尝试从旧设置获取
                    val oldPrefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    val oldColor = oldPrefs.getInt(SettingsActivity.KEY_LYRIC_COLOR, 0)
                    if (oldColor != 0) {
                        // 迁移旧数据
                        prefs.edit().putInt(KEY_PLAYER_LYRIC_COLOR, oldColor).apply()
                        oldColor
                    } else {
                        DEFAULT_LYRIC_COLOR
                    }
                } else it
            }
        }

        fun setPlayerLyricColor(context: Context, color: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_PLAYER_LYRIC_COLOR, color).apply()
            // 同时更新旧设置以保持兼容
            val oldPrefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            oldPrefs.edit().putInt(SettingsActivity.KEY_LYRIC_COLOR, color).apply()
        }

        fun getFloatingLyricColor(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_FLOATING_LYRIC_COLOR, DEFAULT_LYRIC_COLOR)
        }

        fun setFloatingLyricColor(context: Context, color: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_FLOATING_LYRIC_COLOR, color).apply()
        }

        fun isFloatingLyricsEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_FLOATING_LYRICS_ENABLED, false)
        }

        fun setFloatingLyricsEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_FLOATING_LYRICS_ENABLED, enabled).apply()
        }

        fun getFloatingLyricSize(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_FLOATING_LYRIC_SIZE, DEFAULT_LYRIC_SIZE)
        }

        fun setFloatingLyricSize(context: Context, size: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_FLOATING_LYRIC_SIZE, size).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        setupClickListeners()
        updateUI()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 播放页歌词颜色设置
        binding.layoutPlayerLyricColor.setOnClickListener {
            showColorPickerDialog(true)
        }

        // 悬浮歌词颜色设置
        binding.layoutFloatingLyricColor.setOnClickListener {
            showColorPickerDialog(false)
        }

        // 歌词大小滑条
        binding.seekBarLyricSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 将 0-150 映射到 50-200
                val sizePercent = 50 + progress
                binding.tvLyricSizeValue.text = "$sizePercent%"
                updatePreviewLyricSize(sizePercent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 保存设置
                val sizePercent = 50 + (seekBar?.progress ?: 50)
                setFloatingLyricSize(this@LyricSettingsActivity, sizePercent)
                Toast.makeText(this@LyricSettingsActivity, "歌词大小已保存", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun updateUI() {
        // 更新播放页歌词颜色预览
        val playerColor = getPlayerLyricColor(this)
        binding.viewPlayerLyricColorPreview.setBackgroundColor(playerColor)

        // 更新悬浮歌词颜色预览
        val floatingColor = getFloatingLyricColor(this)
        binding.viewFloatingLyricColorPreview.setBackgroundColor(floatingColor)
        binding.tvPreviewCurrentLine.setTextColor(floatingColor)

        // 更新歌词大小
        val lyricSize = getFloatingLyricSize(this)
        binding.seekBarLyricSize.progress = lyricSize - 50
        binding.tvLyricSizeValue.text = "$lyricSize%"
        updatePreviewLyricSize(lyricSize)
    }

    private fun updatePreviewLyricSize(sizePercent: Int) {
        val scale = sizePercent / 100f
        val currentTextSize = 16f * scale
        val nextTextSize = 13f * scale

        binding.tvPreviewCurrentLine.textSize = currentTextSize
        binding.tvPreviewNextLine.textSize = nextTextSize
    }

    private fun showColorPickerDialog(isPlayerLyric: Boolean) {
        val currentColor = if (isPlayerLyric) getPlayerLyricColor(this) else getFloatingLyricColor(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_lyric_color_picker, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isPlayerLyric) "选择播放页歌词颜色" else "选择悬浮歌词颜色")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        // 获取颜色网格容器
        val colorGrid = dialogView.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        val tvPreview = dialogView.findViewById<android.widget.TextView>(R.id.tvColorPreview)

        var selectedColor = currentColor

        // 更新预览文本颜色
        tvPreview?.setTextColor(selectedColor)

        // 创建颜色选择项
        PRESET_LYRIC_COLORS.forEach { color ->
            val colorView = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(8, 8, 8, 8)
                }

                // 颜色圆点
                val circleView = android.widget.ImageView(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(56, 56).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                    })
                }

                // 选中标记
                val checkView = android.widget.ImageView(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(24, 24).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(android.graphics.Color.WHITE)
                    visibility = if (color == currentColor) android.view.View.VISIBLE else android.view.View.GONE
                }

                addView(circleView)
                addView(checkView)

                setOnClickListener {
                    selectedColor = color
                    tvPreview?.setTextColor(selectedColor)

                    // 更新所有选中标记
                    for (i in 0 until colorGrid.childCount) {
                        val child = colorGrid.getChildAt(i) as? android.widget.FrameLayout
                        val check = child?.getChildAt(1) as? android.widget.ImageView
                        check?.visibility = if (i == PRESET_LYRIC_COLORS.indexOf(color))
                            android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            }
            colorGrid?.addView(colorView)
        }

        // 确认按钮
        dialogView.findViewById<android.widget.Button>(R.id.btnConfirm)?.setOnClickListener {
            if (isPlayerLyric) {
                setPlayerLyricColor(this, selectedColor)
                binding.viewPlayerLyricColorPreview.setBackgroundColor(selectedColor)
            } else {
                setFloatingLyricColor(this, selectedColor)
                binding.viewFloatingLyricColorPreview.setBackgroundColor(selectedColor)
                binding.tvPreviewCurrentLine.setTextColor(selectedColor)
            }
            Toast.makeText(this, "歌词颜色已更新", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Android 16: 设置 Edge-to-Edge 模式
     * 处理系统栏（状态栏和导航栏）的 insets
     * 为顶部 Toolbar 添加状态栏高度 padding，防止内容被状态栏遮挡
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 为顶部 Toolbar 添加状态栏高度 padding
            binding.toolbar.updatePadding(
                top = insets.top
            )
            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
