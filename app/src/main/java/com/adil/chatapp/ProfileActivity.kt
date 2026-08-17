package com.adil.chatapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.adil.chatapp.databinding.ActivityProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlin.concurrent.thread

class ProfileActivity : AppCompatActivity() {

    companion object {
        // Avatars are shown small and in lists, so keep them lighter than
        // in-chat photos to keep the users/ node fast to read.
        private const val MAX_AVATAR_DIMENSION = 300
        private const val AVATAR_JPEG_QUALITY = 65
    }

    private lateinit var binding: ActivityProfileBinding
    private lateinit var auth: FirebaseAuth
    private var currentAvatarBase64: String = ""

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadAndApplyAvatarFromUri(it) }
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

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let { applyAvatarBitmap(it) }
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
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            finish()
            return
        }

        binding.tvChangePhoto.setOnClickListener { showPhotoSourceDialog() }
        binding.btnSaveProfile.setOnClickListener { saveProfile() }

        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        val uid = auth.currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference
            .child("users")
            .child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val name = snapshot.child("name").getValue(String::class.java) ?: ""
                    val avatar = snapshot.child("avatarBase64").getValue(String::class.java) ?: ""
                    binding.etProfileName.setText(name)
                    binding.tvProfileInitial.text = if (name.isNotEmpty()) {
                        name.trim().first().uppercase()
                    } else {
                        "?"
                    }
                    currentAvatarBase64 = avatar
                    if (avatar.isNotEmpty()) {
                        val bitmap = decodeBase64ToBitmap(avatar)
                        if (bitmap != null) {
                            binding.ivProfileAvatar.setImageBitmap(bitmap)
                            binding.ivProfileAvatar.visibility = View.VISIBLE
                            binding.tvProfileInitial.visibility = View.GONE
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Ignore for now; a production app should surface this to the user.
                }
            })
    }

    private fun showPhotoSourceDialog() {
        val options = arrayOf(getString(R.string.take_photo), getString(R.string.choose_from_gallery))
        AlertDialog.Builder(this)
            .setTitle(R.string.change_photo)
            .setItems(options) { _, which ->
                if (which == 0) requestCameraCapture() else requestImagePick()
            }
            .show()
    }

    private fun requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            takePictureLauncher.launch(null)
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

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

    private fun loadAndApplyAvatarFromUri(uri: Uri) {
        thread {
            try {
                val input = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(input)
                input?.close()
                if (bitmap != null) {
                    runOnUiThread { applyAvatarBitmap(bitmap) }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun applyAvatarBitmap(bitmap: Bitmap) {
        binding.ivProfileAvatar.setImageBitmap(bitmap)
        binding.ivProfileAvatar.visibility = View.VISIBLE
        binding.tvProfileInitial.visibility = View.GONE
        // Compressing a small (<=300px) bitmap is fast enough to do inline,
        // and doing it synchronously avoids a race with saveProfile() reading
        // currentAvatarBase64 before a background thread finishes.
        currentAvatarBase64 = compressBitmapToBase64(bitmap, MAX_AVATAR_DIMENSION, AVATAR_JPEG_QUALITY)
    }

    private fun saveProfile() {
        val uid = auth.currentUser?.uid ?: return
        val name = binding.etProfileName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        val updates = mapOf<String, Any>(
            "name" to name,
            "avatarBase64" to currentAvatarBase64
        )
        FirebaseDatabase.getInstance().reference
            .child("users")
            .child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                setLoading(false)
                Toast.makeText(this, R.string.name_updated, Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                setLoading(false)
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
    }

    private fun setLoading(loading: Boolean) {
        binding.profileProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSaveProfile.isEnabled = !loading
    }
}
