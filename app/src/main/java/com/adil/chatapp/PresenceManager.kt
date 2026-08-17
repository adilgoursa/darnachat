package com.adil.chatapp

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Tracks the current user's online/offline presence using Realtime
 * Database's built-in ".info/connected" special path + onDisconnect(),
 * a standard free (Spark-plan) pattern that needs no Cloud Functions.
 */
object PresenceManager {

    private var started = false

    fun start(uid: String) {
        if (started) return
        started = true

        val userStatusRef = FirebaseDatabase.getInstance().reference
            .child("users")
            .child(uid)
        val connectedRef = FirebaseDatabase.getInstance().reference.child(".info/connected")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    // If the connection drops (app killed, network lost), let the
                    // server flip us to offline automatically.
                    userStatusRef.child("isOnline").onDisconnect().setValue(false)
                    userStatusRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)

                    userStatusRef.child("isOnline").setValue(true)
                    userStatusRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Ignore; presence is a nice-to-have, not critical.
            }
        })
    }

    /** Explicitly mark the user offline right away (e.g. on manual logout). */
    fun markOfflineNow(uid: String) {
        val userStatusRef = FirebaseDatabase.getInstance().reference
            .child("users")
            .child(uid)
        userStatusRef.child("isOnline").setValue(false)
        userStatusRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
    }

    fun reset() {
        started = false
    }
}
