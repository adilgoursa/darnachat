package com.adil.chatapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.adil.chatapp.adapter.UserAdapter
import com.adil.chatapp.databinding.ActivityMainBinding
import com.adil.chatapp.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private val userList = mutableListOf<User>()
    private lateinit var adapter: UserAdapter

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* If denied, the app still works — new-message notifications just won't show. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = UserAdapter(userList) { user -> openChat(user) }
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = adapter

        binding.tvLogout.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid != null) PresenceManager.markOfflineNow(uid)
            stopService(Intent(this, ChatForegroundService::class.java))
            PresenceManager.reset()
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }

        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        val uid = auth.currentUser?.uid
        if (uid != null) {
            PresenceManager.start(uid)
        }
        requestNotificationPermissionIfNeeded()
        ContextCompat.startForegroundService(this, Intent(this, ChatForegroundService::class.java))

        loadUsers()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadUsers() {
        val currentUid = auth.currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference.child("users")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    userList.clear()
                    for (child in snapshot.children) {
                        val user = child.getValue(User::class.java) ?: continue
                        if (user.uid != currentUid) {
                            userList.add(user)
                        }
                    }
                    adapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {
                    // Ignore for now; a production app should surface this to the user.
                }
            })
    }

    private fun openChat(user: User) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_OTHER_UID, user.uid)
        intent.putExtra(ChatActivity.EXTRA_OTHER_NAME, user.name)
        intent.putExtra(ChatActivity.EXTRA_OTHER_AVATAR, user.avatarBase64)
        startActivity(intent)
    }
}
