package com.krystelligence.solipsism.browser.console

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

/**
 * Bounded per-tab console log. Entries stream through [entries] newest-last;
 * beyond [maxEntries] the oldest are evicted so a chatty page cannot grow
 * memory without bound. Clearing on navigation is the caller's choice
 * (preserve-log toggle), not this store's.
 */
class ConsoleStore(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val lock = Any()
    private val buffered: ArrayDeque<ConsoleEntry> = ArrayDeque()
    private val subject: BehaviorSubject<List<ConsoleEntry>> =
        BehaviorSubject.createDefault(emptyList())

    /** Live snapshot stream for the log list UI. */
    fun entries(): Observable<List<ConsoleEntry>> = subject.hide()

    /** Current snapshot without subscribing. */
    fun snapshot(): List<ConsoleEntry> = synchronized(lock) { buffered.toList() }

    fun append(entry: ConsoleEntry) {
        synchronized(lock) {
            buffered.addLast(entry)
            while (buffered.size > maxEntries) {
                buffered.removeFirst()
            }
            subject.onNext(buffered.toList())
        }
    }

    fun clear() {
        synchronized(lock) {
            buffered.clear()
            subject.onNext(emptyList())
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
