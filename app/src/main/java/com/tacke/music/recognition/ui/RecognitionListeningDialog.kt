package com.tacke.music.recognition.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tacke.music.R

class RecognitionListeningDialog(
    context: Context,
    private val onStartRecording: (() -> Unit)? = null,
    private val onSelectFile: (() -> Unit)? = null,
    private val onCancel: (() -> Unit)? = null
) : Dialog(context, R.style.DialogTheme) {

    private lateinit var layoutAnimation: FrameLayout
    private lateinit var wave1: android.view.View
    private lateinit var wave2: android.view.View
    private lateinit var wave3: android.view.View
    private lateinit var tvStatus: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnStartRecord: MaterialButton
    private lateinit var btnSelectFile: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var ivCenterIcon: ImageView

    private var animatorSet: AnimatorSet? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_recognition_listening, null)
        setContentView(view)

        layoutAnimation = view.findViewById(R.id.layoutAnimation)
        wave1 = view.findViewById(R.id.wave1)
        wave2 = view.findViewById(R.id.wave2)
        wave3 = view.findViewById(R.id.wave3)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvSubtitle = view.findViewById(R.id.tvSubtitle)
        btnStartRecord = view.findViewById(R.id.btnStartRecord)
        btnSelectFile = view.findViewById(R.id.btnSelectFile)
        btnCancel = view.findViewById(R.id.btnCancel)
        ivCenterIcon = view.findViewById(R.id.ivCenterIcon)

        // 开始录音按钮
        btnStartRecord.setOnClickListener {
            onStartRecording?.invoke()
        }

        // 选择文件按钮
        btnSelectFile.setOnClickListener {
            onSelectFile?.invoke()
        }

        // 取消按钮
        btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
    }

    /**
     * 切换到录音模式（显示动画，隐藏按钮）
     */
    fun startRecordingMode() {
        runOnUiThread {
            // 检查视图是否已初始化（避免在 onCreate 之前调用）
            if (!::btnStartRecord.isInitialized) {
                // 延迟执行，等待 onCreate 完成
                mainHandler.postDelayed({ startRecordingMode() }, 100)
                return@runOnUiThread
            }
            
            // 显示动画
            layoutAnimation.visibility = View.VISIBLE
            
            // 更新文字
            tvStatus.text = "正在聆听..."
            tvSubtitle.text = "请播放或哼唱歌曲"
            
            // 隐藏按钮
            btnStartRecord.visibility = View.GONE
            btnSelectFile.visibility = View.GONE
            
            // 开始动画
            startWaveAnimation()
        }
    }

    /**
     * 切换到文件处理模式
     */
    fun startFileProcessingMode() {
        runOnUiThread {
            // 检查视图是否已初始化（避免在 onCreate 之前调用）
            if (!::btnStartRecord.isInitialized) {
                // 延迟执行，等待 onCreate 完成
                mainHandler.postDelayed({ startFileProcessingMode() }, 100)
                return@runOnUiThread
            }
            
            // 隐藏按钮
            btnStartRecord.visibility = View.GONE
            btnSelectFile.visibility = View.GONE
            
            // 更新文字
            tvStatus.text = "正在处理音频文件..."
            tvSubtitle.text = "请稍候"
            
            // 显示文件夹图标
            layoutAnimation.visibility = View.VISIBLE
            ivCenterIcon.setImageResource(R.drawable.ic_folder)
        }
    }

    private fun startWaveAnimation() {
        val animators = listOf(wave1, wave2, wave3).mapIndexed { index, view ->
            val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.5f, 1.5f)
            val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.5f, 1.5f)
            val alpha = ObjectAnimator.ofFloat(view, "alpha", 0.6f, 0f)

            listOf(scaleX, scaleY, alpha).forEach { animator ->
                animator.duration = 1500
                animator.repeatCount = ValueAnimator.INFINITE
                animator.repeatMode = ValueAnimator.RESTART
                animator.startDelay = (index * 500).toLong()
                animator.interpolator = AccelerateDecelerateInterpolator()
            }

            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
            }
        }

        animatorSet = AnimatorSet().apply {
            playTogether(animators as Collection<android.animation.Animator>)
            start()
        }
    }

    /**
     * 设置状态文字（线程安全）
     */
    fun setStatus(status: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvStatus.text = status
        } else {
            mainHandler.post { tvStatus.text = status }
        }
    }

    /**
     * 设置副标题文字（线程安全）
     */
    fun setSubtitle(subtitle: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvSubtitle.text = subtitle
        } else {
            mainHandler.post { tvSubtitle.text = subtitle }
        }
    }

    fun stopWaveAnimation() {
        animatorSet?.cancel()
        wave1.alpha = 0f
        wave2.alpha = 0f
        wave3.alpha = 0f
    }

    /**
     * 在主线程运行代码
     */
    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    override fun dismiss() {
        animatorSet?.cancel()
        super.dismiss()
    }
}
