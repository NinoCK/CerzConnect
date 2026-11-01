/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.kde.kdeconnect.plugins.applauncher.AppLauncherPlugin
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ItemAppDrawerBinding
import org.kde.kdeconnect_tp.databinding.PopupAppDrawerBinding

/** A grid of the PC's apps with a search field, shown as a popup over the mousepad. Tapping one opens it on the PC. */
class AppDrawerPopup(private val activity: Activity, private val plugin: AppLauncherPlugin) {

    private val binding = PopupAppDrawerBinding.inflate(activity.layoutInflater)
    private val dp = activity.resources.displayMetrics.density
    private val popup = PopupWindow(binding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    private val adapter = Adapter()
    private val placeholderTint = ColorStateList.valueOf(
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
    )

    init {
        popup.setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.cerz_round)!!.mutate().apply {
            setTint(MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        })
        popup.elevation = 6 * dp
        popup.setOnDismissListener { plugin.removeAppsUpdatedHandler(TAG) }

        binding.grid.adapter = adapter
        binding.search.doAfterTextChanged { update() }
    }

    fun show(anchor: View) {
        // Centered and nearly full height, so it fits wherever the button sits
        val root = anchor.rootView
        val width = minOf((MAX_WIDTH_DP * dp).toInt(), root.width - (2 * MARGIN_DP * dp).toInt())
        popup.width = width
        popup.height = root.height - (2 * MARGIN_DP * dp).toInt()
        val cells = (width - 24 * dp) / (CELL_WIDTH_DP * dp)
        binding.grid.layoutManager = GridLayoutManager(activity, cells.toInt().coerceAtLeast(3))

        plugin.setAppsUpdatedHandler(TAG, ::update)
        plugin.requestAppList()
        update()
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    fun dismiss() = popup.dismiss()

    private fun update() {
        val query = binding.search.text.toString().trim()
        val apps = plugin.apps.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        adapter.submit(apps)

        binding.empty.isVisible = apps.isEmpty()
        binding.empty.setText(if (plugin.appsReceived) R.string.cerz_apps_none else R.string.cerz_apps_loading)
    }

    private fun launch(app: AppLauncherPlugin.App) {
        plugin.launch(app.id)
        dismiss()
    }

    private inner class Holder(val item: ItemAppDrawerBinding) : RecyclerView.ViewHolder(item.root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var apps: List<AppLauncherPlugin.App> = emptyList()

        // Icons arrive a few at a time, so redrawing everything is simplest
        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<AppLauncherPlugin.App>) {
            apps = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAppDrawerBinding.inflate(activity.layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            val icon = plugin.getIcon(app.id)
            holder.item.name.text = app.name
            if (icon != null) {
                holder.item.icon.imageTintList = null
                holder.item.icon.setImageBitmap(icon)
            } else {
                holder.item.icon.imageTintList = placeholderTint
                holder.item.icon.setImageResource(R.drawable.cerz_apps)
            }
            holder.item.root.contentDescription = app.name
            holder.item.root.setOnClickListener { launch(app) }
        }
    }

    companion object {
        private const val TAG = "mousepad_app_drawer"
        private const val MAX_WIDTH_DP = 480
        private const val MARGIN_DP = 16
        private const val CELL_WIDTH_DP = 80
    }
}
