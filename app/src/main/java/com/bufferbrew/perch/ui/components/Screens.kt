package com.bufferbrew.perch.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.bufferbrew.perch.ui.theme.Dimens
import com.bufferbrew.perch.ui.theme.Motion
import kotlinx.coroutines.CancellationException

/**
 * Shows one screen at a time and animates between them with a shared-axis slide: a deeper
 * screen ([depth] higher) comes in from the end edge, going back reverses it. Used by the full
 * app and the floating panel alike, so both move the same way.
 *
 * [screen] should be a small key (not the data it shows): content reads live data itself. With
 * [backTo] set, the system back gesture previews that screen as you swipe (predictive back) and
 * calls [onBack] when you let go; cancelling the swipe settles back. Leave it null where back
 * means something else (the overlay minimises on back).
 */
@Composable
fun <S : Any> ScreenSwitcher(
    screen: S,
    depth: (S) -> Int,
    modifier: Modifier = Modifier,
    backTo: S? = null,
    onBack: () -> Unit = {},
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val state = remember { SeekableTransitionState(screen) }
    val transition = rememberTransition(state, label = "screen")
    // Follows the app's state. Starting from wherever a back swipe left the seek, so letting go
    // finishes the move rather than restarting it.
    LaunchedEffect(screen) {
        if (state.currentState != screen || state.targetState != screen) state.animateTo(screen)
    }
    if (backTo != null) {
        PredictiveBackHandler { swipe ->
            try {
                swipe.collect { event -> state.seekTo(event.progress, targetState = backTo) }
                onBack()
            } catch (e: CancellationException) {
                state.animateTo(screen)
                throw e
            }
        }
    }
    val slide = with(LocalDensity.current) { Dimens.ScreenSlide.roundToPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    transition.AnimatedContent(
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = { sharedAxis(forward = depth(targetState) >= depth(initialState), slide, rtl) },
        content = content,
    )
}

private fun <S> AnimatedContentTransitionScope<S>.sharedAxis(forward: Boolean, slide: Int, rtl: Boolean): ContentTransform {
    val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
    return (
        slideInHorizontally(Motion.enter(Motion.Screen)) { sign * slide } +
            fadeIn(Motion.fade(Motion.Standard))
        ) togetherWith (
        slideOutHorizontally(Motion.exit(Motion.ScreenExit)) { -sign * slide } +
            fadeOut(Motion.fade(Motion.Quick))
        ) using null
}

/**
 * The last non-null [value]. A screen that is animating out can keep drawing what it showed
 * (the note that was just closed, say) after the app has already moved on.
 */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val held = remember { arrayOfNulls<Any>(1) }
    if (value != null) held[0] = value
    @Suppress("UNCHECKED_CAST")
    return held[0] as T?
}
