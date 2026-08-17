package com.adil.chatapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Builds a deterministic, unique chat room id for two users so that both
 * users always resolve to the same conversation node in the database,
 * regardless of who opens the chat first.
 */
fun buildChatId(uid1: String, uid2: String): String {
    return if (uid1 < uid2) "${uid1}_$uid2" else "${uid2}_$uid1"
}

/**
 * Scales [bitmap] down (if needed) so its longest side is at most
 * [maxDimension], JPEG-compresses it at [quality], and returns the result as
 * a Base64 string. Used to store images/avatars directly inside Realtime
 * Database as text, avoiding any need for Firebase Storage / a billing plan.
 */
fun compressBitmapToBase64(bitmap: Bitmap, maxDimension: Int, quality: Int): String {
    val scale = minOf(
        maxDimension.toFloat() / bitmap.width,
        maxDimension.toFloat() / bitmap.height,
        1f
    )
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else {
        bitmap
    }
    val baos = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
}

/** Decodes a Base64 payload (as produced by [compressBitmapToBase64]) back into a Bitmap. */
fun decodeBase64ToBitmap(base64: String): Bitmap? {
    if (base64.isEmpty()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

/**
 * Formats a presence state (online flag + last-seen timestamp) into a short
 * human-readable Arabic status string, e.g. "متصل الآن" or "آخر ظهور قبل 5 د".
 */
fun formatPresence(context: android.content.Context, isOnline: Boolean, lastSeen: Long): String {
    if (isOnline) return context.getString(com.adil.chatapp.R.string.status_online)
    if (lastSeen <= 0L) return context.getString(com.adil.chatapp.R.string.status_offline)

    val elapsedMs = System.currentTimeMillis() - lastSeen
    val minutes = elapsedMs / 60000
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 1 -> context.getString(com.adil.chatapp.R.string.status_last_seen_just_now)
        minutes < 60 -> context.getString(com.adil.chatapp.R.string.status_last_seen_minutes, minutes.toInt())
        hours < 24 -> context.getString(com.adil.chatapp.R.string.status_last_seen_hours, hours.toInt())
        else -> {
            val sdf = java.text.SimpleDateFormat("d/M HH:mm", java.util.Locale.getDefault())
            context.getString(com.adil.chatapp.R.string.status_last_seen, sdf.format(java.util.Date(lastSeen)))
        }
    }
}
