package com.mytvb.core.ui.base

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.mytvb.R

/**
 * 大字 Toast：直接用系统 Toast（实底清晰、样式与系统一致），仅把系统布局里
 * 14sp 的 message 字号改走 px 池——系统布局文字由调用方 context 的
 * scaledDensity 换算，v2.0.8 起 UiScale 把该值钉死为设计倍率后，原生 density
 * 较高的 TV 屏上字号会塌缩，故按 px 池直写（与全局 UI 缩放同通道）。
 */
object AppToast {

    private val DEFAULT_GRAVITY = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

    fun show(context: Context, text: String, duration: Int = Toast.LENGTH_SHORT) {
        show(context, text, duration, DEFAULT_GRAVITY)
    }

    fun show(context: Context, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(resId), duration, DEFAULT_GRAVITY)
    }

    /** 完整版：需要非默认位置时使用（API 30+ 系统 text toast 忽略位置，退回底部）。 */
    fun show(
        context: Context,
        text: String,
        duration: Int,
        gravity: Int,
        yOffsetPx: Int = context.resources.getDimensionPixelSize(R.dimen.px80)
    ) {
        val toast = Toast.makeText(context, text, duration)
        // 系统 text toast 的 message 字号被钉死 scaledDensity 劫持，直写 px 池字号
        // 并乘全局字体档；view 拿不到的防御路径按系统原样显示
        toast.view?.findViewById<TextView>(android.R.id.message)?.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            context.resources.getDimension(R.dimen.px36) * UiTextScale.scale()
        )
        // API 30+ 会忽略自定义 toast 的位置，退回系统默认底部，视觉仍成立
        toast.setGravity(gravity, 0, yOffsetPx)
        toast.show()
    }
}
