package com.adil.chatapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.adil.chatapp.adapter.MessageAdapter
import com.adil.chatapp.databinding.ActivityChatBinding
import com.adil.chatapp.model.Message
import com.adil.chatapp.model.MessageType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OTHER_UID = "extra_other_uid"
        const val EXTRA_OTHER_NAME = "extra_other_name"
        const val EXTRA_OTHER_AVATAR = "extra_other_avatar"

        // Images and audio are stored as Base64 text directly inside Realtime
        // Database (no Firebase Storage / no billing plan required). Keep
        // clips small so writes stay fast and cheap on the free Spark plan.
        private const val MAX_IMAGE_DIMENSION = 800
        private const val IMAGE_JPEG_QUALITY = 55
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var auth: FirebaseAuth
    private val messageList = mutableListOf<Message>()
    private lateinit var adapter: MessageAdapter
    private lateinit var chatId: String
    private lateinit var currentUid: String
    private lateinit var otherUid: String

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartTime: Long = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var isRecording = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { encodeAndSendImage(it) }
    }

    private val requestGalleryPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickImageLauncher.launch("image/*")
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let { encodeAndSendBitmap(it) }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePictureLauncher.launch(null)
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        currentUid = auth.currentUser?.uid ?: return
        val otherUid = intent.getStringExtra(EXTRA_OTHER_UID) ?: return
        val otherName = intent.getStringExtra(EXTRA_OTHER_NAME) ?: ""
        val otherAvatar = intent.getStringExtra(EXTRA_OTHER_AVATAR) ?: ""

        binding.tvChatWithName.text = otherName
        val avatarBitmap = if (otherAvatar.isNotEmpty()) decodeBase64ToBitmap(otherAvatar) else null
        if (avatarBitmap != null) {
            binding.ivChatAvatar.setImageBitmap(avatarBitmap)
        }
        chatId = buildChatId(currentUid, otherUid)
        this.otherUid = otherUid
        listenForOtherUserPresence(otherUid)

        adapter = MessageAdapter(messageList, currentUid)
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener { sendTextMessage() }

        binding.btnAttachImage.setOnClickListener { requestImagePick() }
        binding.btnCamera.setOnClickListener { requestCameraCapture() }

        binding.btnMic.setOnClickListener { requestAudioRecording() }
        binding.btnStopRecording.setOnClickListener { stopRecordingAndSend() }
        binding.btnCancelRecording.setOnClickListener { cancelRecording() }

        listenForMessages()
    }

    // ---------- Text ----------

    private fun sendTextMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        val message = Message(
            senderId = currentUid,
            text = text,
            timestamp = System.currentTimeMillis(),
            type = MessageType.TEXT
        )
        pushMessage(message)
        binding.etMessage.text.clear()
    }

    // ---------- Images (stored inline as Base64, no Storage bucket needed) ----------

    private fun requestImagePick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pickImageLauncher.launch("image/*")
        } else {
            requestGalleryPermission.launch(permission)
        }
    }

    private fun encodeAndSendImage(uri: Uri) {
        Toast.makeText(this, R.string.uploading, Toast.LENGTH_SHORT).show()
        thread {
            try {
                val input = contentResolver.openInputStream(uri)
                val original = BitmapFactory.decodeStream(input)
                input?.close()
                if (original == null) {
                    runOnUiThread { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                sendImageBitmap(original)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ---------- Camera (instant photo capture, stored inline as Base64) ----------

    private fun requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            takePictureLauncher.launch(null)
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun encodeAndSendBitmap(bitmap: Bitmap) {
        Toast.makeText(this, R.string.uploading, Toast.LENGTH_SHORT).show()
        thread {
            try {
                sendImageBitmap(bitmap)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** Runs on a background thread: compresses [bitmap] and pushes it as an IMAGE message. */
    private fun sendImageBitmap(bitmap: Bitmap) {
        val base64 = compressBitmapToBase64(bitmap, MAX_IMAGE_DIMENSION, IMAGE_JPEG_QUALITY)
        val message = Message(
            senderId = currentUid,
            timestamp = System.currentTimeMillis(),
            type = MessageType.IMAGE,
            mediaUrl = base64
        )
        runOnUiThread { pushMessage(message) }
    }

    // ---------- Audio (stored inline as Base64, no Storage bucket needed) ----------

    private fun requestAudioRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        recordingFile = file

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(32000)
            recorder.setAudioSamplingRate(22050)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            binding.inputBar.visibility = android.view.View.GONE
            binding.recordingBar.visibility = android.view.View.VISIBLE
            recordingHandler.post(recordingTicker)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = System.currentTimeMillis() - recordingStartTime
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / 1000) / 60
            binding.tvRecordingTime.text = String.format(Locale.getDefault(), "%d:%02d — جارِ التسجيل…", minutes, seconds)
            recordingHandler.postDelayed(this, 500)
        }
    }

    private fun stopRecordingAndSend() {
        if (!isRecording) return
        val durationMs = System.currentTimeMillis() - recordingStartTime
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            // Recording was too short or failed; ignore and clean up below.
        }
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        recordingHandler.removeCallbacks(recordingTicker)
        binding.recordingBar.visibility = android.view.View.GONE
        binding.inputBar.visibility = android.view.View.VISIBLE

        val file = recordingFile ?: return
        if (durationMs < 500) {
            file.delete()
            return
        }
        encodeAndSendAudio(file, durationMs)
    }

    private fun cancelRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            // Ignore — we are discarding this recording anyway.
        }
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        recordingHandler.removeCallbacks(recordingTicker)
        binding.recordingBar.visibility = android.view.View.GONE
        binding.inputBar.visibility = android.view.View.VISIBLE
        recordingFile?.delete()
        recordingFile = null
    }

    private fun encodeAndSendAudio(file: File, durationMs: Long) {
        Toast.makeText(this, R.string.uploading, Toast.LENGTH_SHORT).show()
        thread {
            try {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val message = Message(
                    senderId = currentUid,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.AUDIO,
                    mediaUrl = base64,
                    durationMs = durationMs
                )
                runOnUiThread { pushMessage(message) }
                file.delete()
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // ---------- Shared ----------

    private fun pushMessage(message: Message) {
        FirebaseDatabase.getInstance().reference
            .child("chats")
            .child(chatId)
            .child("messages")
            .push()
            .setValue(message)
    }

    private fun listenForMessages() {
        val messagesRef = FirebaseDatabase.getInstance().reference
            .child("chats")
            .child(chatId)
            .child("messages")
        messagesRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    messageList.clear()
                    for (child in snapshot.children) {
                        val message = child.getValue(Message::class.java) ?: continue
                        messageList.add(message)

                        // Mark incoming messages as read as soon as they're shown.
                        if (message.senderId != currentUid && !message.seen) {
                            child.ref.child("seen").setValue(true)
                        }
                    }
                    messageList.sortBy { it.timestamp }
                    adapter.notifyDataSetChanged()
                    if (messageList.isNotEmpty()) {
                        binding.rvMessages.scrollToPosition(messageList.size - 1)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Ignore for now; a production app should surface this to the user.
                }
            })
    }

    private fun listenForOtherUserPresence(otherUid: String) {
        FirebaseDatabase.getInstance().reference
            .child("users")
            .child(otherUid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                    val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                    binding.tvChatWithStatus.text = formatPresence(this@ChatActivity, isOnline, lastSeen)
                }

                override fun onCancelled(error: DatabaseError) {
                    // Ignore for now; presence is a nice-to-have, not critical.
                }
            })
    }

    override fun onResume() {
        super.onResume()
        if (::chatId.isInitialized) {
            ChatForegroundService.currentOpenChatId = chatId
        }
    }

    override fun onPause() {
        super.onPause()
        if (ChatForegroundService.currentOpenChatId == chatId) {
            ChatForegroundService.currentOpenChatId = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.releasePlayer()
        if (isRecording) {
            cancelRecording()
        }
    }
}
