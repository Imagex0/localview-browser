package com.krystelligence.solipsism.browser.console

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.krystelligence.solipsism.R
import com.krystelligence.solipsism.databinding.ItemConsoleEntryBinding
import java.text.DateFormat
import java.util.Date

/**
 * Log list for the in-app console panel. Entries arrive newest-last; the host
 * appends through [submit] after applying its level filter.
 */
class ConsoleLogAdapter :
    ListAdapter<ConsoleEntry, ConsoleLogAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConsoleEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemConsoleEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

        fun bind(entry: ConsoleEntry) {
            val (dot, text) = when (entry.level) {
                ConsoleLevel.ERROR -> colorOf(R.attr.colorError) to colorOf(R.attr.colorError)
                ConsoleLevel.WARNING -> colorOf(R.attr.colorTertiary) to null
                ConsoleLevel.INFO -> colorOf(R.attr.colorAccent) to null
                ConsoleLevel.COMMAND -> colorOf(R.attr.colorAccent) to colorOf(R.attr.colorAccent)
                ConsoleLevel.RESULT -> colorOf(R.attr.colorOnSurfaceVariant) to null
                ConsoleLevel.VERBOSE -> colorOf(R.attr.colorOnSurfaceVariant) to
                    colorOf(R.attr.colorOnSurfaceVariant)
            }
            ViewCompat.setBackgroundTintList(
                binding.entryDot, ColorStateList.valueOf(dot)
            )
            binding.entryMessage.text = entry.message
            // Always reset: recycled holders keep the previous row's color.
            binding.entryMessage.setTextColor(text ?: colorOf(R.attr.colorOnSurface))
            val source = buildString {
                entry.source?.let { append(it) }
                entry.line?.let { append(":$it") }
            }
            binding.entrySource.text = source
            binding.entrySource.visibility =
                if (source.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            binding.entryTime.text = timeFormat.format(Date(entry.timestamp))
        }

        private fun colorOf(attr: Int): Int =
            MaterialColors.getColor(binding.root, attr)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConsoleEntry>() {
            override fun areItemsTheSame(old: ConsoleEntry, new: ConsoleEntry): Boolean =
                old.timestamp == new.timestamp &&
                    old.tabId == new.tabId &&
                    old.message == new.message

            override fun areContentsTheSame(old: ConsoleEntry, new: ConsoleEntry): Boolean =
                old == new
        }
    }
}
