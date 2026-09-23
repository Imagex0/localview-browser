package com.krystelligence.solipsism.browser.console

import com.krystelligence.solipsism.browser.engine.BrowserCore
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test

class ConsoleStoreTest {

    private lateinit var store: ConsoleStore

    private fun entry(message: String, level: ConsoleLevel = ConsoleLevel.INFO) =
        ConsoleEntry(
            engine = BrowserCore.WEBVIEW,
            tabId = 7,
            url = "https://example.com/",
            level = level,
            message = message,
            source = "example.com",
            line = 3,
            timestamp = 42L
        )

    @Before
    fun setUp() {
        store = ConsoleStore(maxEntries = 3)
    }

    @Test
    fun `starts empty`() {
        assertThat(store.snapshot()).isEmpty()
    }

    @Test
    fun `appends newest-last and streams snapshots`() {
        val observed = mutableListOf<List<ConsoleEntry>>()
        store.entries().subscribe { observed.add(it) }

        store.append(entry("one"))
        store.append(entry("two"))

        assertThat(store.snapshot().map(ConsoleEntry::message)).containsExactly("one", "two")
        assertThat(observed.last().map(ConsoleEntry::message)).containsExactly("one", "two")
    }

    @Test
    fun `evicts oldest beyond capacity`() {
        store.append(entry("one"))
        store.append(entry("two"))
        store.append(entry("three"))
        store.append(entry("four"))

        assertThat(store.snapshot().map(ConsoleEntry::message))
            .containsExactly("two", "three", "four")
    }

    @Test
    fun `clear empties and emits`() {
        val observed = mutableListOf<List<ConsoleEntry>>()
        store.entries().skip(1).subscribe { observed.add(it) }

        store.append(entry("one"))
        store.clear()

        assertThat(store.snapshot()).isEmpty()
        assertThat(observed.last()).isEmpty()
    }
}
