package com.krystelligence.solipsism.browser.view

import com.krystelligence.solipsism.utils.Utils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the Solipsism side rail should auto-hide on scroll-down and
 * reappear on scroll-up. Pure scroll-direction logic — the activity owns the
 * actual translation/alpha animation so this stays unit-testable.
 *
 * Scroll-down past [hideThresholdPx] hides; scroll-up past it (or returning
 * near the top) shows. Duplicate events are suppressed via [isHidden].
 */
@Singleton
class RailAutoHideController @Inject constructor() {

    var onHide: (() -> Unit)? = null
    var onShow: (() -> Unit)? = null

    /** Set by the activity: false when rail is pref-hidden, wrong mode, drawers open, etc. */
    var inputEnabled: Boolean = false

    var isHidden: Boolean = false
        private set

    private var accumulatedDownPx = 0
    private var accumulatedUpPx = 0

    private val hideThresholdPx: Int = Utils.dpToPx(HIDE_THRESHOLD_DP)
    private val topResetPx: Int = Utils.dpToPx(TOP_RESET_DP)

    /**
     * Feed a WebView scroll delta. Called from [WebViewScrollCoordinator] for
     * every configured WebView so all tabs share one rail state.
     */
    fun onWebViewScroll(dy: Int, scrollY: Int) {
        if (!inputEnabled) return
        if (dy == 0) return

        // Near the top the rail must always be visible — the escape hatch.
        if (scrollY <= topResetPx) {
            accumulatedDownPx = 0
            accumulatedUpPx = 0
            if (isHidden) show() else resetAccumulators()
            return
        }

        if (dy > 0) {
            accumulatedDownPx += dy
            accumulatedUpPx = 0
            if (!isHidden && accumulatedDownPx >= hideThresholdPx) {
                hide()
            }
        } else {
            accumulatedUpPx -= dy
            accumulatedDownPx = 0
            if (isHidden && accumulatedUpPx >= hideThresholdPx) {
                show()
            }
        }
    }

    /** Force the rail visible, e.g. on tab change, drawer open, pref change. */
    fun showImmediately() {
        accumulatedDownPx = 0
        accumulatedUpPx = 0
        if (isHidden) show() else resetAccumulators()
    }

    fun resetForNewPage() {
        accumulatedDownPx = 0
        accumulatedUpPx = 0
        if (isHidden) show()
    }

    private fun hide() {
        isHidden = true
        resetAccumulators()
        onHide?.invoke()
    }

    private fun show() {
        isHidden = false
        resetAccumulators()
        onShow?.invoke()
    }

    private fun resetAccumulators() {
        accumulatedDownPx = 0
        accumulatedUpPx = 0
    }

    companion object {
        private const val HIDE_THRESHOLD_DP = 12f
        private const val TOP_RESET_DP = 8f
    }
}
