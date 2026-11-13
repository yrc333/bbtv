package com.mytvb.ui.fragment.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mytvb.R
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.databinding.FragmentMainMenuBinding
import com.mytvb.ui.activity.MainActivity

/**
 * 启动菜单页：宫格展示全部功能入口。选中后由 [MainActivity] 全屏打开对应页面，
 * 功能页不显示该菜单；返回后回到本页并恢复焦点。
 */
class MainMenuFragment : BaseFragment<FragmentMainMenuBinding>() {

    companion object {
        private const val COLUMNS = 5

        fun newInstance(): MainMenuFragment = MainMenuFragment()
    }

    enum class Entry(@StringRes val titleRes: Int, @DrawableRes val iconRes: Int) {
        RECOMMEND(R.string.recommend, R.drawable.tab_recommend),
        HOT(R.string.hot, R.drawable.ic_hot),
        ANIMATION(R.string.animation, R.drawable.ic_tv),
        CINEMA(R.string.film_and_television, R.drawable.ic_display),
        SEARCH(R.string.search, R.drawable.ic_search),
        HOME(R.string.menu_home, R.drawable.ic_home),
        FOLLOWING(R.string.user_following, R.drawable.ic_user),
        HISTORY(R.string.history, R.drawable.ic_history),
        SIGN_IN(R.string.sign_in, R.drawable.tab_me),
        SETTINGS(R.string.setting, R.drawable.ic_setting)
    }

    private val menuItems = mutableListOf<View>()

    override fun useLightBaseContainer(): Boolean = true

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMainMenuBinding {
        return FragmentMainMenuBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        buildMenuRows()
        binding.root.post { focusFirstItem() }
    }

    private fun buildMenuRows() {
        val container = binding.menuRows
        container.removeAllViews()
        menuItems.clear()

        Entry.values().toList().chunked(COLUMNS).forEach { rowEntries ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowEntries.forEach { entry ->
                row.addView(createMenuItem(entry, row))
            }
            // 末行不足 COLUMNS 个时补等宽空位，保证两行宫格左右对齐
            repeat(COLUMNS - rowEntries.size) {
                row.addView(
                    View(requireContext()),
                    LinearLayout.LayoutParams(0, 1, 1f)
                )
            }
            container.addView(row)
        }
    }

    private fun createMenuItem(entry: Entry, parent: ViewGroup): View {
        val item = layoutInflater.inflate(R.layout.item_main_menu, parent, false)
        item.findViewById<ImageView>(R.id.menuIcon).setImageResource(entry.iconRes)
        item.findViewById<TextView>(R.id.menuLabel).text = getString(entry.titleRes)
        item.contentDescription = getString(entry.titleRes)
        item.setOnClickListener {
            (activity as? MainActivity)?.openMainMenuEntry(entry)
        }
        menuItems.add(item)
        return item
    }

    /** 菜单页获得焦点时的兜底落点（首次进入 / 返回后锚点失效）。 */
    fun focusFirstItem(): Boolean {
        val target = menuItems.firstOrNull { it.isShown && it.isFocusable } ?: return false
        return target.requestFocus()
    }
}
