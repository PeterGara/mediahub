package com.petergara.mediahub.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/**
 * Jediné spojenie medzi UI a prehrávacou službou.
 * Drží MediaController + stav prehrávania ako StateFlow (pre Compose).
 */
object PlayerController {

    private val directExecutor = Executor { it.run() }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle

    private val _hasQueue = MutableStateFlow(false)
    val hasQueue: StateFlow<Boolean> = _hasQueue

    fun connect(context: Context) {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _isPlaying.value = controller?.isPlaying == true
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        _currentTitle.value =
                            mediaItem?.mediaMetadata?.title?.toString() ?: _currentTitle.value
                    }
                })
                _isPlaying.value = controller?.isPlaying ?: false
                _currentTitle.value =
                    controller?.mediaMetadata?.title?.toString() ?: ""
            } catch (e: Exception) {
                // service nie je dostupné – nič
            }
        }, directExecutor)
    }

    /** Prehrá zoznam skladieb od kliknutého indexu. Stav nastaví hneď – UI nesmie čakať na udalosť. */
    fun playQueue(items: List<MediaItem>, startIndex: Int) {
        val c = controller ?: return
        c.setMediaItems(items, startIndex, 0)
        c.prepare()
        c.play()
        // deterministicky nastav stav – bar sa zobrazí okamžite
        _hasQueue.value = items.isNotEmpty()
        _isPlaying.value = true
        items.getOrNull(startIndex)?.mediaMetadata?.title?.toString()
            ?.takeIf { it.isNotBlank() }?.let { _currentTitle.value = it }
    }

    fun stopAll() {
        controller?.stop()
        controller?.clearMediaItems()
        _hasQueue.value = false
        _isPlaying.value = false
        _currentTitle.value = ""
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        _hasQueue.value = false
        _isPlaying.value = false
        _currentTitle.value = ""
    }
}