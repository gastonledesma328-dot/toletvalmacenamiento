package com.toletv.app.ui.adapters

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.toletv.app.R
import com.toletv.app.data.model.Channel

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Channel) -> Unit = {},                       // compat: igual que antes
    private val onChannelClickWithIndex: ((Channel, Int) -> Unit)? = null,    // opcional
    private val onChannelFocused: ((Channel) -> Unit)? = null,                // SOLO efectos visuales
    private val onChannelLongClick: ((Channel, Int) -> Unit)? = null          // 👈 NUEVO: variantes al mantener 2s
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    companion object {
        private const val LONG_PRESS_MS = 2000L // 2 segundos exactos
        private const val CLICK_DEBOUNCE_MS = 350L
    }

    init { setHasStableIds(true) }

    class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val channelLogo: ImageView = itemView.findViewById(R.id.ivChannelLogo)
        val channelName: TextView = itemView.findViewById(R.id.tvChannelName)
        val overlayView: View? = itemView.findViewById(R.id.overlayView)

        // Estado para click / long-press
        var lastClickTime: Long = 0L
        var longPressTriggered: Boolean = false
        var pointerDown: Boolean = false
        var downX: Float = 0f
        var downY: Float = 0f

        val mainHandler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
    }

    override fun getItemId(position: Int): Long {
        val id = channels[position].id ?: channels[position].name
        return id.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        // Asegura navegación por TV
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]

        holder.channelName.text = channel.name
        holder.itemView.contentDescription = channel.name

        val img = safeUrl(channel.logoUrl)
        val opts = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontAnimate()
            .placeholder(R.drawable.ic_play)
            .error(R.drawable.ic_play)

        Glide.with(holder.itemView.context)
            .load(img)
            .apply(opts)
            .thumbnail(0.2f)
            .into(holder.channelLogo)

        // —————————————————————————————————————————————
        // CLICK corto con debounce (no interfiere con long-press)
        // —————————————————————————————————————————————
        holder.itemView.setOnClickListener {
            // si el long-press ya se disparó, no ejecutar click
            if (holder.longPressTriggered) return@setOnClickListener

            val now = System.currentTimeMillis()
            if (now - holder.lastClickTime < CLICK_DEBOUNCE_MS) return@setOnClickListener
            holder.lastClickTime = now

            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                val ch = channels[idx]
                onChannelClickWithIndex?.invoke(ch, idx) ?: onChannelClick(ch)
            }
        }

        // —————————————————————————————————————————————
        // TOUCH: long-press manual exacto de 2000ms
        // —————————————————————————————————————————————
        holder.itemView.setOnTouchListener { v, event ->
            val vc = ViewConfiguration.get(v.context)
            val slop = vc.scaledTouchSlop

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holder.pointerDown = true
                    holder.longPressTriggered = false
                    holder.downX = event.x
                    holder.downY = event.y

                    holder.longPressRunnable?.let { holder.mainHandler.removeCallbacks(it) }
                    holder.longPressRunnable = Runnable {
                        if (holder.pointerDown && !holder.longPressTriggered) {
                            val idx = holder.bindingAdapterPosition
                            if (idx != RecyclerView.NO_POSITION) {
                                holder.longPressTriggered = true
                                onChannelLongClick?.invoke(channels[idx], idx)
                            }
                        }
                    }.also { holder.mainHandler.postDelayed(it, LONG_PRESS_MS) }
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - holder.downX)
                    val dy = (event.y - holder.downY)
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        // se movió demasiado: cancela long
                        cancelLongPress(holder)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holder.pointerDown = false
                    // si no se disparó long-press, dejamos que el click normal siga su curso (performClick ya está arriba)
                    cancelLongPress(holder)
                }
            }
            // Importante: devolver false para no consumir y permitir focus/selector de TV
            false
        }

        // —————————————————————————————————————————————
        // CONTROL REMOTO: long-press con OK/ENTER por 2000ms
        // —————————————————————————————————————————————
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        // cuando comienza la pulsación, programamos long-press a 2s
                        if (event.repeatCount == 0) {
                            holder.longPressTriggered = false
                            holder.longPressRunnable?.let { holder.mainHandler.removeCallbacks(it) }
                            holder.longPressRunnable = Runnable {
                                if (!holder.longPressTriggered) {
                                    val idx = holder.bindingAdapterPosition
                                    if (idx != RecyclerView.NO_POSITION) {
                                        holder.longPressTriggered = true
                                        onChannelLongClick?.invoke(channels[idx], idx)
                                    }
                                }
                            }.also { holder.mainHandler.postDelayed(it, LONG_PRESS_MS) }
                        }
                        // si mantiene presionado y el sistema empieza a repetir eventos,
                        // seguimos esperando hasta los 2000ms; no consumimos todavía
                        false
                    }
                    KeyEvent.ACTION_UP -> {
                        // si NO hubo long-press, dejamos que el click normal ocurra
                        val wasLong = holder.longPressTriggered
                        cancelLongPress(holder)
                        // Si fue long, ya manejamos arriba, consumimos el UP
                        wasLong
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        // —————————————————————————————————————————————
        // FOCO: SOLO efectos visuales (NO reproducir ni hacer performClick)
        // —————————————————————————————————————————————
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.06f else 1f
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(120L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            holder.overlayView?.let { overlay ->
                if (hasFocus) {
                    overlay.visibility = View.VISIBLE
                    overlay.setBackgroundColor(
                        ContextCompat.getColor(v.context, R.color.focus_background)
                    )
                    overlay.animate().alpha(0.15f).setDuration(120L).start()
                } else {
                    overlay.animate().alpha(0f).setDuration(120L).withEndAction {
                        overlay.visibility = View.GONE
                    }.start()
                }
            }

            // Hint opcional:
            // if (hasFocus) v.tooltipText = "Mantener OK 2s: Variantes" else v.tooltipText = null

            // Si querés usarlo solo para UI:
            // if (hasFocus) onChannelFocused?.invoke(channel)
        }
    }

    override fun getItemCount(): Int = channels.size

    fun updateChannels(newChannels: List<Channel>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = channels.size
            override fun getNewListSize() = newChannels.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = channels[oldPos]
                val new = newChannels[newPos]
                val oldId = old.id ?: old.name
                val newId = new.id ?: new.name
                return oldId == newId
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = channels[oldPos]
                val new = newChannels[newPos]
                return old.name == new.name &&
                        old.logoUrl == new.logoUrl &&
                        old.streamUrl == new.streamUrl &&
                        old.streamType == new.streamType &&
                        old.description == new.description
            }
        })
        channels = newChannels
        diff.dispatchUpdatesTo(this)
    }

    // Útil para leer la fila actual desde afuera
    fun getChannels(): List<Channel> = channels

    private fun safeUrl(url: String?): String? {
        val u = url?.trim().takeUnless { it.isNullOrEmpty() || it.equals("null", true) }
        return when {
            u == null -> null
            u.startsWith("http://") -> u.replaceFirst("http://", "https://")
            else -> u
        }
    }

    private fun cancelLongPress(holder: ChannelViewHolder) {
        holder.longPressRunnable?.let { holder.mainHandler.removeCallbacks(it) }
        holder.longPressRunnable = null
    }
}
