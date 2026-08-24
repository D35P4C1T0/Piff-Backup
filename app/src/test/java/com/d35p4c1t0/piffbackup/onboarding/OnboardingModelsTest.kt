package com.d35p4c1t0.piffbackup.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnboardingModelsTest {
    @Test
    fun `derives the documented Hetzner hostname and fixed port`() {
        val endpoint = StorageBoxEndpoint.create("u123456", null)

        assertEquals("u123456.your-storagebox.de", endpoint.hostname)
        assertEquals(23, endpoint.port)
    }

    @Test
    fun `accepts a validated advanced hostname`() {
        val endpoint = StorageBoxEndpoint.create("u123456", "box.example.test")

        assertEquals("box.example.test", endpoint.hostname)
    }

    @Test
    fun `rejects unsafe or malformed endpoint values`() {
        assertThrows(IllegalArgumentException::class.java) { StorageBoxEndpoint.create("bad user", null) }
        assertThrows(IllegalArgumentException::class.java) { StorageBoxEndpoint.create("u123456", "box..example") }
        assertThrows(IllegalArgumentException::class.java) { StorageBoxEndpoint.create("u123456", "-box.example") }
        assertThrows(IllegalArgumentException::class.java) { StorageBoxEndpoint("u123456", "box.example", 22) }
    }
}
