package com.krystelligence.solipsism.browser.tab

import com.krystelligence.solipsism.BuildConfig
import com.krystelligence.solipsism.R
import com.krystelligence.solipsism.adblock.lists.AddListResult
import com.krystelligence.solipsism.adblock.lists.FilterListDetector
import com.krystelligence.solipsism.adblock.lists.FilterListKind
import com.krystelligence.solipsism.adblock.lists.RemoteFilterListManager
import com.krystelligence.solipsism.browser.BrowserActivity
import com.krystelligence.solipsism.browser.di.IncognitoMode
import com.krystelligence.solipsism.dialog.BrowserDialog
import com.krystelligence.solipsism.dialog.DialogItem
import com.krystelligence.solipsism.extensions.snackbar
import com.krystelligence.solipsism.extensions.toast
import com.krystelligence.solipsism.log.Logger
import com.krystelligence.solipsism.utils.IntentUtils
import com.krystelligence.solipsism.utils.NavigationSecurity
import com.krystelligence.solipsism.utils.Utils
import android.app.Activity
import android.content.Intent
import android.os.Environment
import android.net.MailTo
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import javax.inject.Inject

/**
 * Handle URLs loaded by the [WebView] and determine if they should be loaded by the browser or
 * another app.
 */
class UrlHandler @Inject constructor(
    private val activity: Activity,
    private val logger: Logger,
    private val intentUtils: IntentUtils,
    private val filterListManager: RemoteFilterListManager,
    @IncognitoMode private val incognitoMode: Boolean
) {

    /**
     * Return true if the [url] should be loaded by another app or in another way, false if the
     * browser can let the [view] continue loading as it wants.
     */
    private val trustedRoots: List<File> by lazy {
        listOf(
            File(activity.filesDir, "generated-html"),
            File(activity.filesDir, "homepage")
        )
    }

    fun shouldOverrideLoading(
        view: WebView,
        url: String,
        headers: Map<String, String>,
        isMainFrame: Boolean = true
    ): Boolean {
        if (isMainFrame && !NavigationSecurity.isTrustedInternalFileUrl(url, trustedRoots)) {
            // The generated Solipsism pages are the only pages that need file access. Reset this
            // before every other top-level navigation so a local page cannot retain the exception.
            // Skipped for subframes: touching WebSettings 24-48x per page is wasteful and
            // trips StrictMode stacks without benefit.
            view.settings.allowFileAccess = false
        }
        if (url == HISTORY_CLEAR_URL) {
            (activity as? BrowserActivity)?.clearAllHistoryFromHistoryPage()
            return true
        }
        if (url == HISTORY_DECOY_URL) {
            (activity as? BrowserActivity)?.showHistoryDecoyModePrompt()
            return true
        }
        if (url == DOWNLOADS_CLEAR_URL) {
            (activity as? BrowserActivity)?.clearAllDownloadsFromDownloadsPage()
            return true
        }
        if (url == DOWNLOADS_DECOY_URL) {
            (activity as? BrowserActivity)?.showDownloadDecoyModePrompt()
            return true
        }
        if (url.startsWith(DECOY_DOWNLOAD_URL_PREFIX)) return true
        if (incognitoMode) {
            // If we are in incognito, immediately load, we don't want the url to leave the app
            return continueLoadingUrl(view, url, headers, isMainFrame)
        }
        if (URLUtil.isAboutUrl(url)) {
            // If this is an about page, immediately load, we don't need to leave the app
            return continueLoadingUrl(view, url, headers, isMainFrame)
        }
        if (FilterListDetector.isCandidate(url)) {
            // Offer to subscribe to filter-list links instead of rendering them as text.
            showFilterListOffer(url)
            return true
        }

        return if (isMailOrIntent(url, view) || intentUtils.startActivityForUrl(view, url)) {
            // If it was a mailto: link, or an intent, or could be launched elsewhere, do that
            true
        } else {
            // If none of the special conditions was met, continue with loading the url
            continueLoadingUrl(view, url, headers, isMainFrame)
        }
    }

    /**
     * Asks whether a navigated filter-list link should be subscribed to.
     */
    private fun showFilterListOffer(url: String) {
        BrowserDialog.showPositiveNegativeDialog(
            activity,
            R.string.filter_list_detected_title,
            R.string.filter_list_detected_message,
            messageArguments = arrayOf(url),
            positiveButton = DialogItem(title = R.string.filter_list_add_button) {
                filterListManager.addList(url, ::onFilterListAdded)
            },
            negativeButton = DialogItem(title = android.R.string.cancel) {},
            onCancel = {}
        )
    }

    private fun onFilterListAdded(result: AddListResult) {
        val message = when (result) {
            is AddListResult.Added ->
                if (result.kind == FilterListKind.ABP) {
                    R.string.filter_list_added_abp
                } else {
                    R.string.filter_list_added_hosts
                }
            AddListResult.Duplicate -> R.string.filter_list_duplicate
            AddListResult.InvalidUrl,
            AddListResult.DownloadFailed -> R.string.problem_download
            AddListResult.UnrecognizedFormat -> R.string.filter_list_unrecognized
        }
        activity.toast(message)
    }

    private fun continueLoadingUrl(
        webView: WebView,
        url: String,
        headers: Map<String, String>,
        isMainFrame: Boolean = true
    ): Boolean {
        if (!NavigationSecurity.isAllowedTopLevelNavigation(url, trustedRoots)) {
            webView.stopLoading()
            return true
        }
        return when {
            // Per WebViewClient docs: do NOT call loadUrl with the same URL then
            // return true — it cancels + restarts the load. Return false instead.
            // Headers only apply to main-frame loadUrl; subframes must not reload.
            headers.isEmpty() || !isMainFrame -> false
            else -> {
                webView.loadUrl(url, headers)
                true
            }
        }
    }

    private fun isMailOrIntent(url: String, view: WebView): Boolean {
        if (url.startsWith("mailto:")) {
            val mailTo = MailTo.parse(url)
            val i = Utils.newEmailIntent(mailTo.to, mailTo.subject, mailTo.body, mailTo.cc)
            activity.startActivity(i)
            view.reload()
            return true
        } else if (url.startsWith("intent://")) {
            // All intent:// URLs must go through IntentUtils, which rejects unsafe data schemes.
            return intentUtils.startActivityForUrl(view, url)
        } else if (URLUtil.isFileUrl(url)) {
            if (NavigationSecurity.isTrustedInternalFileUrl(url, trustedRoots)) {
                return false
            }
            val path = runCatching { url.toUri().path }.getOrNull()
            val file = path?.let(::File)

            if (file?.isFile == true) {
                val downloadsRoot = runCatching {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .canonicalFile
                }.getOrNull()
                val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
                val isDownload = downloadsRoot != null && canonicalFile != null &&
                    (canonicalFile == downloadsRoot ||
                        canonicalFile.path.startsWith(downloadsRoot.path + File.separator))
                if (!isDownload) return true

                try {
                    val newMimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(Utils.guessFileExtension(file.toString()))
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    val contentUri = FileProvider.getUriForFile(
                        activity,
                        BuildConfig.APPLICATION_ID + ".fileprovider",
                        canonicalFile
                    )
                    intent.setDataAndType(contentUri, newMimeType)

                    activity.startActivity(intent)
                } catch (e: Exception) {
                    logger.log(TAG, "Unable to open downloaded file", e)
                }

            } else {
                activity.snackbar(R.string.message_open_download_fail)
            }
            return true
        }
        return false
    }

    @Deprecated("Use cached trustedRoots", ReplaceWith("trustedRoots"))
    private fun trustedInternalRoots(): List<File> = trustedRoots

    companion object {
        private const val TAG = "UrlHandler"
        private const val HISTORY_CLEAR_URL = "solipsism://clear-history"
        private const val HISTORY_DECOY_URL = "solipsism://decoy-mode"
        private const val DOWNLOADS_CLEAR_URL = "solipsism://clear-download-history"
        private const val DOWNLOADS_DECOY_URL = "solipsism://download-decoy-mode"
        private const val DECOY_DOWNLOAD_URL_PREFIX = "solipsism://decoy-download/"
    }
}
