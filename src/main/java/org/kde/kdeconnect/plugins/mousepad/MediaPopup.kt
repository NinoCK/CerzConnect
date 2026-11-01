/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.PopupMediaBinding

/** Compact version of the multimedia controls, shown as a popup over the touchpad. */
class MediaPopup(private val activity: Activity, private val plugin: MprisPlugin) {

    private val binding = PopupMediaBinding.inflate(activity.layoutInflater)
    private val dp = activity.resources.displayMetrics.density
    private val popup = PopupWindow(binding.root, (280 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
    private val ticker = Handler(Looper.getMainLooper())
    private var player: MprisPlugin.MprisPlayer? = null
    private var seeking = false

    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            ticker.postDelayed(this, 1000)
        }
    }

    init {
        popup.setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.cerz_round)!!.mutate().apply {
            setTint(MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        })
        popup.elevation = 6 * dp
        popup.setOnDismissListener {
            plugin.removePlayerStatusUpdatedHandler(TAG)
            plugin.removePlayerListUpdatedHandler(TAG)
            ticker.removeCallbacks(tick)
        }

        binding.prev.setOnClickListener { player?.sendPrevious() }
        binding.play.setOnClickListener { player?.sendPlayPause() }
        binding.next.setOnClickListener { player?.sendNext() }
        binding.seek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.position.text = time(value.toLong())
        }
        binding.seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                seeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
                player?.sendSetPosition(slider.value.toInt())
            }
        })
        binding.volume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                player?.sendSetVolume(slider.value.toInt())
            }
        })
        binding.player.setOnClickListener { anchor ->
            PopupMenu(activity, anchor).apply {
                plugin.playerList.forEach { menu.add(it) }
                setOnMenuItemClickListener { item ->
                    select(item.title.toString())
                    true
                }
            }.show()
        }
    }

    fun show(anchor: View) {
        plugin.setPlayerListUpdatedHandler(TAG) { activity.runOnUiThread(::updateList) }
        plugin.setPlayerStatusUpdatedHandler(TAG) { activity.runOnUiThread(::update) }
        ticker.post(tick)
        popup.showAsDropDown(anchor, 0, (6 * dp).toInt())
    }

    fun dismiss() = popup.dismiss()

    private fun updateList() {
        val names = plugin.playerList
        binding.noPlayers.isVisible = names.isEmpty()
        binding.player.isVisible = names.isNotEmpty()
        binding.controls.isVisible = names.isNotEmpty()
        select(player?.playerName?.takeIf { it in names } ?: plugin.playingPlayer?.playerName ?: names.firstOrNull())
    }

    private fun select(name: String?) {
        player = plugin.getPlayerStatus(name)
        binding.player.text = name
        update()
    }

    private fun update() {
        val p = player ?: plugin.getEmptyPlayer()
        binding.title.text = p.title
        binding.artist.text = p.artist
        binding.artist.isVisible = p.artist.isNotEmpty()
        binding.seekRow.isVisible = p.isSeekAllowed
        // Slider throws if its range is empty or the value falls outside it
        binding.seek.valueTo = p.length.coerceAtLeast(1).toFloat()
        binding.length.text = time(p.length)
        updatePosition()
        binding.play.setIconResource(if (p.isPlaying) R.drawable.ic_pause_black else R.drawable.ic_play_black)
        binding.prev.isEnabled = p.isGoPreviousAllowed
        binding.next.isEnabled = p.isGoNextAllowed
        binding.volumeRow.isVisible = p.isSetVolumeAllowed
        binding.volume.value = p.volume.coerceIn(0, 100).toFloat()
    }

    private fun updatePosition() {
        val p = player ?: return
        if (seeking || !p.isSeekAllowed) return
        binding.seek.value = p.position.toFloat().coerceIn(0f, binding.seek.valueTo)
        binding.position.text = time(p.position)
    }

    private fun time(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    companion object {
        private const val TAG = "mousepad_popup"
    }
}
