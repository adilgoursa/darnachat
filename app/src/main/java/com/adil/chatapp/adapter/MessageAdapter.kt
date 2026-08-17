package com.adil.chatapp.adapter

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.adil.chatapp.R
import com.adil.chatapp.databinding.ItemMessageReceivedBinding
import com.adil.chatapp.databinding.ItemMessageSentBinding
import com.adil.chatapp.model.Message
import com.adil.chatapp.model.MessageType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MessageAdapter(
    private val messages: List<Message>,
    private val currentUserId: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }

    // Only one audio clip plays at a time across the whole list.
    private var activePlayer: MediaPlayer? = null
    private var activePlayingPosition: Int = -1

    inner class SentViewHolder(val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root)
    inner class ReceivedViewHolder(val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == currentUserId) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SENT) {
            SentViewHolder(ItemMessageSentBinding.inflate(inflater, parent, false))
        } else {
            ReceivedViewHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        val tvMessage = when (holder) {
            is SentViewHolder -> holder.binding.tvMessage
            is ReceivedViewHolder -> holder.binding.tvMessage
            else -> return
        }
        val ivImage = when (holder) {
            is SentViewHolder -> holder.binding.ivImage
            is ReceivedViewHolder -> holder.binding.ivImage
            else -> return
        }
        val audioContainer = when (holder) {
            is SentViewHolder -> holder.binding.audioContainer
            is ReceivedViewHolder -> holder.binding.audioContainer
            else -> return
        }
        val btnPlay = when (holder) {
            is SentViewHolder -> holder.binding.btnPlay
            is ReceivedViewHolder -> holder.binding.btnPlay
            else -> return
        }
        val tvDuration = when (holder) {
            is SentViewHolder -> holder.binding.tvDuration
            is ReceivedViewHolder -> holder.binding.tvDuration
            else -> return
        }

        if (holder is SentViewHolder) {
            holder.binding.tvStatus.text = if (message.seen) "✓✓" else "✓"
        }

        tvMessage.visibility = android.view.View.GONE
        ivImage.visibility = android.view.View.GONE
        audioContainer.visibility = android.view.View.GONE
        ivImage.setImageDrawable(null)

        when (message.type) {
            MessageType.IMAGE -> {
                ivImage.visibility = android.view.View.VISIBLE
                try {
                    val bytes = Base64.decode(message.mediaUrl, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivImage.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    // Corrupted or empty payload; leave the image blank.
                }
            }
            MessageType.AUDIO -> {
                audioContainer.visibility = android.view.View.VISIBLE
                tvDuration.text = formatDuration(message.durationMs)
                val isPlayingThis = activePlayingPosition == position && activePlayer?.isPlaying == true
                btnPlay.setImageResource(if (isPlayingThis) R.drawable.ic_pause else R.drawable.ic_play)
                btnPlay.setOnClickListener {
                    toggleAudio(message.mediaUrl, position, btnPlay)
                }
            }
            else -> {
                tvMessage.visibility = android.view.View.VISIBLE
                tvMessage.text = message.text
            }
        }
    }

    private fun toggleAudio(base64Audio: String, position: Int, btnPlay: android.widget.ImageButton) {
        if (activePlayingPosition == position && activePlayer?.isPlaying == true) {
            activePlayer?.pause()
            btnPlay.setImageResource(R.drawable.ic_play)
            return
        }

        activePlayer?.release()
        activePlayer = null

        try {
            val bytes = Base64.decode(base64Audio, Base64.NO_WRAP)
            val tempFile = File.createTempFile("playback_", ".m4a", btnPlay.context.cacheDir)
            FileOutputStream(tempFile).use { it.write(bytes) }

            val player = MediaPlayer()
            activePlayingPosition = position
            player.setDataSource(tempFile.absolutePath)
            player.setOnPreparedListener {
                it.start()
                btnPlay.setImageResource(R.drawable.ic_pause)
            }
            player.setOnCompletionListener {
                btnPlay.setImageResource(R.drawable.ic_play)
                activePlayingPosition = -1
                tempFile.delete()
            }
            player.prepareAsync()
            activePlayer = player
        } catch (e: Exception) {
            activePlayingPosition = -1
        }
    }

    fun releasePlayer() {
        activePlayer?.release()
        activePlayer = null
        activePlayingPosition = -1
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun getItemCount(): Int = messages.size
}
