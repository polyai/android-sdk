// Copyright PolyAI Limited

package ai.poly.examples.hello.views

import ai.poly.examples.hello.views.databinding.ActivityChatBinding
import ai.poly.examples.hello.views.databinding.ItemMessageBinding
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.Delivery
import ai.poly.messaging.PolyMessaging
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Rung 01 (Views) — `chat()`, render `session.messages` in a RecyclerView, `send()`.
 * The classic-Views `01-Hello` example.
 */
class ChatActivity : ComponentActivity() {

    private lateinit var binding: ActivityChatBinding
    private val session: ChatSession by lazy { PolyMessaging.chat() }
    private val adapter = MessageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep the composer above the gesture nav bar (closed) / keyboard (open) by
        // applying window insets. The app draws edge-to-edge under targetSdk 36.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            v.updatePadding(bottom = bottom)
            insets
        }

        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter

        binding.send.setOnClickListener {
            val text = binding.composer.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.composer.setText("")
                lifecycleScope.launch { runCatching { session.send(text) } }
            }
        }

        // Collect SDK state, lifecycle-aware.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.messages.collect { messages ->
                        adapter.submit(messages)
                        if (messages.isNotEmpty()) binding.list.scrollToPosition(messages.size - 1)
                    }
                }
                launch {
                    session.connection.collect { status ->
                        binding.banner.visibility = if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
        private val items = mutableListOf<ChatMessage>()

        fun submit(newItems: List<ChatMessage>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        class Holder(private val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(message: ChatMessage) {
                val params = b.bubble.layoutParams as LinearLayout.LayoutParams
                when (message) {
                    is ChatMessage.User -> {
                        b.bubble.text = message.message.text
                        b.bubble.setBackgroundColor(Color.parseColor("#2563EB"))
                        b.bubble.setTextColor(Color.WHITE)
                        b.bubble.alpha = if (message.message.delivery == Delivery.PENDING) 0.5f else 1f
                        b.root.gravity = Gravity.END
                        b.bubble.setTypeface(null, Typeface.NORMAL)
                    }
                    is ChatMessage.Agent -> {
                        b.bubble.text = message.message.text
                        b.bubble.setBackgroundColor(Color.parseColor("#E5E7EB"))
                        b.bubble.setTextColor(Color.BLACK)
                        b.bubble.alpha = 1f
                        b.root.gravity = Gravity.START
                    }
                    is ChatMessage.System -> {
                        b.bubble.text = message.message.event.reason ?: "—"
                        b.bubble.setBackgroundColor(Color.TRANSPARENT)
                        b.bubble.setTextColor(Color.GRAY)
                        b.root.gravity = Gravity.CENTER
                    }
                }
                b.bubble.layoutParams = params
            }
        }
    }
}
