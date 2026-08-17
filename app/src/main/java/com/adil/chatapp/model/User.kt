package com.adil.chatapp.model

data class User(
    var uid: String = "",
    var name: String = "",
    var email: String = "",
    var avatarBase64: String = "",
    var isOnline: Boolean = false,
    var lastSeen: Long = 0L
)
