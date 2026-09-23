package com.krystelligence.solipsism.browser.bookmark

import com.krystelligence.solipsism.R
import com.krystelligence.solipsism.browser.image.ImageLoader
import com.krystelligence.solipsism.database.Bookmark
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/**
 * An adapter that creates the views for bookmark list items and binds the bookmark data to them.
 *
 * @param onClick Invoked when the cell is clicked.
 * @param onLongClick Invoked when the cell is long pressed.
 * @param imageLoader The image loader needed to load favicons.
 */
class BookmarkRecyclerViewAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val imageLoader: ImageLoader,
    private val showFavicons: () -> Boolean = { true }
) : ListAdapter<Bookmark, BookmarkViewHolder>(
    object : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: Bookmark, newItem: Bookmark): Boolean =
            oldItem == newItem
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val itemView = inflater.inflate(R.layout.bookmark_list_item, parent, false)

        return BookmarkViewHolder(
            itemView,
            onItemLongClickListener = onLongClick,
            onItemClickListener = onClick
        )
    }

    /** When true, the drag handle is shown for entries so manual order can be changed. */
    var manualReorderEnabled: Boolean = false

    /** Invoked when the drag handle is touched, activity should start the drag. */
    var onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        val viewModel = getItem(position)
        holder.binding.textBookmark.text = viewModel.title

        if (showFavicons()) {
            imageLoader.loadImage(holder.binding.faviconBookmark, viewModel)
        } else {
            holder.binding.faviconBookmark.setImageResource(R.drawable.ic_action_book)
        }

        val showHandle = manualReorderEnabled && viewModel is Bookmark.Entry
        holder.binding.dragHandleBookmark.isVisible = showHandle
        holder.binding.dragHandleBookmark.setOnTouchListener { _, event ->
            if (showHandle && event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
    }

    /**
     * Moves an item in the current list for live drag feedback.
     * Returns the new ordered list, persistence is handled by the presenter on drag end.
     */
    fun moveItem(fromPosition: Int, toPosition: Int): List<Bookmark> {
        if (fromPosition == toPosition) return currentList
        val updated = currentList.toMutableList()
        if (fromPosition !in updated.indices || toPosition !in updated.indices) return currentList
        Collections.swap(updated, fromPosition, toPosition)
        submitList(updated.toList())
        return updated.toList()
    }
}
