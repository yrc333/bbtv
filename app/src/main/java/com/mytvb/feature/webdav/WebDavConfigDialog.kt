package com.mytvb.feature.webdav

import android.content.Context
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.DialogWebDavConfigBinding

/** WebDAV 服务器地址 / 账号 / 密码配置弹窗。 */
class WebDavConfigDialog(
    context: Context,
    current: WebDavConfig?,
    private val onSave: (url: String, username: String, password: String) -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogWebDavConfigBinding.inflate(LayoutInflater.from(context))

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        DialogWindowFit.overlay(
            this,
            DialogWindowFit.wrapContentInScroll(context, binding.root),
            (context.resources.displayMetrics.widthPixels * 0.55f).toInt(),
            cancelOnOutside = false,
            tag = "webdavConfig"
        )

        binding.editUrl.setText(current?.baseUrl?.toString().orEmpty())
        binding.editUsername.setText(current?.username.orEmpty())
        binding.editPassword.setText(current?.password.orEmpty())

        binding.buttonCancel.setOnClickListener { dismiss() }
        binding.buttonSave.setOnClickListener {
            val rawUrl = binding.editUrl.text?.toString().orEmpty()
            if (WebDavConfigStore.normalizeBaseUrl(rawUrl) == null) {
                AppToast.show(context, R.string.webdav_url_invalid)
                return@setOnClickListener
            }
            onSave(
                rawUrl.trim(),
                binding.editUsername.text?.toString().orEmpty().trim(),
                binding.editPassword.text?.toString().orEmpty()
            )
            dismiss()
        }
        setOnShowListener { binding.editUrl.requestFocus() }
    }
}
