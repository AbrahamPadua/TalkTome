package com.livingbook.speech

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.livingbook.LivingBookApp

/**
 * Foreground media service so narration continues in the background and shows lock-screen controls.
 * [TtsNarrationService] connects to it on the first play; Media3 posts the notification and goes foreground while playing.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val narration = (application as LivingBookApp).container.narration as TtsNarrationService
        val openApp = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        session = MediaSession.Builder(this, NarrationPlayer(narration))
            .apply { if (openApp != null) setSessionActivity(openApp) }
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away ends the service unless narration is playing, which carries on in the background.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            // The app's own controller keeps the service bound; drop it or the service can never stop.
            ((application as LivingBookApp).container.narration as TtsNarrationService).releaseController()
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
