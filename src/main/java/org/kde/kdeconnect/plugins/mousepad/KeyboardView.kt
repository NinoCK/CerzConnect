/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect_tp.R
import java.text.Normalizer
import com.google.android.material.R as MaterialR

/**
 * On-screen keyboard (US or Greek) that sends key presses through the MousePadPlugin.
 *
 * Modifiers work like physical ones while a finger holds them, and latch for the next key when tapped.
 * The protocol releases modifiers after every packet, so Tabs pressed while Alt/Ctrl/Super is held are
 * queued and sent as one packet when a modifier is let go: Alt+Tab+Tab then reaches the third window.
 */
class KeyboardView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var plugin: MousePadPlugin? = null

    private class Key(
        val label: String,
        val text: String? = null,
        val shifted: String? = null,
        val special: Int = 0,
        val modifier: String? = null,
        val weight: Float = 1f,
    )

    /** Modifiers under a finger, and whether another key was pressed meanwhile */
    private val held = mutableMapOf<String, Boolean>()
    private val latched = mutableSetOf<String>()
    private val keyViews = mutableListOf<Pair<TextView, Key>>()
    private val repeater = Handler(Looper.getMainLooper())
    private val dp = resources.displayMetrics.density
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var greek = prefs.getBoolean(context.getString(R.string.cerz_greek_pref), false)
    private var accent: String? = null
    private var pendingTabs = 0
    private var tabMods = emptySet<String>()
    private var repeating: Key? = null

    private val repeatTask = object : Runnable {
        override fun run() {
            press(repeating ?: return)
            repeater.postDelayed(this, 50)
        }
    }

    init {
        orientation = VERTICAL
        val rows = listOf(
            listOf(special("Esc", KeyEvent.KEYCODE_ESCAPE)) + (1..12).map { special("F$it", KeyEvent.KEYCODE_F1 + it - 1) },
            chars("`1234567890-=", "~!@#$%^&*()_+") + special("⌫", KeyEvent.KEYCODE_DEL, 2f),
            listOf(special("Tab", KeyEvent.KEYCODE_TAB, 1.5f)) + chars("qwertyuiop[]", "QWERTYUIOP{}") +
                Key("\\", "\\", "|", weight = 1.5f),
            listOf(modifier("Ctrl", "ctrl", 1.75f)) + chars("asdfghjkl;'", "ASDFGHJKL:\"") +
                special("Enter", KeyEvent.KEYCODE_ENTER, 2.25f),
            listOf(modifier("Shift", "shift", 2f)) + chars("zxcvbnm,./", "ZXCVBNM<>?") +
                listOf(modifier("Shift", "shift"), special("↑", KeyEvent.KEYCODE_DPAD_UP), special("Del", KeyEvent.KEYCODE_FORWARD_DEL)),
            listOf(
                modifier("Super", "super", 1.5f), modifier("Alt", "alt", 1.5f), special("", LANGUAGE, 1.5f),
                Key("space", " ", " ", weight = 7.5f),
                special("←", KeyEvent.KEYCODE_DPAD_LEFT), special("↓", KeyEvent.KEYCODE_DPAD_DOWN), special("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        )
        for ((i, row) in rows.withIndex()) {
            val rowView = LinearLayout(context)
            for (key in row) rowView.addView(makeKey(key))
            addView(rowView, LayoutParams(LayoutParams.MATCH_PARENT, 0, if (i == 0) 0.75f else 1f))
        }
        refresh()
    }

    private fun chars(normal: String, shifted: String) =
        normal.indices.map { Key(normal[it].toString(), normal[it].toString(), shifted[it].toString()) }

    private fun special(label: String, keyCode: Int, weight: Float = 1f) = Key(label, special = keyCode, weight = weight)

    private fun modifier(label: String, name: String, weight: Float = 1f) = Key(label, modifier = name, weight = weight)

    /** What the key types now, unshifted and shifted */
    private fun chars(key: Key): Pair<String, String> =
        GREEK[key.text]?.takeIf { greek } ?: (key.text!! to key.shifted!!)

    private fun mods() = held.keys + latched

    private fun color(attr: Int) = MaterialColors.getColor(this, attr)

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = 10 * dp
        setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(key: Key): TextView {
        val isChar = key.text != null
        // Material roles: letters on the highest surface, other keys tonal, pressed or latched keys primary
        val accent = color(androidx.appcompat.R.attr.colorPrimary)
        val onAccent = color(MaterialR.attr.colorOnPrimary)
        val textColor = color(
            when {
                key.text == " " -> MaterialR.attr.colorOnSurfaceVariant
                isChar -> MaterialR.attr.colorOnSurface
                else -> MaterialR.attr.colorOnSecondaryContainer
            }
        )
        val view = TextView(context).apply {
            text = key.label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isChar && key.label.length == 1) 17f else 12f)
            if (!isChar) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(accent))
                addState(intArrayOf(android.R.attr.state_selected), rounded(accent))
                addState(intArrayOf(), rounded(color(if (isChar) MaterialR.attr.colorSurfaceContainerHighest else MaterialR.attr.colorSecondaryContainer)))
            }
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(onAccent, onAccent, textColor),
                )
            )
            val margin = (3 * dp).toInt()
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight).apply { setMargins(margin, margin, margin, margin) }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (key.modifier != null) {
                        held[key.modifier] = false
                        refresh()
                    } else {
                        keyDown(key)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (key.modifier != null) modifierUp(key.modifier) else keyUp(key)
                }
            }
            true
        }
        keyViews += view to key
        return view
    }

    private fun modifierUp(modifier: String) {
        val used = held.remove(modifier) ?: return
        // A tap without another key latches (or unlatches) it for the next key
        if (!used && !latched.remove(modifier)) latched.add(modifier)
        flushTabs()
        refresh()
    }

    private fun keyDown(key: Key) {
        held.entries.forEach { it.setValue(true) }
        if (key.special == LANGUAGE) {
            toggleLanguage()
            return
        }
        press(key)
        repeating = key
        repeater.removeCallbacks(repeatTask)
        repeater.postDelayed(repeatTask, 400)
    }

    private fun keyUp(key: Key) {
        if (repeating === key) {
            repeater.removeCallbacks(repeatTask)
            repeating = null
        }
        if (latched.isNotEmpty()) {
            latched.clear()
            refresh()
        }
    }

    private fun press(key: Key) {
        val mods = mods()
        if (key.special == KeyEvent.KEYCODE_TAB && held.keys.any { it != "shift" }) {
            if (pendingTabs > 0 && mods != tabMods) flushTabs()
            tabMods = mods
            pendingTabs++
            refresh()
            return
        }
        flushTabs()
        send(key, mods)
    }

    private fun flushTabs() {
        if (pendingTabs == 0) return
        val np = packet(tabMods)
        // A multi character key is typed with the modifiers held throughout
        if (pendingTabs == 1) np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_TAB) else np["key"] = "\t".repeat(pendingTabs)
        if ("shift" in tabMods) np["shift"] = true
        pendingTabs = 0
        plugin?.sendPacket(np)
        refresh()
    }

    private fun send(key: Key, mods: Set<String>) {
        val np = packet(mods)
        val shift = "shift" in mods
        val hadAccent = accent != null
        if (key.special != 0) {
            np["specialKey"] = KeyListenerView.SpecialKeysMap.get(key.special)
            if (shift) np["shift"] = true
            accent = null
        } else if (mods.any { it != "shift" }) {
            // Ctrl/Alt/Super shortcuts want the plain key plus the shift flag, not the shifted symbol
            np["key"] = chars(key).first
            if (shift) np["shift"] = true
            accent = null
        } else {
            val c = chars(key).let { if (shift) it.second else it.first }
            DEAD_KEYS[c]?.let {
                accent = it
                refresh()
                return
            }
            np["key"] = accent?.let { a -> Normalizer.normalize(c + a, Normalizer.Form.NFC).takeIf { it.length == 1 } } ?: c
            accent = null
        }
        if (hadAccent) refresh()
        plugin?.sendPacket(np)
    }

    /** Shift is left to the caller, plain characters carry it in the character itself */
    private fun packet(mods: Set<String>) = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
        for (m in mods) if (m != "shift") this[m] = true
    }

    private fun toggleLanguage() {
        greek = !greek
        prefs.edit { putBoolean(context.getString(R.string.cerz_greek_pref), greek) }
        accent = null
        // Switch the PC's input source too (GNOME and Windows), it can only type characters of its active layout
        plugin?.sendPacket(packet(setOf("super")).apply { this["key"] = " " })
        refresh()
    }

    private fun refresh() {
        val mods = mods()
        val shift = "shift" in mods
        for ((view, key) in keyViews) {
            view.isSelected = key.modifier != null && key.modifier in mods
            when {
                key.text != null && key.label == key.text -> {
                    val c = chars(key).let { if (shift) it.second else it.first }
                    view.text = c
                    view.isSelected = accent != null && DEAD_KEYS[c] == accent
                }
                key.special == KeyEvent.KEYCODE_TAB -> view.text = if (pendingTabs > 0) "Tab ×$pendingTabs" else "Tab"
                key.special == LANGUAGE -> view.text = if (greek) "ΕΛ" else "EN"
            }
        }
    }

    override fun onDetachedFromWindow() {
        repeater.removeCallbacks(repeatTask)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LANGUAGE = -1

        // Greek layout over the US letter keys, unshifted and shifted
        private val GREEK = "qwertyuiopasdfghjkl;zxcvbnm".let { us ->
            val normal = ";ςερτυθιοπασδφγηξκλ΄ζχψωβνμ"
            val shifted = ":ΣΕΡΤΥΘΙΟΠΑΣΔΦΓΗΞΚΛ¨ΖΧΨΩΒΝΜ"
            us.indices.associate { us[it].toString() to (normal[it].toString() to shifted[it].toString()) }
        }

        /** Tonos and dialytika combine with the next letter, like on a Greek keyboard */
        private val DEAD_KEYS = mapOf("΄" to "́", "¨" to "̈")
    }
}
