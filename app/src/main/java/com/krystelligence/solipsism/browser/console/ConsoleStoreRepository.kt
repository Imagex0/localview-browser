package com.krystelligence.solipsism.browser.console

import com.krystelligence.solipsism.browser.engine.BrowserCore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns one [ConsoleStore] per tab id. Tabs are cheap to open and leak-prone
 * to track, so the map is bounded: beyond [MAX_TABS] the least-recently-touched
 * store is evicted. Call [release] when a tab is destroyed.
 */
@Singleton
class ConsoleStoreRepository @Inject constructor() {

    private val lock = Any()
    private val stores = LinkedHashMap<Int, Entry>(MAX_TABS, 0.75f, true)

    private data class Entry(val engine: BrowserCore, val store: ConsoleStore)

    fun storeFor(tabId: Int, engine: BrowserCore): ConsoleStore {
        synchronized(lock) {
            val existing = stores[tabId]
            if (existing != null && existing.engine == engine) {
                return existing.store
            }
            val created = ConsoleStore()
            stores[tabId] = Entry(engine, created)
            while (stores.size > MAX_TABS) {
                stores.entries.iterator().let {
                    it.next()
                    it.remove()
                }
            }
            return created
        }
    }

    fun release(tabId: Int) {
        synchronized(lock) {
            stores.remove(tabId)
        }
    }

    companion object {
        const val MAX_TABS = 50
    }
}
