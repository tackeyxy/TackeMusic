package com.tacke.music.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tacke.music.R
import kotlin.math.sin
import kotlin.random.Random

/**
 * 音频频谱可视化视图
 * 模拟音频波形显示效果
 */
class AudioSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barCount = 48 // 频谱条数量
    private var barWidths = FloatArray(barCount)
    private var barHeights = FloatArray(barCount)
    private var targetHeights = FloatArray(barCount)
    private var animationSpeed = FloatArray(barCount)
    
    private var isPlaying = false
    private var primaryColor = ContextCompat.getColor(context, R.color.primary)
    private var accentColor = ContextCompat.getColor(context, R.color.accent_cyan)
    
    private var time = 0f
    private val random = Random(System.currentTimeMillis())
    
    init {
        barPaint.style = Paint.Style.FILL
        barPaint.strokeCap = Paint.Cap.ROUND
        
        // 初始化频谱条
        for (i in 0 until barCount) {
            barHeights[i] = 0f
            targetHeights[i] = 0f
            animationSpeed[i] = 0.05f + random.nextFloat() * 0.1f
        }
    }
    
    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (playing) {
            startAnimation()
        }
        invalidate()
    }
    
    fun setColors(primary: Int, accent: Int) {
        primaryColor = primary
        accentColor = accent
        invalidate()
    }
    
    private fun startAnimation() {
        postOnAnimation(object : Runnable {
            override fun run() {
                if (isPlaying) {
                    updateSpectrum()
                    invalidate()
                    postOnAnimation(this)
                }
            }
        })
    }
    
    private fun updateSpectrum() {
        time += 0.05f
        
        for (i in 0 until barCount) {
            // 使用正弦波组合模拟频谱效果
            val wave1 = sin(time * 2 + i * 0.3f)
            val wave2 = sin(time * 3 + i * 0.5f) * 0.5f
            val wave3 = sin(time * 1.5f + i * 0.2f) * 0.3f
            
            // 添加一些随机性
            val noise = (random.nextFloat() - 0.5f) * 0.3f
            
            // 计算目标高度 (0 到 1 之间)
            val target = ((wave1 + wave2 + wave3 + noise + 1.5f) / 3f).coerceIn(0f, 1f)
            targetHeights[i] = target
            
            // 平滑过渡
            val diff = targetHeights[i] - barHeights[i]
            barHeights[i] += diff * animationSpeed[i]
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // 计算每个频谱条的宽度
        val totalGap = (barCount + 1) * 4f // 间隙总和
        val availableWidth = w - totalGap
        val barWidth = availableWidth / barCount
        
        for (i in 0 until barCount) {
            barWidths[i] = barWidth.coerceAtLeast(4f)
        }
        
        // 创建渐变着色器
        val gradient = LinearGradient(
            0f, h.toFloat(),
            0f, 0f,
            intArrayOf(
                primaryColor,
                accentColor,
                accentColor and 0x00FFFFFF or 0x40000000
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        barPaint.shader = gradient
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        val gap = 4f
        val maxBarHeight = height * 0.6f // 最大高度为视图的60%
        val startX = (width - (barCount * barWidths[0] + (barCount - 1) * gap)) / 2f
        
        for (i in 0 until barCount) {
            val barHeight = barHeights[i] * maxBarHeight
            val x = startX + i * (barWidths[i] + gap)
            val bottom = height.toFloat()
            val top = bottom - barHeight
            
            // 绘制圆角矩形
            canvas.drawRoundRect(
                x,
                top,
                x + barWidths[i],
                bottom,
                barWidths[i] / 2f,
                barWidths[i] / 2f,
                barPaint
            )
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isPlaying = false
    }
}
