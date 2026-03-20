package com.tacke.music.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.tacke.music.R
import com.tacke.music.databinding.ActivityLyricSettingsBinding
import com.tacke.music.utils.LyricStyleSettings

class LyricSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricSettingsBinding

    companion object {
        const val PREFS_NAME = LyricStyleSettings.PREFS_NAME
        const val DEFAULT_LYRIC_COLOR = LyricStyleSettings.DEFAULT_LYRIC_COLOR
        val PRESET_LYRIC_COLORS = LyricStyleSettings.PRESET_FLOATING_LYRIC_COLORS

        fun getFloatingLyricColor(context: Context): Int = LyricStyleSettings.getFloatingLyricColor(context)
        fun setFloatingLyricColor(context: Context, color: Int) = LyricStyleSettings.setFloatingLyricColor(context, color)
        fun getFloatingLyricSize(context: Context): Float = LyricStyleSettings.getFloatingLyricSize(context)
        fun setFloatingLyricSize(context: Context, size: Float) = LyricStyleSettings.setFloatingLyricSize(context, size)

        fun getPlayerLyricColor(context: Context): Int = LyricStyleSettings.getPlayerLyricColor(context)
        fun setPlayerLyricColor(context: Context, color: Int) = LyricStyleSettings.setPlayerLyricColor(context, color)
        fun getPlayerLyricSize(context: Context): Float = LyricStyleSettings.getPlayerLyricSize(context)
        fun setPlayerLyricSize(context: Context, size: Float) = LyricStyleSettings.setPlayerLyricSize(context, size)

        fun getFullscreenLyricColor(context: Context): Int = LyricStyleSettings.getFullscreenLyricColor(context)
        fun setFullscreenLyricColor(context: Context, color: Int) = LyricStyleSettings.setFullscreenLyricColor(context, color)
        fun getFullscreenLyricSize(context: Context): Float = LyricStyleSettings.getFullscreenLyricSize(context)
        fun setFullscreenLyricSize(context: Context, size: Float) = LyricStyleSettings.setFullscreenLyricSize(context, size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupClickListeners()
        bindFloatingSection()
        bindPlayerSection()
        bindFullscreenSection()
    }

    override fun onResume() {
        super.onResume()
        refreshAllPreviews()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun bindFloatingSection() {
        binding.tvFloatingPreviewCurrent.text = "悬浮歌词正在播放"
        binding.tvFloatingPreviewNext.text = "下一句歌词预览"

        binding.btnFloatingSizeDown.setOnClickListener {
            updateFloatingSize(getFloatingLyricSize(this) - LyricStyleSettings.LYRIC_SIZE_STEP)
        }
        binding.btnFloatingSizeUp.setOnClickListener {
            updateFloatingSize(getFloatingLyricSize(this) + LyricStyleSettings.LYRIC_SIZE_STEP)
        }

        renderColorGrid(
            grid = binding.gridFloatingColors,
            colors = LyricStyleSettings.PRESET_FLOATING_LYRIC_COLORS,
            selectedColor = getFloatingLyricColor(this),
            onSelect = { color ->
                setFloatingLyricColor(this, color)
                refreshFloatingPreview()
            }
        )
        refreshFloatingPreview()
    }

    private fun bindPlayerSection() {
        binding.tvPlayerPreviewCurrent.text = "播放页当前歌词"
        binding.tvPlayerPreviewNext.text = "播放页下一句歌词"

        binding.btnPlayerSizeDown.setOnClickListener {
            updatePlayerSize(getPlayerLyricSize(this) - LyricStyleSettings.LYRIC_SIZE_STEP)
        }
        binding.btnPlayerSizeUp.setOnClickListener {
            updatePlayerSize(getPlayerLyricSize(this) + LyricStyleSettings.LYRIC_SIZE_STEP)
        }

        renderColorGrid(
            grid = binding.gridPlayerColors,
            colors = LyricStyleSettings.PRESET_PLAYER_LYRIC_COLORS,
            selectedColor = getPlayerLyricColor(this),
            onSelect = { color ->
                setPlayerLyricColor(this, color)
                refreshPlayerPreview()
            }
        )
        refreshPlayerPreview()
    }

    private fun bindFullscreenSection() {
        binding.tvFullscreenPreviewCurrent.text = "全屏歌词当前行"
        binding.tvFullscreenPreviewNext.text = "下一句歌词"

        binding.btnFullscreenSizeDown.setOnClickListener {
            updateFullscreenSize(getFullscreenLyricSize(this) - LyricStyleSettings.LYRIC_SIZE_STEP)
        }
        binding.btnFullscreenSizeUp.setOnClickListener {
            updateFullscreenSize(getFullscreenLyricSize(this) + LyricStyleSettings.LYRIC_SIZE_STEP)
        }

        renderColorGrid(
            grid = binding.gridFullscreenColors,
            colors = LyricStyleSettings.PRESET_FULLSCREEN_LYRIC_COLORS,
            selectedColor = getFullscreenLyricColor(this),
            onSelect = { color ->
                setFullscreenLyricColor(this, color)
                refreshFullscreenPreview()
            }
        )
        refreshFullscreenPreview()
    }

    private fun refreshAllPreviews() {
        refreshFloatingPreview()
        refreshPlayerPreview()
        refreshFullscreenPreview()
    }

    private fun refreshFloatingPreview() {
        val color = getFloatingLyricColor(this)
        val size = getFloatingLyricSize(this)
        binding.tvFloatingSizeValue.text = "${size.toInt()}sp"
        binding.tvFloatingPreviewCurrent.setTextColor(color)
        binding.tvFloatingPreviewCurrent.textSize = size
        binding.tvFloatingPreviewNext.textSize = (size * 0.7f).coerceAtLeast(12f)
        binding.tvFloatingPreviewNext.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        renderColorGrid(
            grid = binding.gridFloatingColors,
            colors = LyricStyleSettings.PRESET_FLOATING_LYRIC_COLORS,
            selectedColor = color,
            onSelect = { selected ->
                setFloatingLyricColor(this, selected)
                refreshFloatingPreview()
            }
        )
    }

    private fun refreshPlayerPreview() {
        val color = getPlayerLyricColor(this)
        val size = getPlayerLyricSize(this)
        binding.tvPlayerSizeValue.text = "${size.toInt()}sp"
        binding.tvPlayerPreviewCurrent.setTextColor(color)
        binding.tvPlayerPreviewCurrent.textSize = size
        binding.tvPlayerPreviewNext.textSize = (size * 0.78f).coerceAtLeast(12f)
        binding.tvPlayerPreviewNext.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        renderColorGrid(
            grid = binding.gridPlayerColors,
            colors = LyricStyleSettings.PRESET_PLAYER_LYRIC_COLORS,
            selectedColor = color,
            onSelect = { selected ->
                setPlayerLyricColor(this, selected)
                refreshPlayerPreview()
            }
        )
    }

    private fun refreshFullscreenPreview() {
        val color = getFullscreenLyricColor(this)
        val size = getFullscreenLyricSize(this)
        binding.tvFullscreenSizeValue.text = "${size.toInt()}sp"
        binding.tvFullscreenPreviewCurrent.setTextColor(color)
        binding.tvFullscreenPreviewCurrent.textSize = size
        binding.tvFullscreenPreviewNext.textSize = (size * 0.75f).coerceAtLeast(12f)
        binding.tvFullscreenPreviewNext.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvFullscreenPreviewNext.alpha = 0.55f
        renderColorGrid(
            grid = binding.gridFullscreenColors,
            colors = LyricStyleSettings.PRESET_FULLSCREEN_LYRIC_COLORS,
            selectedColor = color,
            onSelect = { selected ->
                setFullscreenLyricColor(this, selected)
                refreshFullscreenPreview()
            }
        )
    }

    private fun updateFloatingSize(size: Float) {
        setFloatingLyricSize(this, size)
        refreshFloatingPreview()
    }

    private fun updatePlayerSize(size: Float) {
        setPlayerLyricSize(this, size)
        refreshPlayerPreview()
    }

    private fun updateFullscreenSize(size: Float) {
        setFullscreenLyricSize(this, size)
        refreshFullscreenPreview()
    }

    private fun renderColorGrid(
        grid: GridLayout,
        colors: List<Int>,
        selectedColor: Int,
        onSelect: (Int) -> Unit
    ) {
        grid.removeAllViews()
        colors.forEach { color ->
            grid.addView(createColorChip(color, color == selectedColor) {
                onSelect(color)
            })
        }
    }

    private fun createColorChip(color: Int, selected: Boolean, onClick: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }

            val circleView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(56, 56).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }

            val checkView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(24, 24).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.ic_check)
                setColorFilter(Color.WHITE)
                visibility = if (selected) View.VISIBLE else View.GONE
            }

            addView(circleView)
            addView(checkView)
            setOnClickListener { onClick() }
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(
                top = insets.top
            )
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
