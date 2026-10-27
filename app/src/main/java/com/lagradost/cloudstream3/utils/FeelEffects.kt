package com.lagradost.cloudstream3.utils

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R

/**
 * Phase 8.16 — the shared "feel" helpers behind the animation pass: a
 * press-shrink for tappable cards/posters and a single haptic tick used by
 * navigation, the player and pull-to-refresh, so every surface answers a
 * touch the same way.
 *
 * Both are deliberately cheap: only scale/alpha are animated, and the tick
 * is a single system constant — no vibrator permission, no patterns.
 */

/** Whether the viewer left haptics on (Settings → General, default on). */
fun Context.hapticsEnabled(): Boolean = try {
    PreferenceManager.getDefaultSharedPreferences(this)
        .getBoolean(getString(R.string.haptics_enabled_key), true)
} catch (e: Exception) {
    true
}

/** One light tick, if haptics are enabled. Safe on any API level: an
 * unsupported feedback constant is ignored by the system. */
fun View.hapticTick() {
    if (!context.hapticsEnabled()) return
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

/**
 * Makes a tappable view shrink slightly while pressed and spring back on
 * release — the "alive under the finger" cue. The touch listener always
 * returns false, so clicks, long-presses and focus handling on the view
 * are untouched; a scroll intercept delivers ACTION_CANCEL, which resets
 * the scale, so a recycled item can never stay shrunk mid-fling.
 */
fun View.addPressShrink(pressedScale: Float = 0.97f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate()
                .scaleX(pressedScale).scaleY(pressedScale)
                .setDuration(120).start()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(180).start()
        }
        false
    }
}
