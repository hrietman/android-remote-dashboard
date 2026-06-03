package nl.hrietman.androidremotedashboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

class AudioCommandPlayer(
    private val context: Context,
) {
    private var mediaPlayer: MediaPlayer? = null

    fun play(url: String) {
        stop()

        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        @Suppress("DEPRECATION")
        player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        player.setOnPreparedListener { preparedPlayer ->
            if (preparedPlayer === mediaPlayer) {
                preparedPlayer.start()
            }
        }
        player.setOnCompletionListener { completedPlayer ->
            if (completedPlayer === mediaPlayer) {
                stop()
            } else {
                completedPlayer.reset()
                completedPlayer.release()
            }
        }
        player.setOnErrorListener { erroredPlayer, _, _ ->
            if (erroredPlayer === mediaPlayer) {
                stop()
            } else {
                erroredPlayer.reset()
                erroredPlayer.release()
            }
            true
        }
        player.setDataSource(context, android.net.Uri.parse(url))
        player.prepareAsync()
    }

    fun stop() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (_: Exception) {
        }
        try {
            player.reset()
        } catch (_: Exception) {
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
    }
}
