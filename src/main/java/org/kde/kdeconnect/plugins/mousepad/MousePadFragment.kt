/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseFragment
import org.kde.kdeconnect.extensions.getSafeDrawInsets
import org.kde.kdeconnect.extensions.setOnApplyWindowInsetsListenerCompat
import org.kde.kdeconnect.ui.compose.components.addDeviceDisconnectedOverlay
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityMousepadBinding
import kotlin.math.abs
import kotlin.math.pow

/** CerzConnect: the remote input page, shown by MainActivity as the home of a connected device. */
class MousePadFragment :
    BaseFragment<ActivityMousepadBinding>(),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    MousePadGestureDetector.OnGestureListener,
    SensorEventListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    val deviceId: String by lazy { requireArguments().getString(ARG_DEVICE_ID)!! }
    private lateinit var plugin: MousePadPlugin

    private var mPrevX: Float = 0f
    private var mPrevY: Float = 0f
    internal var dragging: Boolean = false
    internal var maybeDragging: Boolean = false
    private var accumulatedDragDistance2: Float = 0.0f
    private var mCurrentSensitivity: Float = 1.0f
    private var displayDpiMultiplier: Float = 1.0f
    private var scrollDirection: Int = 1
    private var scrollCoefficient: Double = 1.0
    private var allowGyro: Boolean = false
    private var gyroEnabled: Boolean = false
    private var doubleTapDragEnabled: Boolean = false
    private var gyroscopeSensitivity: Int = 100
    private var isScrolling: Boolean = false
    private var accumulatedDistanceX: Double = 0.0
    private var accumulatedDistanceY: Double = 0.0
    private var mediaPopup: MediaPopup? = null
    private var touchpadRight: Boolean = false
    private var touchpadCollapsed: Boolean = false
    private var wheelY: Float = 0f
    private lateinit var wheel: ScrollWheelDrawable

    private lateinit var mDetector: GestureDetector
    private var mSensorManager: SensorManager? = null
    private lateinit var mMousePadGestureDetector: MousePadGestureDetector
    private lateinit var mPointerAccelerationProfile: PointerAccelerationProfile

    private var mouseDelta: PointerAccelerationProfile.MouseDelta = PointerAccelerationProfile.MouseDelta() // to be reused on every touch move event

    private lateinit var prefs: SharedPreferences

    private var prefsApplied: Boolean = false

    override fun getActionBarTitle(): String? = null

    override fun onInflateBinding(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        ActivityMousepadBinding.inflate(inflater, container, false)

    private enum class ClickType {
        LEFT,
        RIGHT,
        MIDDLE,
        NONE;

        companion object {
            fun fromString(s: String?): ClickType {
                return when (s) {
                    "left" -> LEFT
                    "right" -> RIGHT
                    "middle" -> MIDDLE
                    else -> NONE
                }
            }
        }
    }

    private var singleTapAction: ClickType = ClickType.NONE
    private var doubleTapAction: ClickType = ClickType.NONE
    private var tripleTapAction: ClickType = ClickType.NONE

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values

        var x = -values[2] * 70 * (gyroscopeSensitivity / 100.0f)
        var y = -values[0] * 70 * (gyroscopeSensitivity / 100.0f)

        if (x < 0.25 && x > -0.25) {
            x = 0f
        } else {
            x *= (gyroscopeSensitivity / 100.0f)
        }

        if (y < 0.25 && y > -0.25) {
            y = 0f
        } else {
            y *= (gyroscopeSensitivity / 100.0f)
        }

        plugin.sendMouseDelta(x, y)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val main = requireActivity() as MainActivity

        binding.mouseClickLeft.setOnClickListener { sendLeftClick() }
        wheel = ScrollWheelDrawable(binding.touchpadArea)
        binding.mouseClickMiddle.setOnTouchListener { v, e -> onWheelTouch(v, e); true }
        binding.mouseClickRight.setOnClickListener { sendRightClick() }

        val device = KdeConnect.getInstance().getDevice(deviceId)
        plugin = device?.getPlugin<MousePadPlugin>() ?: run {
            // Device gone or plugin turned off: fall back to the device page, which explains why
            main.onDeviceSelected(device?.deviceId)
            return
        }
        main.setFullscreen(true)
        binding.openMenu.setOnClickListener { main.openDrawer() }
        binding.root.addDeviceDisconnectedOverlay(device)
        binding.root.setOnGenericMotionListener { _, e -> onGenericMotion(e) }
        // Full screen, so keep clear of the cutout and the transient system bars
        binding.root.setOnApplyWindowInsetsListenerCompat { v, insets ->
            val safe = insets.getSafeDrawInsets()
            v.updatePadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        binding.keyboard.plugin = plugin
        binding.touchpad.setOnTouchListener { _, event -> onTouchpadEvent(event); true }
        binding.sendClipboard.setOnClickListener {
            device.getPlugin<ClipboardPlugin>()?.userInitiatedSendClipboard()
                ?: pluginOffToast(R.string.pref_plugin_clipboard)
        }
        binding.mediaControls.setOnClickListener { anchor ->
            val mpris = device.getPlugin<MprisPlugin>() ?: run {
                pluginOffToast(R.string.pref_plugin_mpris)
                return@setOnClickListener
            }
            mediaPopup = MediaPopup(requireActivity(), mpris).also { it.show(anchor) }
        }

        mDetector = GestureDetector(requireContext(), this)
        mMousePadGestureDetector = MousePadGestureDetector(this)
        mDetector.setOnDoubleTapListener(this)
        mSensorManager = ContextCompat.getSystemService(requireContext(), SensorManager::class.java)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.registerOnSharedPreferenceChangeListener(this)

        applyPrefs()
        setTouchpadCollapsed(prefs.getBoolean(getString(R.string.cerz_touchpad_collapsed_pref), false), false)
        binding.toggleTouchpad.setOnClickListener { setTouchpadCollapsed(!touchpadCollapsed, true) }

        // Technically xdpi and ydpi should be handled separately,
        // but since ydpi is usually almost equal to xdpi, only xdpi is used for the multiplier.
        displayDpiMultiplier = StandardDpi / resources.displayMetrics.xdpi
    }

    override fun onResume() {
        applyPrefs()

        if (allowGyro && !gyroEnabled) {
            mSensorManager?.let {
                it.registerListener(this, it.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
            }
            gyroEnabled = true
        }

        super.onResume()
    }

    override fun onPause() {
        if (gyroEnabled) {
            mSensorManager?.unregisterListener(this)
            gyroEnabled = false
        }
        super.onPause()
    }

    override fun onStop() {
        if (gyroEnabled) {
            mSensorManager?.unregisterListener(this)
            gyroEnabled = false
        }
        super.onStop()
    }

    override fun onDestroyView() {
        mediaPopup?.dismiss()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
        // Switching to another device keeps full screen, any other page gets the toolbar back
        (activity as? MainActivity)?.let { if (it.currentFragment !is MousePadFragment) it.setFullscreen(false) }
        super.onDestroyView()
    }

    private fun onTouchpadEvent(event: MotionEvent): Boolean {
        if (mMousePadGestureDetector.onTouchEvent(event)) {
            return true
        }
        if (mDetector.onTouchEvent(event)) {
            return true
        }

        val actionType = event.action

        if (isScrolling) {
            if (actionType == MotionEvent.ACTION_UP) {
                isScrolling = false
            } else {
                return false
            }
        }


        when (actionType) {
            MotionEvent.ACTION_DOWN -> {
                mPrevX = event.x
                mPrevY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val mCurrentX = event.x
                val mCurrentY = event.y

                val deltaX = (mCurrentX - mPrevX) * displayDpiMultiplier * mCurrentSensitivity
                val deltaY = (mCurrentY - mPrevY) * displayDpiMultiplier * mCurrentSensitivity

                if (maybeDragging) {
                    accumulatedDragDistance2 += deltaX * deltaX + deltaY * deltaY
                    if (accumulatedDragDistance2 >= MinDraggingDistance2) {
                        maybeDragging = false
                        dragging = true
                        accumulatedDragDistance2 = 0.0f
                        plugin.sendSingleHold()
                    }
                } else {
                    // Run the mouse delta through the pointer acceleration profile
                    mPointerAccelerationProfile.touchMoved(deltaX, deltaY, event.eventTime)
                    mouseDelta = mPointerAccelerationProfile.commitAcceleratedMouseDelta(mouseDelta)

                    plugin.sendMouseDelta(mouseDelta.x, mouseDelta.y)
                }

                mPrevX = mCurrentX
                mPrevY = mCurrentY
            }
            MotionEvent.ACTION_UP -> {
                if (doubleTapDragEnabled && maybeDragging) {
                    maybeDragging = false
                    accumulatedDragDistance2 = 0.0f
                    plugin.sendDoubleClick()
                }
            }
        }
        return true
    }

    /** The middle button is a scroll wheel: hold it and drag up or down, it doesn't click */
    private fun onWheelTouch(v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                wheel.show(v)
                wheelY = e.y
                accumulatedDistanceY = 0.0
            }
            MotionEvent.ACTION_MOVE -> {
                // Same units and direction as the two finger scroll on the touchpad: finger up scrolls up
                accumulatedDistanceY += (wheelY - e.y) * scrollCoefficient
                wheelY = e.y
                if (abs(accumulatedDistanceY) > MinDistanceToSendScroll) {
                    wheel.scrolled(up = scrollDirection * accumulatedDistanceY > 0)
                    sendScroll(0.0, scrollDirection * accumulatedDistanceY)
                    accumulatedDistanceY = 0.0
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> wheel.hide()
        }
    }

    override fun onDown(e: MotionEvent): Boolean = false

    override fun onShowPress(e: MotionEvent) {
        // From GestureDetector, left empty
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    private fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            val distanceY = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val distanceX = e.getAxisValue(MotionEvent.AXIS_HSCROLL)

            accumulatedDistanceX += distanceX
            accumulatedDistanceY += distanceY

            if (abs(accumulatedDistanceX) > MinDistanceToSendScroll || abs(accumulatedDistanceY) > MinDistanceToSendScroll) {
                sendScroll(accumulatedDistanceX, accumulatedDistanceY)
                accumulatedDistanceX = 0.0
                accumulatedDistanceY = 0.0
            }
        }

        return false
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        // If only one thumb is used then cancel the scroll gesture
        if (e2.pointerCount <= 1) {
            return false
        }

        isScrolling = true

        var filteredDistanceX = distanceX
        var filteredDistanceY = distanceY

        val absX = abs(filteredDistanceX)
        val absY = abs(filteredDistanceY)

        // Suppress minor perpendicular motion near either axis while preserving
        // deliberate diagonal scrolling.
        if (absX < absY * DirectionLockRatio) {
            filteredDistanceX = 0f
        } else if (absY < absX * DirectionLockRatio) {
            filteredDistanceY = 0f
        }

        accumulatedDistanceX += filteredDistanceX * scrollCoefficient
        accumulatedDistanceY += filteredDistanceY * scrollCoefficient

        if (abs(accumulatedDistanceX) > MinDistanceToSendScroll || abs(accumulatedDistanceY) > MinDistanceToSendScroll) {
            sendScroll(-scrollDirection * accumulatedDistanceX, scrollDirection * accumulatedDistanceY)

            accumulatedDistanceX = 0.0
            accumulatedDistanceY = 0.0
        }

        return true
    }

    override fun onLongPress(e: MotionEvent) {
        if (!doubleTapDragEnabled && !dragging) {
            binding.touchpad.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            plugin.sendSingleHold()
            dragging = true
        }
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        when (singleTapAction) {
            ClickType.LEFT -> sendLeftClick()
            ClickType.RIGHT -> sendRightClick()
            ClickType.MIDDLE -> sendMiddleClick()
            ClickType.NONE -> {}
        }
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (!dragging) {
            if (doubleTapDragEnabled) {
                maybeDragging = true
            } else {
                plugin.sendDoubleClick()
            }
        }
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && maybeDragging) {
            // Make sure we pass the event on to the general motion event handler which takes
            // care of ending an eventual drag.
            return false
        }
        return true
    }

    override fun onTripleFingerTap(ev: MotionEvent): Boolean {
        when (tripleTapAction) {
            ClickType.LEFT -> sendLeftClick()
            ClickType.RIGHT -> sendRightClick()
            ClickType.MIDDLE -> sendMiddleClick()
            ClickType.NONE -> {}
        }
        return true
    }

    override fun onDoubleFingerTap(ev: MotionEvent): Boolean {
        when (doubleTapAction) {
            ClickType.LEFT -> sendLeftClick()
            ClickType.RIGHT -> sendRightClick()
            ClickType.MIDDLE -> sendMiddleClick()
            ClickType.NONE -> {}
        }
        return true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        prefsApplied = false
    }

    private fun sendLeftClick() {
        if (dragging) {
            plugin.sendSingleRelease()
            dragging = false
        } else {
            plugin.sendLeftClick()
        }
    }

    private fun sendMiddleClick() {
        plugin.sendMiddleClick()
    }

    private fun sendRightClick() {
        plugin.sendRightClick()
    }

    private fun sendScroll(x: Double, y: Double) {
        plugin.sendScroll(x, y)
    }

    private fun applyPrefs() {
        if (prefsApplied) return
        if (!::prefs.isInitialized) return

        val p = prefs

        setTouchpadSide(p.getBoolean(getString(R.string.cerz_touchpad_right_pref), false))

        if (p.getBoolean(getString(R.string.mousepad_scroll_direction), false)) {
            scrollDirection = -1
        } else {
            scrollDirection = 1
        }

        var scrollSensitivity = p.getInt(getString(R.string.mousepad_scroll_sensitivity), 100)
        if (scrollSensitivity == 0) scrollSensitivity = 1
        scrollCoefficient = (scrollSensitivity / 100f).toDouble().pow(1.5)

        allowGyro = isGyroSensorAvailable() && p.getBoolean(getString(R.string.gyro_mouse_enabled), false)
        if (allowGyro) gyroscopeSensitivity = p.getInt(getString(R.string.gyro_mouse_sensitivity), 100)

        val singleTapSetting = p.getString(
            getString(R.string.mousepad_single_tap_key),
            getString(R.string.mousepad_default_single),
        )
        val doubleTapSetting = p.getString(
            getString(R.string.mousepad_double_tap_key),
            getString(R.string.mousepad_default_double),
        )
        val tripleTapSetting = p.getString(
            getString(R.string.mousepad_triple_tap_key),
            getString(R.string.mousepad_default_triple),
        )
        val sensitivitySetting = p.getString(
            getString(R.string.mousepad_sensitivity_key),
            getString(R.string.mousepad_default_sensitivity),
        )

        val accelerationProfileName = p.getString(
            getString(R.string.mousepad_acceleration_profile_key),
            getString(R.string.mousepad_default_acceleration_profile),
        ) ?: getString(R.string.mousepad_default_acceleration_profile)

        mPointerAccelerationProfile = PointerAccelerationProfileFactory.getProfileWithName(accelerationProfileName)

        singleTapAction = ClickType.fromString(singleTapSetting)
        doubleTapAction = ClickType.fromString(doubleTapSetting)
        tripleTapAction = ClickType.fromString(tripleTapSetting)

        when (sensitivitySetting) {
            "slowest" -> mCurrentSensitivity = 0.2f
            "aboveSlowest" -> mCurrentSensitivity = 0.5f
            "default" -> mCurrentSensitivity = 1.0f
            "aboveDefault" -> mCurrentSensitivity = 1.5f
            "fastest" -> mCurrentSensitivity = 2.0f
            else -> {
                mCurrentSensitivity = 1.0f
                return
            }
        }
        if (p.getBoolean(getString(R.string.mousepad_mouse_buttons_enabled_pref), true)) {
            binding.mouseButtons.visibility = View.VISIBLE
        } else {
            binding.mouseButtons.visibility = View.GONE
        }

        doubleTapDragEnabled = p.getBoolean(getString(R.string.mousepad_doubletap_drag_enabled_pref), true)

        prefsApplied = true
    }

    /** Collapsed, only the top buttons stay, as a column on the touchpad's side */
    private fun setTouchpadCollapsed(collapse: Boolean, animate: Boolean) {
        touchpadCollapsed = collapse
        if (animate) {
            prefs.edit { putBoolean(getString(R.string.cerz_touchpad_collapsed_pref), collapse) }
            TransitionManager.beginDelayedTransition(binding.mousepadView, AutoTransition().setOrdering(TransitionSet.ORDERING_TOGETHER))
        }
        binding.touchpadArea.isVisible = !collapse
        binding.topButtons.orientation = if (collapse) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        binding.sidePanel.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (collapse) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            weight = if (collapse) 0f else 3f
        }
        updateToggleIcon(animate)
    }

    private fun setTouchpadSide(right: Boolean) {
        touchpadRight = right
        val row = binding.mousepadView
        val panel = binding.sidePanel
        if ((row.indexOfChild(panel) == 1) == right) return
        row.removeView(panel)
        row.addView(panel, if (right) 1 else 0)
        panel.updateLayoutParams<LinearLayout.LayoutParams> {
            val gap = marginStart + marginEnd
            marginStart = if (right) gap else 0
            marginEnd = if (right) 0 else gap
        }
        updateToggleIcon(false)
    }

    // The chevron points the way the touchpad will go
    private fun updateToggleIcon(animate: Boolean) {
        val rotation = if (touchpadCollapsed != touchpadRight) 180f else 0f
        if (animate) binding.toggleTouchpad.animate().rotation(rotation) else binding.toggleTouchpad.rotation = rotation
    }

    private fun pluginOffToast(pluginName: Int) {
        Toast.makeText(requireContext(), getString(R.string.cerz_plugin_off, getString(pluginName)), Toast.LENGTH_SHORT).show()
    }

    private fun isGyroSensorAvailable(): Boolean {
        return mSensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }

    companion object {
        private const val ARG_DEVICE_ID = "deviceId"

        fun newInstance(deviceId: String) = MousePadFragment().apply { arguments = bundleOf(ARG_DEVICE_ID to deviceId) }

        /** The page needs a paired, connected device with the plugin turned on */
        fun canOpen(device: Device?) =
            device != null && device.isPaired && device.isReachable && device.getPlugin<MousePadPlugin>() != null

        private const val MinDistanceToSendScroll = 2.5f // touch gesture scroll
        private const val DirectionLockRatio = 0.5f
        private const val StandardDpi = 240.0f // = hdpi
        private const val MinDraggingDistance2 = 25.0f // distance squared to move after a double tap to start dragging
    }
}
