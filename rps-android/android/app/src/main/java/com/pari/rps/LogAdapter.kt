package com.pari.rps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for match history log entries with slide-in animations.
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val entries = mutableListOf<GameLogic.LogEntry>()

    fun addEntry(entry: GameLogic.LogEntry) {
        entries.add(0, entry) // prepend (most recent first)
        notifyItemInserted(0)
    }

    fun clear() {
        entries.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])

        // Slide-in + fade animation for recently added items
        if (position == 0) {
            val animSet = AnimationSet(true)
            animSet.addAnimation(TranslateAnimation(0f, 0f, -40f, 0f).apply {
                duration = 300
            })
            animSet.addAnimation(AlphaAnimation(0f, 1f).apply {
                duration = 300
            })
            holder.itemView.startAnimation(animSet)
        }
    }

    override fun getItemCount(): Int = entries.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRound: TextView = itemView.findViewById(R.id.tvLogRound)
        private val tvResult: TextView = itemView.findViewById(R.id.tvLogResult)

        fun bind(entry: GameLogic.LogEntry) {
            tvRound.text = "R${entry.round}  ${entry.humanMove.emoji} vs ${entry.aiMove.emoji}"

            tvResult.text = entry.result.label
            val colorRes = when (entry.result) {
                GameLogic.Result.WIN -> R.color.color_win
                GameLogic.Result.LOSE -> R.color.color_lose
                GameLogic.Result.DRAW -> R.color.color_draw
            }
            tvResult.setTextColor(ContextCompat.getColor(itemView.context, colorRes))
        }
    }
}
