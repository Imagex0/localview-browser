package com.krystelligence.solipsism.browser.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.krystelligence.solipsism.R
import com.krystelligence.solipsism.databinding.ItemCookieBinding

/**
 * Cookie rows for the manager sheet. Name filtering is a pure function
 * ([filterCookies]) so it stays unit-testable; values stay masked until a
 * row is expanded for editing, matching the popup's privacy behavior.
 */
class CookieListAdapter(
    private val listener: Listener
) : ListAdapter<CookieListAdapter.Row, CookieListAdapter.ViewHolder>(DIFF) {

    interface Listener {
        fun onSaveDraft(
            originalName: String?,
            name: String,
            value: String,
            domain: String,
            path: String,
            secure: Boolean,
            httpOnly: Boolean,
            sameSite: String
        )

        fun onDelete(cookie: BrowserCookie)
    }

    sealed interface Row {
        data class Entry(val cookie: BrowserCookie) : Row
        data object Draft : Row
    }

    private var full: List<Row> = emptyList()
    private var query: String = ""
    private var expandedName: String? = null
    private var draftOpen: Boolean = false

    fun submit(cookies: List<BrowserCookie>, showDraft: Boolean) {
        full = cookies.map(Row::Entry)
        draftOpen = showDraft
        applyFilter()
    }

    fun setQuery(raw: String) {
        query = raw.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        val rows = full.filter { row ->
            row is Row.Draft || query.isBlank() ||
                (row as Row.Entry).cookie.name.lowercase().contains(query)
        }.let { filtered ->
            if (draftOpen) listOf(Row.Draft) + filtered else filtered
        }
        submitList(rows)
    }

    fun closeDraft() {
        draftOpen = false
        applyFilter()
    }

    fun collapseAll() {
        expandedName = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCookieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCookieBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            when (row) {
                is Row.Draft -> bindDraft()
                is Row.Entry -> bindEntry(row.cookie)
            }
        }

        private fun bindEntry(cookie: BrowserCookie) {
            val editing = expandedName == cookie.name
            binding.cookieName.text = cookie.name
            binding.cookieValue.text = mask(cookie.value)
            binding.cookieValue.visibility = if (editing) View.GONE else View.VISIBLE
            binding.cookieEditor.visibility = if (editing) View.VISIBLE else View.GONE
            if (editing) {
                binding.cookieFieldName.setText(cookie.name)
                binding.cookieFieldValue.setText(cookie.value)
            }
            binding.cookieEdit.setOnClickListener {
                expandedName = if (expandedName == cookie.name) null else cookie.name
                notifyItemChanged(bindingAdapterPosition)
            }
            binding.cookieDelete.setOnClickListener { listener.onDelete(cookie) }
            binding.cookieSave.setOnClickListener {
                listener.onSaveDraft(
                    originalName = cookie.name,
                    name = binding.cookieFieldName.text?.toString().orEmpty(),
                    value = binding.cookieFieldValue.text?.toString().orEmpty(),
                    domain = binding.cookieFieldDomain.text?.toString().orEmpty(),
                    path = binding.cookieFieldPath.text?.toString().orEmpty(),
                    secure = binding.cookieFieldSecure.isChecked,
                    httpOnly = binding.cookieFieldHttpOnly.isChecked,
                    sameSite = binding.cookieFieldSameSite.text?.toString().orEmpty()
                )
            }
        }

        private fun bindDraft() {
            binding.cookieName.setText(R.string.cookie_manager_add)
            binding.cookieValue.visibility = View.GONE
            binding.cookieEditor.visibility = View.VISIBLE
            binding.cookieFieldName.setText("")
            binding.cookieFieldValue.setText("")
            binding.cookieFieldDomain.setText("")
            binding.cookieFieldPath.setText("/")
            binding.cookieFieldSecure.isChecked = false
            binding.cookieFieldHttpOnly.isChecked = false
            binding.cookieFieldSameSite.setText("")
            binding.cookieSave.setOnClickListener {
                listener.onSaveDraft(
                    originalName = null,
                    name = binding.cookieFieldName.text?.toString().orEmpty(),
                    value = binding.cookieFieldValue.text?.toString().orEmpty(),
                    domain = binding.cookieFieldDomain.text?.toString().orEmpty(),
                    path = binding.cookieFieldPath.text?.toString().orEmpty(),
                    secure = binding.cookieFieldSecure.isChecked,
                    httpOnly = binding.cookieFieldHttpOnly.isChecked,
                    sameSite = binding.cookieFieldSameSite.text?.toString().orEmpty()
                )
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(old: Row, new: Row): Boolean = when {
                old is Row.Entry && new is Row.Entry -> old.cookie.name == new.cookie.name
                old is Row.Draft && new is Row.Draft -> true
                else -> false
            }

            override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
        }

        fun mask(value: String): String =
            if (value.isEmpty()) "(empty)" else "•••• (${value.length} characters)"

        fun filterCookies(cookies: List<BrowserCookie>, query: String): List<BrowserCookie> {
            val trimmed = query.trim().lowercase()
            if (trimmed.isBlank()) return cookies
            return cookies.filter { it.name.lowercase().contains(trimmed) }
        }
    }
}
