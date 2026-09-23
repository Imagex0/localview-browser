package com.krystelligence.solipsism.database.bookmark

import com.krystelligence.solipsism.SDK_VERSION
import com.krystelligence.solipsism.TestApplication
import com.krystelligence.solipsism.database.Bookmark
import com.krystelligence.solipsism.database.asFolder
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    application = TestApplication::class,
    sdk = [SDK_VERSION]
)
class BookmarkManualOrderTest {

    private lateinit var database: BookmarkDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        database = BookmarkDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `new bookmarks get incremental positions instead of all zero`() {
        val urls = listOf(
            "https://example.test/a",
            "https://example.test/b",
            "https://example.test/c"
        )
        urls.forEach { url ->
            database.addBookmarkIfNotExists(
                Bookmark.Entry(url = url, title = url, position = 0, folder = Bookmark.Folder.Root)
            ).blockingGet()
        }

        val sorted = database.getBookmarksFromFolderSorted("", BookmarkSortOrder.MANUAL).blockingGet()
            .filterIsInstance<Bookmark.Entry>()

        assertThat(sorted.map { it.url }).containsExactly(
            "https://example.test/a",
            "https://example.test/b",
            "https://example.test/c"
        )
        assertThat(sorted.map { it.position }).containsExactly(0, 1, 2)
    }

    @Test
    fun `updateBookmarkOrder persists drag-reordered list`() {
        val initial = (0 until 3).map { index ->
            Bookmark.Entry(
                url = "https://example.test/item-$index",
                title = "Item $index",
                position = index,
                folder = Bookmark.Folder.Root
            )
        }
        database.addBookmarkList(initial).blockingAwait()

        val reversed = initial.reversed()
        database.updateBookmarkOrder(reversed).blockingAwait()

        val sorted = database.getBookmarksFromFolderSorted("", BookmarkSortOrder.MANUAL).blockingGet()
            .filterIsInstance<Bookmark.Entry>()

        assertThat(sorted.map { it.url }).containsExactly(
            "https://example.test/item-2",
            "https://example.test/item-1",
            "https://example.test/item-0"
        )
    }

    private companion object {
        const val DATABASE_NAME = "bookmarkManager"
    }
}
