package com.krystelligence.solipsism.browser.data

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.krystelligence.solipsism.R
import com.krystelligence.solipsism.databinding.DialogCookieManagerBinding
import java.net.URI

/**
 * Cookie manager as a half-page bottom sheet in the console's design language,
 * replacing the cramped popup ([CookieManagerDialog.show]). Same data and
 * rules: masked values, per-row inline editor, delete confirmations, and the
 * repository's validation — only the surface changed.
 */
class CookieManagerSheet : BottomSheetDialogFragment(), CookieListAdapter.Listener {

    interface CookieHost {
        fun cookieRepository(): CookieManagerRepository
    }

    private var binding: DialogCookieManagerBinding? = null
    private val adapter = CookieListAdapter(this)
    private var url: String = ""
    private var draftOpen: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = DialogCookieManagerBinding.inflate(inflater, container, false)
        this.binding = binding
        url = arguments?.getString(ARG_URL).orEmpty()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.cookieList.layoutManager = LinearLayoutManager(requireContext())
        binding.cookieList.adapter = adapter
        binding.cookieContext.text = siteLabel(url)
        binding.cookieClose.setOnClickListener { dismiss() }
        binding.cookieRefresh.setOnClickListener { refresh() }
        binding.cookieSearch.doAfterTextChanged { adapter.setQuery(it?.toString().orEmpty()) }
        binding.cookieAdd.setOnClickListener {
            draftOpen = true
            refresh(keepDraft = true)
        }
        binding.cookieDeleteVisible.setOnClickListener { confirmDeleteVisible() }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.let { behavior ->
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.55).toInt()
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun host(): CookieHost? = activity as? CookieHost

    private fun refresh(keepDraft: Boolean = false) {
        val binding = binding ?: return
        if (!keepDraft) {
            draftOpen = false
            adapter.collapseAll()
        }
        val repository = host()?.cookieRepository() ?: return
        val cookies = repository.listForUrl(url)
        adapter.submit(cookies, showDraft = draftOpen)
        binding.cookieEmpty.visibility =
            if (cookies.isEmpty() && !draftOpen) View.VISIBLE else View.GONE
    }

    override fun onSaveDraft(
        originalName: String?,
        name: String,
        value: String,
        domain: String,
        path: String,
        secure: Boolean,
        httpOnly: Boolean,
        sameSite: String
    ) {
        val repository = host()?.cookieRepository() ?: return
        // Blank names go through repository validation like the old editor,
        // surfacing its message instead of inventing a new one.
        val draft = CookieDraft(
            name = name.trim(),
            value = value,
            domain = domain.trim().ifEmpty { null },
            path = path.trim().ifEmpty { "/" },
            secure = secure,
            httpOnly = httpOnly,
            sameSite = sameSite.trim().ifEmpty { null }
        )
        repository.set(url, draft) { result ->
            activity?.runOnUiThread {
                if (result is CookieOperationResult.Failure) {
                    toast(result.reason)
                } else {
                    draftOpen = false
                    refresh()
                }
            }
        }
    }

    override fun onDelete(cookie: BrowserCookie) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cookie_manager_delete)
            .setMessage(R.string.cookie_manager_delete_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_yes) { _, _ ->
                host()?.cookieRepository()?.delete(url, cookie.name) { result ->
                    activity?.runOnUiThread {
                        if (result is CookieOperationResult.Failure) {
                            toast(result.reason)
                        }
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteVisible() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cookie_manager_delete_visible)
            .setMessage(R.string.cookie_manager_delete_visible_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_yes) { _, _ ->
                host()?.cookieRepository()?.deleteVisibleCookies(url) { result ->
                    activity?.runOnUiThread {
                        if (result is CookieOperationResult.Failure) {
                            toast(result.reason)
                        }
                        draftOpen = false
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun siteLabel(url: String): String =
        runCatching { URI(url).host ?: url }.getOrDefault(url)

    companion object {
        private const val ARG_URL = "cookie_manager_url"

        fun newInstance(url: String) = CookieManagerSheet().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}
