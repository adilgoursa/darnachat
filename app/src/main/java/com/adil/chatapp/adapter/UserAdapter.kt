package com.adil.chatapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.adil.chatapp.databinding.ItemUserBinding
import com.adil.chatapp.decodeBase64ToBitmap
import com.adil.chatapp.formatPresence
import com.adil.chatapp.model.User

class UserAdapter(
    private val users: List<User>,
    private val onClick: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    inner class UserViewHolder(val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        holder.binding.tvUserName.text = user.name
        holder.binding.tvUserStatus.text = formatPresence(holder.binding.root.context, user.isOnline, user.lastSeen)
        holder.binding.tvUserInitial.text = if (user.name.isNotEmpty()) {
            user.name.trim().first().uppercase()
        } else {
            "?"
        }

        if (user.avatarBase64.isNotEmpty()) {
            val bitmap = decodeBase64ToBitmap(user.avatarBase64)
            if (bitmap != null) {
                holder.binding.ivUserAvatar.setImageBitmap(bitmap)
                holder.binding.ivUserAvatar.visibility = View.VISIBLE
                holder.binding.tvUserInitial.visibility = View.GONE
            } else {
                holder.binding.ivUserAvatar.visibility = View.GONE
                holder.binding.tvUserInitial.visibility = View.VISIBLE
            }
        } else {
            holder.binding.ivUserAvatar.visibility = View.GONE
            holder.binding.tvUserInitial.visibility = View.VISIBLE
        }

        holder.binding.root.setOnClickListener { onClick(user) }
    }

    override fun getItemCount(): Int = users.size
}
