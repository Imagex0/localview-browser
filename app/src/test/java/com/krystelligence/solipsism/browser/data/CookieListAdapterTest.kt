package com.krystelligence.solipsism.browser.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CookieListAdapterTest {

    private val cookies = listOf(
        BrowserCookie("SID", "abc123"),
        BrowserCookie("ssid_secure", "x"),
        BrowserCookie("theme", "")
    )

    @Test
    fun `blank query returns everything`() {
        assertThat(CookieListAdapter.filterCookies(cookies, "  ")).isEqualTo(cookies)
    }

    @Test
    fun `query matches case-insensitively`() {
        assertThat(CookieListAdapter.filterCookies(cookies, "SID").map(BrowserCookie::name))
            .containsExactly("SID", "ssid_secure")
    }

    @Test
    fun `no match returns empty`() {
        assertThat(CookieListAdapter.filterCookies(cookies, "nope")).isEmpty()
    }

    @Test
    fun `mask hides values but keeps length`() {
        assertThat(CookieListAdapter.mask("abc123")).isEqualTo("•••• (6 characters)")
        assertThat(CookieListAdapter.mask("")).isEqualTo("(empty)")
    }
}
