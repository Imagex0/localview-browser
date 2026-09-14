package com.krystelligence.solipsism.adblock.lists

import android.app.Application
import android.content.SharedPreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

class FilterListRepositoryTest {

    private val stored = mutableMapOf<String, String?>()
    private lateinit var repository: FilterListRepository

    @Before
    fun setUp() {
        stored.clear()
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(editor.putString(anyString(), anyString())).thenAnswer(Answer<SharedPreferences.Editor> { invocation ->
            stored[invocation.arguments[0] as String] = invocation.arguments[1] as String?
            editor
        })
        val preferences = mock(SharedPreferences::class.java)
        `when`(preferences.getString(anyString(), any())).thenAnswer(Answer<String?> { invocation ->
            stored[invocation.arguments[0] as String] ?: invocation.arguments[1] as String?
        })
        `when`(preferences.edit()).thenReturn(editor)
        val application = mock(Application::class.java)
        `when`(application.getSharedPreferences(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(preferences)
        repository = FilterListRepository(application)
    }

    @Test
    fun `added lists round-trip with defaults`() {
        assertThat(repository.add("https://example.com/hosts.txt", FilterListKind.HOSTS)).isTrue()

        val lists = repository.lists()
        assertThat(lists).hasSize(1)
        assertThat(lists[0].url).isEqualTo("https://example.com/hosts.txt")
        assertThat(lists[0].kind).isEqualTo(FilterListKind.HOSTS)
        assertThat(lists[0].enabled).isTrue()
        assertThat(lists[0].lastUpdated).isEqualTo(0L)
    }

    @Test
    fun `duplicate urls are rejected case-insensitively`() {
        repository.add("https://example.com/hosts.txt", FilterListKind.HOSTS)

        assertThat(repository.add("https://EXAMPLE.com/hosts.txt", FilterListKind.HOSTS)).isFalse()
        assertThat(repository.lists()).hasSize(1)
    }

    @Test
    fun `blank urls are rejected`() {
        assertThat(repository.add("   ", FilterListKind.ABP)).isFalse()
        assertThat(repository.lists()).isEmpty()
    }

    @Test
    fun `enable toggle and timestamp persist`() {
        repository.add("https://example.com/list.txt", FilterListKind.ABP)

        assertThat(repository.setEnabled("https://example.com/list.txt", false)).isTrue()
        assertThat(repository.setEnabled("https://example.com/list.txt", false)).isFalse()
        assertThat(repository.updateTimestamp("https://example.com/list.txt", 12345L)).isTrue()

        val entry = repository.entry("https://example.com/list.txt")
        assertThat(entry?.enabled).isFalse()
        assertThat(entry?.lastUpdated).isEqualTo(12345L)
    }

    @Test
    fun `remove deletes only the matching url`() {
        repository.add("https://example.com/a.txt", FilterListKind.HOSTS)
        repository.add("https://example.com/b.txt", FilterListKind.ABP)

        assertThat(repository.remove("https://example.com/a.txt")).isTrue()
        assertThat(repository.remove("https://example.com/a.txt")).isFalse()
        assertThat(repository.lists().map { it.url }).containsExactly("https://example.com/b.txt")
    }
}
