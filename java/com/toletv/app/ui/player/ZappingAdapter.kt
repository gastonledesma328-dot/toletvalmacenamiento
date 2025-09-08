package com.toletv.app.ui.player

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.toletv.app.R
import com.toletv.app.data.model.ZappingChannel

class ZappingAdapter(
    private var items: List<ZappingChannel>,
    private val onOpen: (Int, ZappingChannel) -> Unit
) : RecyclerView.Adapter<ZappingAdapter.VH>() {

    companion object {
        private const val CLICK_DEBOUNCE_MS = 350L
        private const val FOCUS_ARM_MS = 120L
    }

    var selected: Int = 0
        private set

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        items[position].id.hashCode().toLong() // asumimos id o nombre estable

    fun submit(newList: List<ZappingChannel>, keepSelection: Boolean = true) {
        items = newList
        if (!keepSelection) selected = 0
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index !in items.indices) return
        val old = selected
        selected = index
        if (old != selected) {
            notifyItemChanged(old, "state")
            notifyItemChanged(selected, "state")
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgLogo)
        val txt: TextView = v.findViewById(R.id.txtName)
        var lastClick: Long = 0L
        var focusGainedAt: Long = 0L

        init {
            // Click protegido: requiere foco, FOCUS_ARM y debounce
            v.setOnClickListener { view ->
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                val now = SystemClock.elapsedRealtime()

                if (!view.isFocused) {
                    view.requestFocus()
                    return@setOnClickListener
                }
                if (now - focusGainedAt < FOCUS_ARM_MS) return@setOnClickListener
                if (now - lastClick < CLICK_DEBOUNCE_MS) return@setOnClickListener

                lastClick = now
                onOpen(pos, items[pos])
            }

            // D-pad estable (UP/DOWN)
            v.setOnKeyListener { vv, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

                val rv = vv.parent as? RecyclerView ?: return@setOnKeyListener false
                val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
                val posNow = bindingAdapterPosition
                if (posNow == RecyclerView.NO_POSITION) return@setOnKeyListener false

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val target = (posNow - 1).coerceAtLeast(0)
                        moveFocus(rv, lm, target)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val target = (posNow + 1).coerceAtMost(itemCount - 1)
                        moveFocus(rv, lm, target)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        // Misma protección que el click
                        val now = SystemClock.elapsedRealtime()
                        if (!vv.isFocused) {
                            vv.requestFocus(); return@setOnKeyListener true
                        }
                        if (now - focusGainedAt < FOCUS_ARM_MS) return@setOnKeyListener true
                        if (now - lastClick < CLICK_DEBOUNCE_MS) return@setOnKeyListener true

                        lastClick = now
                        val p = bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) onOpen(p, items[p])
                        return@setOnKeyListener true
                    }
                }
                false
            }

            v.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusGainedAt = SystemClock.elapsedRealtime()
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        val old = selected
                        selected = p
                        if (old != selected) {
                            notifyItemChanged(old, "state")
                            notifyItemChanged(selected, "state")
                        }
                    }
                }
            }
        }

        private fun moveFocus(rv: RecyclerView, lm: LinearLayoutManager, target: Int) {
            if (target !in 0 until itemCount) return
            select(target)
            lm.smoothScrollToPosition(rv, RecyclerView.State(), target)
            rv.post {
                rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_zap, parent, false)
        v.isFocusable = true
        v.isClickable = true
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.txt.text = it.name
        Glide.with(h.itemView).load(it.logo).into(h.img)
        applySelectedStyle(h, pos == selected)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("state")) {
            applySelectedStyle(holder, position == selected)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun applySelectedStyle(h: VH, isSelected: Boolean) {
        h.itemView.alpha = if (isSelected) 1f else 0.6f
        h.itemView.scaleX = if (isSelected) 1.06f else 1.0f
        h.itemView.scaleY = if (isSelected) 1.06f else 1.0f
    }
}
