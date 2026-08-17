package com.adil.chatapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adil.chatapp.model.Message
import com.adil.chatapp.model.MessageType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase

/**
 * A foreground service that keeps a lightweight listener alive on all of the
 * current user's chats so it can show a local notification when a new
 * message arrives while the app is backgrounded.
 *
 * This is the fully-free alternative to real push notifications: sending a
 * push to a device that has fully quit requires a trusted server (a Cloud
 * Function, or a backend calling the FCM API), and Cloud Functions on
 * Firebase require the paid Blaze plan even when usage stays within the
 * free tier. Since the project must stay on the free Spark plan with no
 * billing account, this service — which only works while Android keeps the
 * app process alive in the background — is the trade-off we agreed on.
 */
class ChatForegroundService : Service() {

    companion object {
        private const val SERVICE_CHANNEL_ID = "darna_service_channel"
        private const val MESSAGES_CHANNEL_ID = "darna_messages_channel"
        private const val SERVICE_NOTIFICATION_ID = 1001

        /** Set by ChatActivity while a given chat is on-screen, so we don't notify for it. */
        @Volatile
        var currentOpenChatId: String? = null
    }

    private var currentUid: String = ""
    private var serviceStartTime: Long = 0L
    private val attachedChatIds = mutableSetOf<String>()
    private val activeListeners = mutableListOf<Pair<com.google.firebase.database.Query, ChildEventListener>>()
    private var usersListener: ChildEventListener? = null
    private var isListenerAttached = false

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = System.currentTimeMillis()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentUid = uid

        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
        watchUsersForNewChats()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.notification_service_channel_name),
            NotificationManager.IMPORTANCE_MIN
        )
        manager.createNotificationChannel(serviceChannel)

        val messagesChannel = NotificationChannel(
            MESSAGES_CHANNEL_ID,
            getString(R.string.notification_messages_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(messagesChannel)
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setSmallIcon(R.drawable.ic_profile)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** Watches the users list and attaches a message listener for every chat we're part of. */
    private fun watchUsersForNewChats() {
        // Prevent attaching the same listener multiple times if onStartCommand is called again
        if (isListenerAttached) return
        isListenerAttached = true

        val usersRef = FirebaseDatabase.getInstance().reference.child("users")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val otherUid = snapshot.key ?: return
                if (otherUid == currentUid) return
                val otherName = snapshot.child("name").getValue(String::class.java) ?: ""
                attachChatListener(otherUid, otherName)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        usersRef.addChildEventListener(listener)
        usersListener = listener
    }

    private fun attachChatListener(otherUid: String, otherName: String) {
        val chatId = buildChatId(currentUid, otherUid)
        if (!attachedChatIds.add(chatId)) return

        val messagesRef = FirebaseDatabase.getInstance().reference
            .child("chats")
            .child(chatId)
            .child("messages")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(Message::class.java) ?: return
                if (message.senderId == currentUid) return
                if (message.timestamp < serviceStartTime) return
                if (currentOpenChatId == chatId) return
                showMessageNotification(chatId, otherUid, otherName, message)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        messagesRef.addChildEventListener(listener)
        activeListeners.add(messagesRef to listener)
    }

    private fun showMessageNotification(chatId: String, otherUid: String, otherName: String, message: Message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val preview = when (message.type) {
            MessageType.IMAGE -> getString(R.string.notification_new_image)
            MessageType.AUDIO -> getString(R.string.notification_new_audio)
            else -> message.text
        }

        val openIntent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_OTHER_UID, otherUid)
            putExtra(ChatActivity.EXTRA_OTHER_NAME, otherName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, chatId.hashCode(), openIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, MESSAGES_CHANNEL_ID)
            .setContentTitle(otherName)
            .setContentText(preview)
            .setSmallIcon(R.drawable.ic_profile)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(chatId.hashCode(), notification)
        } catch (e: SecurityException) {
            // Notification permission revoked between the check and the call; ignore.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usersListener?.let {
            FirebaseDatabase.getInstance().reference.child("users").removeEventListener(it)
        }
        activeListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        activeListeners.clear()
        attachedChatIds.clear()
        isListenerAttached = false
    }
}
