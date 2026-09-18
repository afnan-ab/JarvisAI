package com.jarvis.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: TextView = view.findViewById(R.id.bubbleText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_message_user else R.layout.item_message_jarvis
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun getItemViewType(position: Int): Int = if (messages[position].isUser) 1 else 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bubble.text = messages[position].text
    }

    override fun getItemCount() = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }
}
