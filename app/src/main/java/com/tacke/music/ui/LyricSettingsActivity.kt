package com.tacke.music.ui

import android.content.Context
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

        const val DEFAULT_LYRIC_COLOR = 0xFF00CED1.toInt()

        val PRESET_LYRIC_COLORS = listOf(
            0xFF00CED1.toInt(),
            0xFFFF4081.toInt(),
            0xFF4CAF50.toInt(),
            0xFFFF9800.toInt(),
            0xFF9C27B0.toInt(),
            0xFF2196F3.toInt(),
            0xFFFFEB3B.toInt(),
            0xFFF44336.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF00BCD4.toInt(),
            0xFFE91E63.toInt(),
            0xFF8BC34A.toInt()
        )

        fun getPlayerLyricColor(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_PLAYER_LYRIC_COLOR, DEFAULT_LYRIC_COLOR)
        }

        fun setPlayerLyricColor(context: Context, color: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_PLAYER_LYRIC_COLOR, color).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupClickListeners()
        updateUI()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.layoutPlayerLyricColor.setOnClickListener {
            showColorPickerDialog()
        }
    }

    private fun updateUI() {
        val playerColor = getPlayerLyricColor(this)
        binding.viewPlayerLyricColorPreview.setBackgroundColor(playerColor)
    }

    private fun showColorPickerDialog() {
        val currentColor = getPlayerLyricColor(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_lyric_color_picker, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择播放页歌词颜色")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        val colorGrid = dialogView.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        val tvPreview = dialogView.findViewById<android.widget.TextView>(R.id.tvColorPreview)

        var selectedColor = currentColor

        tvPreview?.setTextColor(selectedColor)

        PRESET_LYRIC_COLORS.forEach { color ->
            val colorView = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }

                val circleView = android.widget.ImageView(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(56, 56).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                    })
                }

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

        dialogView.findViewById<android.widget.Button>(R.id.btnConfirm)?.setOnClickListener {
            setPlayerLyricColor(this, selectedColor)
            binding.viewPlayerLyricColorPreview.setBackgroundColor(selectedColor)
            Toast.makeText(this, "歌词颜色已更新", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
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
