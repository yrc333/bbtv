package com.mytvb.ui.dialog

import android.content.Context
import android.graphics.Color
import android.os.CountDownTimer
import android.text.Html
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import com.mytvb.R
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.DialogUsageTipBinding

class UsageTipDialog(context: Context) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogUsageTipBinding.inflate(LayoutInflater.from(context))
    private var countDownTimer: CountDownTimer? = null

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        // 返回键允许直接关闭：否则倒计时期间遥控器/触屏没有任何退出途径，
        // 用户只能强杀应用（usage_tip_shown 已落盘，关闭后不再弹出）。
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        // fill 全屏窗口 + 居中卡片（华为电视对独立小窗有延迟裁高，fill 载体不受影响）
        DialogWindowFit.overlay(
            this, binding.root,
            (context.resources.displayMetrics.widthPixels * 0.6).toInt(),
            cancelOnOutside = false,
            tag = "usageTip"
        )
        // 内容在小屏/大字号下可能高过屏幕，限制滚动区高度避免按钮被推出屏幕
        binding.textScroll.maxHeight =
            (context.resources.displayMetrics.heightPixels * 0.65f).toInt()

        binding.buttonConfirm.setTextColor(Color.GRAY)
        binding.buttonConfirm.isClickable = false
        binding.buttonConfirm.text = context.getString(R.string.confirm_countdown, 10)

        binding.buttonConfirm.setOnClickListener { dismiss() }

        binding.textContent.text =
            Html.fromHtml(context.getString(R.string.usage_tip_content), Html.FROM_HTML_MODE_COMPACT)

        setOnShowListener {
            startCountdown()
        }
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.buttonConfirm.text =
                    context.getString(R.string.confirm_countdown, seconds.toInt())
            }

            override fun onFinish() {
                binding.buttonConfirm.setText(R.string.confirm)
                binding.buttonConfirm.setTextColor(
                    context.resources.getColor(R.color.pink, null)
                )
                binding.buttonConfirm.isClickable = true
                binding.buttonConfirm.requestFocus()
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        countDownTimer?.cancel()
    }
}
