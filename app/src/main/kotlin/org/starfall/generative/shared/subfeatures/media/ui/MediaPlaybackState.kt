package org.starfall.generative.shared.subfeatures.media.ui
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import org.starfall.generative.shared.helpers.audio.MediaPlayers

internal class MediaPlaybackState(val player: Player) {
    var ready by mutableStateOf(false)
    var error by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var duration by mutableIntStateOf(0)
    var position by mutableIntStateOf(0)

    fun sync() {
        ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
        playing = player.isPlaying
        duration = player.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        position = player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    fun toggle() {
        if (!ready || error) return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.playWhenReady) player.pause()
        else player.play()
        sync()
    }

    fun seek(positionMs: Int) {
        player.seekTo(positionMs.toLong())
        position = positionMs
    }
}

@Composable
internal fun rememberMediaPlayback(reference: String, initialPosition: Int = 0, autoPlay: Boolean = false,
    onPositionChanged: (Int) -> Unit = {}, onPlayingChanged: (Boolean) -> Unit = {}): MediaPlaybackState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(reference) { MediaPlaybackState(MediaPlayers.create(context)) }
    val positionChanged by rememberUpdatedState(onPositionChanged)
    val playingChanged by rememberUpdatedState(onPlayingChanged)
    DisposableEffect(state, lifecycle) {
        val player = state.player
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state.sync()
                positionChanged(state.position)
                playingChanged(state.playing)
            }
            override fun onPlayerError(error: PlaybackException) { state.error = true; state.sync() }
        }
        player.addListener(listener)
        player.setMediaItem(MediaPlayers.item(reference), initialPosition.toLong())
        player.playWhenReady = autoPlay && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        player.prepare()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            positionChanged(player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(state, state.playing) {
        while (state.playing) {
            state.sync()
            positionChanged(state.position)
            delay(250)
        }
    }
    return state
}
