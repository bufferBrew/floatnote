package com.bufferbrew.perch.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Every duration, easing and spring in the app. Pick a spec by what the motion is for, never a
 * literal number at the call site.
 *
 * Reduced motion: Compose scales all of these by the system's animator duration scale, in the
 * Activity and in the overlay's service window alike, so "Remove animations" ends them
 * instantly. Nothing here may be replaced by `delay()` loops or frame clocks, which would not be.
 */
object Motion {
    /** Direct feedback: press, toggle, check-off. */
    const val Press = 100
    /** Small things appearing or leaving: a row of buttons, a status strip. */
    const val Quick = 150
    /** Things entering or changing state inside a screen. */
    const val Standard = 250
    /** A whole screen or the floating window coming in. */
    const val Screen = 350
    /** A whole screen leaving. Shorter: the user has already decided to go. */
    const val ScreenExit = 200

    /** Material 3 emphasized decelerate: things arriving. */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material 3 emphasized accelerate: things leaving. */
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Opacity only. Linear is right for a fade. */
    fun <T> fade(duration: Int = Quick): FiniteAnimationSpec<T> = tween(duration, easing = LinearEasing)

    fun <T> enter(duration: Int = Standard): FiniteAnimationSpec<T> = tween(duration, easing = EnterEasing)

    fun <T> exit(duration: Int = Quick): FiniteAnimationSpec<T> = tween(duration, easing = ExitEasing)

    /** Position and size: interruptible, no overshoot. */
    fun <T> move(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Scale for pressed feedback and the bubble: quick, a hint of give. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
}
