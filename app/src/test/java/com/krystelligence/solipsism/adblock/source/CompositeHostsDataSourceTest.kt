package com.krystelligence.solipsism.adblock.source

import com.krystelligence.solipsism.database.adblock.Host
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.IOException

class CompositeHostsDataSourceTest {

    private class FakeSource(
        private val id: String,
        private val result: HostsResult
    ) : HostsDataSource {
        override suspend fun loadHosts(): HostsResult = result
        override suspend fun identifier(): String = id
    }

    @Test
    fun `union merges hosts and dedupes`() { runBlocking {
        val source = CompositeHostsDataSource(
            listOf(
                FakeSource("a", HostsResult.Success(listOf(Host("one.com"), Host("two.com")))),
                FakeSource("b", HostsResult.Success(listOf(Host("two.com"), Host("three.com"))))
            )
        )

        val result = source.loadHosts()

        assertThat(result).isInstanceOf(HostsResult.Success::class.java)
        assertThat((result as HostsResult.Success).hosts.map { it.name })
            .containsExactlyInAnyOrder("one.com", "two.com", "three.com")
    }
    }

    @Test
    fun `partial failure still merges successes and reports each source`() { runBlocking {
        val reported = mutableMapOf<String, Boolean>()
        val source = CompositeHostsDataSource(
            listOf(
                FakeSource("good", HostsResult.Success(listOf(Host("one.com")))),
                FakeSource("bad", HostsResult.Failure(IOException("down")))
            )
        ) { identifier, success -> reported[identifier] = success }

        val result = source.loadHosts()

        assertThat((result as HostsResult.Success).hosts.map { it.name }).containsExactly("one.com")
        assertThat(reported).containsExactlyInAnyOrderEntriesOf(mapOf("good" to true, "bad" to false))
    }
    }

    @Test
    fun `total failure returns failure`() { runBlocking {
        val source = CompositeHostsDataSource(
            listOf(FakeSource("bad", HostsResult.Failure(IOException("down"))))
        )

        assertThat(source.loadHosts()).isInstanceOf(HostsResult.Failure::class.java)
    }
    }

    @Test
    fun `identifier is stable and order independent`() { runBlocking {
        val first = CompositeHostsDataSource(
            listOf(FakeSource("a", HostsResult.Success(emptyList())), FakeSource("b", HostsResult.Success(emptyList())))
        )
        val second = CompositeHostsDataSource(
            listOf(FakeSource("b", HostsResult.Success(emptyList())), FakeSource("a", HostsResult.Success(emptyList())))
        )

        assertThat(first.identifier()).isEqualTo(second.identifier())
        assertThat(first.identifier()).startsWith("composite:")
    }
    }
}
