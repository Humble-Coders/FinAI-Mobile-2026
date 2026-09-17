package com.humblesolutions.finai.ui.components

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.humblesolutions.finai.util.LoaderTiming
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's one coin loader, for every signed-out screen and the wizard.
 *
 * Whenever it is up, it is in the middle of the screen with everything behind it
 * blurred, and it stays for at least [LoaderTiming.MIN_VISIBLE_MS] — or as long
 * as the work really takes. A card with a coin resting on its edge tells the
 * loader where that coin is, so the loader's coin sets off from there and goes
 * back there: one coin moving, rather than one vanishing while another appears.
 */
@Stable
class AppLoader {

    /** The resting coin's centre, in window coordinates, and the card that placed it. */
    internal var resting: Offset? = null
        private set
    private var restingOwner: Any? = null

    /** True while the loader's coin is away from its card; the card hides its own meanwhile. */
    var coinAway by mutableStateOf(false)
        internal set

    private val requests = mutableStateMapOf<String, Unit>()

    /** Whether any screen outside the onboarding flow has asked for the loader. */
    val requested: Boolean get() = requests.isNotEmpty()

    fun request(key: String, active: Boolean) {
        if (active) requests[key] = Unit else requests.remove(key)
    }

    internal fun place(owner: Any, centre: Offset) {
        restingOwner = owner
        resting = centre
    }

    internal fun release(owner: Any) {
        if (restingOwner !== owner) return
        restingOwner = null
        resting = null
    }
}

val LocalAppLoader = staticCompositionLocalOf { AppLoader() }

/** Asks for the loader for as long as [active] holds and this stays on screen. */
@Composable
fun LoaderSignal(key: String, active: Boolean) {
    val loader = LocalAppLoader.current
    DisposableEffect(loader, key, active) {
        loader.request(key, active)
        onDispose { loader.request(key, false) }
    }
}

/**
 * Wraps the whole app: blurs it and puts the coin over it while [active], held
 * for the minimum however quickly the work finishes.
 */
@Composable
fun LoaderHost(active: Boolean, loader: AppLoader, content: @Composable () -> Unit) {
    val shown = rememberHeldVisible(active)
    val blur by animateDpAsState(if (shown) BlurRadius else 0.dp, tween(TRAVEL_MS), label = "loaderBlur")
    val veil by animateFloatAsState(if (shown) 1f else 0f, tween(TRAVEL_MS), label = "loaderVeil")
    var host by remember { mutableStateOf(IntSize.Zero) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(shown) {
        // Typing is over for now, and a keyboard would cover the coin.
        if (shown) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { host = it }) {
        // Blur needs API 31. Below it the veil is thicker, so the screen still
        // steps back behind the coin.
        Box(Modifier.fillMaxSize().blur(blur)) {
            CompositionLocalProvider(LocalAppLoader provides loader) { content() }
        }
        if (veil > 0f) {
            val ground = MaterialTheme.colorScheme.background
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(veil)
                    .background(ground.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.2f else 0.7f))
                    // Nothing behind the loader can be pressed while it is up.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
        TravellingCoin(shown, loader, host)
    }
}

/**
 * [active], but once it turns true it stays true for at least the minimum,
 * measured from the moment it first showed.
 */
@Composable
fun rememberHeldVisible(active: Boolean): Boolean {
    var shown by remember { mutableStateOf(active) }
    var shownAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(active) {
        if (active) {
            if (!shown) {
                shownAt = SystemClock.uptimeMillis()
                shown = true
            }
        } else if (shown) {
            delay(LoaderTiming.remainingMs(shownAt, SystemClock.uptimeMillis()))
            shown = false
        }
    }
    return shown
}

@Composable
private fun BoxScope.TravellingCoin(shown: Boolean, loader: AppLoader, host: IntSize) {
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scale = remember { Animatable(1f) }
    val opacity = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(shown) {
        val centre = Offset(host.width / 2f, host.height / 2f)
        val home = loader.resting?.takeIf { host != IntSize.Zero }?.minus(centre)
        if (shown) {
            if (!visible) {
                offset.snapTo(home ?: Offset.Zero)
                scale.snapTo(if (home != null) 1f else LOADING_SCALE)
                opacity.snapTo(if (home != null) 1f else 0f)
                visible = true
                loader.coinAway = true
            }
            coroutineScope {
                launch { offset.animateTo(Offset.Zero, tween(TRAVEL_MS)) }
                launch { scale.animateTo(LOADING_SCALE, tween(TRAVEL_MS)) }
                launch { opacity.animateTo(1f, tween(FADE_MS)) }
            }
        } else if (visible) {
            if (home != null) {
                coroutineScope {
                    launch { offset.animateTo(home, tween(TRAVEL_MS)) }
                    launch { scale.animateTo(1f, tween(TRAVEL_MS)) }
                }
            } else {
                opacity.animateTo(0f, tween(FADE_MS))
            }
            visible = false
            loader.coinAway = false
        }
    }

    if (visible) {
        CoinBadge(
            Modifier
                .align(Alignment.Center)
                .offset { offset.value.round() }
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = opacity.value
                },
        )
    }
}

private val BlurRadius = 18.dp
private const val TRAVEL_MS = 420
private const val FADE_MS = 200

/** The resting coin is 88dp; in the middle of the screen it reads at 112dp. */
private const val LOADING_SCALE = 112f / 88f
