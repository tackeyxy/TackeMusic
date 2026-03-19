package com.tacke.music.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView

class OutlinedGlyphTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var inactiveColor: Int = Color.WHITE
        set(value) {
            field = value
            applyCurrentTextColor()
            invalidate()
        }

    var isActiveState: Boolean = false
        set(value) {
            field = value
            applyCurrentTextColor()
            invalidate()
        }

    var activeColor: Int = Color.RED
        set(value) {
            field = value
            applyCurrentTextColor()
            invalidate()
        }

    var emphasizeStrokeOnActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var strokeColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var strokeWidthPx: Float = resources.displayMetrics.density * 1.4f
        set(value) {
            field = value
            invalidate()
        }

    init {
        text = "词"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        textAlignment = TEXT_ALIGNMENT_CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        applyCurrentTextColor()
    }

    private fun applyCurrentTextColor() {
        // TextView 实际文字颜色来自 mCurTextColor，必须通过 setTextColor 更新
        super.setTextColor(if (isActiveState) activeColor else inactiveColor)
    }

    override fun onDraw(canvas: Canvas) {
        val paint = paint
        val oldStyle = paint.style
        val oldColor = paint.color
        val oldStrokeWidth = paint.strokeWidth
        val oldFakeBold = paint.isFakeBoldText

        paint.isFakeBoldText = true

        if (isActiveState && emphasizeStrokeOnActive) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.color = strokeColor
            paint.strokeWidth = strokeWidthPx
            super.onDraw(canvas)
        }

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = currentTextColor
        super.onDraw(canvas)

        paint.style = oldStyle
        paint.color = oldColor
        paint.strokeWidth = oldStrokeWidth
        paint.isFakeBoldText = oldFakeBold
    }
}
