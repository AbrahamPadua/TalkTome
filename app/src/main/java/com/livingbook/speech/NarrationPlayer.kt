package com.livingbook.speech

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The narration as a Media3 player: mirrors its state for the media session (notification, lock screen, headset)
 * and turns their commands into narration calls. Next and previous move by topic; seek forward and back by sentence.
 */
internal class NarrationPlayer(private val narration: TtsNarrationService) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = MainScope()

    init {
        scope.launch { merge(narration.state, narration.request).collect { invalidateState() } }
    }

    override fun getState(): State {
        val state = State.Builder()
            .setAvailableCommands(COMMANDS)
            // With a single item, repeat-all is what makes Media3 accept next/previous; handleSeek turns them into topic moves.
            .setRepeatMode(Player.REPEAT_MODE_ALL)
        val request = narration.request.value ?: return state.build()
        val status = narration.state.value.status
        val metadata = MediaMetadata.Builder().setTitle(request.topicTitle).setArtist(request.bookTitle).build()
        val item = MediaItem.Builder().setMediaId(request.topicTitle).setMediaMetadata(metadata).build()
        return state
            .setPlaylist(listOf(MediaItemData.Builder("${request.bookTitle}/${request.topicTitle}").setMediaItem(item).build()))
            .setPlayWhenReady(status == NarrationStatus.PLAYING, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when (status) {
                    NarrationStatus.PLAYING, NarrationStatus.PAUSED -> Player.STATE_READY
                    NarrationStatus.ENDED -> Player.STATE_ENDED
                    NarrationStatus.IDLE, NarrationStatus.ERROR -> Player.STATE_IDLE
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) narration.play() else narration.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> narration.send(NarrationEvent.NextTopicRequested)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> narration.send(NarrationEvent.PreviousTopicRequested)
            Player.COMMAND_SEEK_FORWARD -> narration.skipForward()
            Player.COMMAND_SEEK_BACK -> narration.skipBack()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
