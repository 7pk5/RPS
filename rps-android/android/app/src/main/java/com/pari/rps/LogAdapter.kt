package com.pari.rps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for match history log entries.
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val entries = mutableListOf<GameLogic.LogEntry>()

    fun addEntry(entry: GameLogic.LogEntry) {
        entries.add(0, entry) // prepend (most recent first)
        notifyItemInserted(0)
    }

    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])
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
