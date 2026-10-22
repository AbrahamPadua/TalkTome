package com.livingbook.speech

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Foreground media service so narration continues in the background and shows lock-screen controls. */
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null // speech worker
}
