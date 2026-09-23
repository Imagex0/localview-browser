package com.krystelligence.solipsism.browser.console

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.krystelligence.solipsism.R
import com.krystelligence.solipsism.browser.engine.BrowserCore
import com.krystelligence.solipsism.databinding.DialogConsoleBinding
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable

/**
 * In-app console panel (accepted prototype `prototypes/browser-console.html`):
 * filterable log list for one tab plus a REPL input row.
 *
 * Data comes from the per-tab [ConsoleStore]; the host activity supplies tab
 * switching and evaluation through [ConsoleHost] so the sheet stays free of
 * browser wiring.
 */
class ConsoleBottomSheet : BottomSheetDialogFragment() {

    interface ConsoleHost {
        data class TabRef(val tabId: Int, val engine: BrowserCore, val label: String)

        /** Open tabs for the context chip, selected tab first. */
        fun consoleTabs(): List<TabRef>

        fun consoleStore(tabId: Int): ConsoleStore?

        /** Returns false when the tab's engine cannot evaluate with a result. */
        fun evaluateConsole(tabId: Int, code: String): Boolean
    }

    private var binding: DialogConsoleBinding? = null
    private val disposables = CompositeDisposable()
    private val adapter = ConsoleLogAdapter()
    private val visibleLevels = mutableSetOf(
        ConsoleLevel.ERROR, ConsoleLevel.WARNING, ConsoleLevel.INFO
    )
    private var tabId: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = DialogConsoleBinding.inflate(inflater, container, false)
        this.binding = binding
        tabId = arguments?.getInt(ARG_TAB_ID, -1) ?: -1
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        binding.consoleList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.consoleList.adapter = adapter
        binding.consoleClose.setOnClickListener { dismiss() }
        binding.consoleClear.setOnClickListener { host()?.consoleStore(tabId)?.clear() }
        binding.consoleContext.setOnClickListener { cycleTab() }
        binding.consoleRun.setOnClickListener { submit() }
        binding.consoleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }
        rebuildChips()
        subscribe()
    }

    override fun onStart() {
        super.onStart()
        // Prototype height: a peek sheet, expandable to full.
        (dialog as? BottomSheetDialog)?.behavior?.let { behavior ->
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.55).toInt()
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    override fun onDestroyView() {
        disposables.clear()
        binding = null
        super.onDestroyView()
    }

    private fun host(): ConsoleHost? = activity as? ConsoleHost

    private fun rebuildChips() {
        val binding = binding ?: return
        val group = binding.consoleLevels
        group.removeAllViews()
        chipSpec().forEach { (level, label) ->
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = level == null || visibleLevels.contains(level)
                setOnCheckedChangeListener { _, checked ->
                    if (level == null) {
                        if (checked) visibleLevels.addAll(commandLevels()) else visibleLevels.clear()
                    } else if (checked) {
                        visibleLevels.add(level)
                    } else {
                        visibleLevels.remove(level)
                    }
                    subscribe()
                }
            }
            group.addView(chip)
        }
    }

    private fun chipSpec(): List<Pair<ConsoleLevel?, String>> {
        val counts = host()?.consoleStore(tabId)?.snapshot().orEmpty()
            .groupingBy(ConsoleEntry::level).eachCount()
        fun count(level: ConsoleLevel) = counts[level]?.takeIf { it > 0 }?.let { " $it" }.orEmpty()
        return listOf(
            null to getString(R.string.console_filter_all),
            ConsoleLevel.ERROR to getString(R.string.console_filter_errors) + count(ConsoleLevel.ERROR),
            ConsoleLevel.WARNING to getString(R.string.console_filter_warnings) + count(ConsoleLevel.WARNING),
            ConsoleLevel.INFO to getString(R.string.console_filter_info) + count(ConsoleLevel.INFO),
            ConsoleLevel.VERBOSE to getString(R.string.console_filter_verbose)
        )
    }

    private fun commandLevels(): Set<ConsoleLevel> =
        setOf(ConsoleLevel.ERROR, ConsoleLevel.WARNING, ConsoleLevel.INFO, ConsoleLevel.VERBOSE)

    private fun subscribe() {
        disposables.clear()
        val binding = binding ?: return
        val store = host()?.consoleStore(tabId) ?: return
        updateContext()
        disposables.add(
            store.entries()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { entries ->
                    val visible = entries.filter {
                        visibleLevels.contains(it.level) ||
                            it.level == ConsoleLevel.COMMAND ||
                            it.level == ConsoleLevel.RESULT
                    }
                    adapter.submitList(visible)
                    binding.consoleEmpty.visibility =
                        if (visible.isEmpty()) View.VISIBLE else View.GONE
                    if (visible.isNotEmpty()) {
                        binding.consoleList.scrollToPosition(visible.size - 1)
                    }
                }
        )
    }

    private fun updateContext() {
        val binding = binding ?: return
        val tabs = host()?.consoleTabs().orEmpty()
        val current = tabs.firstOrNull { it.tabId == tabId }
        binding.consoleContext.text = if (current != null) {
            getString(
                R.string.console_context,
                current.engine.name.lowercase().replaceFirstChar(Char::titlecase),
                current.label
            )
        } else {
            getString(R.string.action_console)
        }
    }

    private fun cycleTab() {
        val tabs = host()?.consoleTabs().orEmpty()
        if (tabs.size < 2) return
        val index = tabs.indexOfFirst { it.tabId == tabId }.takeIf { it >= 0 } ?: 0
        tabId = tabs[(index + 1) % tabs.size].tabId
        rebuildChips()
        subscribe()
    }

    private fun submit() {
        val binding = binding ?: return
        val code = binding.consoleInput.text?.toString().orEmpty().trim()
        if (code.isEmpty()) return
        binding.consoleInput.text?.clear()
        val supported = host()?.evaluateConsole(tabId, code) ?: false
        if (!supported) {
            Toast.makeText(requireContext(), R.string.console_unsupported_engine, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_TAB_ID = "console_tab_id"

        fun newInstance(tabId: Int) = ConsoleBottomSheet().apply {
            arguments = Bundle().apply { putInt(ARG_TAB_ID, tabId) }
        }
    }
}
