package com.tacke.music.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.databinding.ActivityPlayerCoverSettingsBinding

class PlayerCoverSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerCoverSettingsBinding
    private var rotationAnimator: ObjectAnimator? = null

    companion object {
        const val PREFS_NAME = "player_cover_settings"
        const val KEY_COVER_STYLE = "cover_style"

        // 封面显示样式
        const val COVER_STYLE_ROTATING_CIRCLE = 0  // 旋转圆形
        const val COVER_STYLE_STATIC_SQUARE = 1    // 静态正方形

        fun getCoverStyle(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_COVER_STYLE, COVER_STYLE_ROTATING_CIRCLE)
        }

        fun setCoverStyle(context: Context, style: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_COVER_STYLE, style).apply()
        }

        fun start(context: Context) {
            val intent = Intent(context, PlayerCoverSettingsActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerCoverSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupClickListeners()
        loadCurrentStyle()
        setupPreview()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarPlaceholder?.layoutParams?.height = insets.top
            binding.statusBarPlaceholder?.requestLayout()
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 旋转圆形选项
        binding.layoutRotatingCircle.setOnClickListener {
            selectStyle(COVER_STYLE_ROTATING_CIRCLE)
        }
        binding.radioRotatingCircle.setOnClickListener {
            selectStyle(COVER_STYLE_ROTATING_CIRCLE)
        }

        // 静态正方形选项
        binding.layoutStaticSquare.setOnClickListener {
            selectStyle(COVER_STYLE_STATIC_SQUARE)
        }
        binding.radioStaticSquare.setOnClickListener {
            selectStyle(COVER_STYLE_STATIC_SQUARE)
        }
    }

    private fun loadCurrentStyle() {
        val currentStyle = getCoverStyle(this)
        updateRadioButtons(currentStyle)
        updatePreview(currentStyle)
    }

    private fun selectStyle(style: Int) {
        setCoverStyle(this, style)
        updateRadioButtons(style)
        updatePreview(style)
    }

    private fun updateRadioButtons(selectedStyle: Int) {
        binding.radioRotatingCircle.isChecked = selectedStyle == COVER_STYLE_ROTATING_CIRCLE
        binding.radioStaticSquare.isChecked = selectedStyle == COVER_STYLE_STATIC_SQUARE
    }

    private fun setupPreview() {
        // 加载示例图片到预览区域
        Glide.with(this)
            .load(R.drawable.ic_album_default)
            .placeholder(R.drawable.ic_album_default)
            .into(binding.ivPreview)

        // 初始化旋转动画
        rotationAnimator = ObjectAnimator.ofFloat(binding.previewContainer, View.ROTATION, 0f, 360f).apply {
            duration = 20000 // 20秒一圈
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
    }

    private fun updatePreview(style: Int) {
        when (style) {
            COVER_STYLE_ROTATING_CIRCLE -> {
                // 圆形样式 - 设置大圆角
                binding.previewContainer.post {
                    val size = minOf(binding.previewContainer.width, binding.previewContainer.height)
                    binding.previewContainer.radius = size / 2f
                    binding.previewContainer.cardElevation = 0f
                }
                // 启动旋转动画
                rotationAnimator?.start()
            }
            COVER_STYLE_STATIC_SQUARE -> {
                // 正方形样式 - 设置小圆角
                binding.previewContainer.radius = 16f
                binding.previewContainer.cardElevation = 0f
                // 停止旋转动画并复位
                rotationAnimator?.pause()
                binding.previewContainer.rotation = 0f
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rotationAnimator?.cancel()
    }
}
