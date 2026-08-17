package com.adil.chatapp.model

object MessageType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val AUDIO = "audio"
}

data class Message(
    var senderId: String = "",
    var text: String = "",
    var timestamp: Long = 0L,
    var type: String = MessageType.TEXT,
    var mediaUrl: String = "",
    var durationMs: Long = 0L,
    var seen: Boolean = false
)
