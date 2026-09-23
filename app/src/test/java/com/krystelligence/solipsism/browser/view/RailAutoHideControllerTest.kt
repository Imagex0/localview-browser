package com.krystelligence.solipsism.browser.view

import com.krystelligence.solipsism.SDK_VERSION
import com.krystelligence.solipsism.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class RailAutoHideControllerTest {

    private lateinit var controller: RailAutoHideController
    private var hideCount = 0
    private var showCount = 0

    @Before
    fun setUp() {
        controller = RailAutoHideController()
        controller.inputEnabled = true
        controller.onHide = { hideCount++ }
        controller.onShow = { showCount++ }
        hideCount = 0
        showCount = 0
    }

    @Test
    fun `scroll down past threshold hides once`() {
        controller.onWebViewScroll(200, 500)
        assertThat(controller.isHidden).isTrue()
        assertThat(hideCount).isEqualTo(1)

        controller.onWebViewScroll(200, 700)
        assertThat(hideCount).isEqualTo(1)
    }

    @Test
    fun `scroll up past threshold shows`() {
        controller.onWebViewScroll(200, 500)
        assertThat(controller.isHidden).isTrue()

        controller.onWebViewScroll(-200, 300)
        assertThat(controller.isHidden).isFalse()
        assertThat(showCount).isEqualTo(1)
    }

    @Test
    fun `near top always shows`() {
        controller.onWebViewScroll(200, 500)
        assertThat(controller.isHidden).isTrue()

        controller.onWebViewScroll(5, 2)
        assertThat(controller.isHidden).isFalse()
        assertThat(showCount).isEqualTo(1)
    }

    @Test
    fun `disabled input ignores scroll`() {
        controller.inputEnabled = false
        controller.onWebViewScroll(500, 800)
        assertThat(controller.isHidden).isFalse()
        assertThat(hideCount).isEqualTo(0)
    }

    @Test
    fun `small jitters do not trigger`() {
        controller.onWebViewScroll(5, 500)
        controller.onWebViewScroll(5, 505)
        assertThat(controller.isHidden).isFalse()
        assertThat(hideCount).isEqualTo(0)
    }
}
