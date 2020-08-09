/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState.Status.CANCELLED
import mozilla.components.browser.state.state.content.DownloadState.Status.COMPLETED
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.MiddlewareStore
import mozilla.components.support.base.log.logger.Logger
import kotlin.coroutines.CoroutineContext

/**
 * [Middleware] implementation for managing downloads via the provided download service. Its
 * purpose is to react to global download state changes (e.g. of [BrowserState.downloads])
 * and notify the download service, as needed.
 */
@Suppress("ComplexMethod")
class DownloadMiddleware(
    private val applicationContext: Context,
    private val downloadServiceClass: Class<*>,
    coroutineContext: CoroutineContext = Dispatchers.IO,
    @VisibleForTesting
    internal val downloadStorage: DownloadStorage = DownloadStorage(applicationContext)
) : Middleware<BrowserState, BrowserAction> {
    private val logger = Logger("DownloadMiddleware")

    private var scope = CoroutineScope(coroutineContext)

    @InternalCoroutinesApi
    override fun invoke(
        store: MiddlewareStore<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction
    ) {
        when (action) {
            is DownloadAction.AddDownloadAction -> {
                next(action)
                scope.launch {
                    if (!action.restored) {
                        downloadStorage.add(action.download)
                        logger.debug("Added download ${action.download.fileName} to the storage")
                    }
                }
                if (action.download.status !in arrayOf(COMPLETED, CANCELLED)) {
                    val intent = Intent(applicationContext, downloadServiceClass)
                    intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, action.download.id)
                    applicationContext.startService(intent)
                    logger.debug("Sending download intent ${action.download.fileName}")
                }
            }

            is DownloadAction.RemoveDownloadAction -> {
                scope.launch {
                    store.state.downloads[action.downloadId]?.let {
                        downloadStorage.remove(it)
                        logger.debug("Removed download ${it.fileName} from the storage")
                    }
                }
            }

            is DownloadAction.UpdateDownloadAction -> {
                val updated = action.download
                store.state.downloads[updated.id]?.let { old ->
                    // To not overwhelm the storage, we only send updates that are relevant,
                    // we only care about properties, that we are stored on the storage.
                    if (!DownloadStorage.areTheSame(old, updated)) {
                        scope.launch {
                            downloadStorage.update(action.download)
                        }
                        logger.debug("Updated download ${action.download.fileName} on the storage")
                    }
                }
            }

            is DownloadAction.RestoreDownloadsState -> {
                scope.launch {
                    downloadStorage.getDownloads().collect { downloads ->
                        downloads.forEach { download ->
                            if (!store.state.downloads.containsKey(download.id)) {
                                store.dispatch(DownloadAction.AddDownloadAction(download, true))
                                logger.error("Download restarted from db")
                            }
                        }
                    }
                }
            }
        }
        if (action !is DownloadAction.AddDownloadAction) {
            next(action)
        }
    }
}
