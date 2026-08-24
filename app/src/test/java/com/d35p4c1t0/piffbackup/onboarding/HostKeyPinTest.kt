package com.d35p4c1t0.piffbackup.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class HostKeyPinTest {
    @Test
    fun `round trips an SSH public key and produces a standard fingerprint`() {
        val key = rsaKey()
        val pin = HostKeyPin.fromPublicKey(key)
        val restored = HostKeyPin.parse(pin.persistedValue)

        assertTrue(pin.securelyMatches(restored))
        assertTrue(pin.sha256Fingerprint.startsWith("SHA256:"))
        assertEquals("box.example ${pin.persistedValue}\n", pin.knownHostsLine("box.example"))
    }

    @Test
    fun `pinning verifier rejects a changed server identity`() {
        val expectedKey = rsaKey()
        val expected = HostKeyPin.fromPublicKey(expectedKey)
        val verifier = PinningHostKeyVerifier("box.example", 23, expected)

        assertTrue(verifier.verify("box.example", 23, expectedKey))
        assertFalse(verifier.verify("box.example", 23, rsaKey()))
        assertTrue(verifier.rejectedChangedKey)
        assertEquals(listOf(expected.algorithm), verifier.findExistingAlgorithms("box.example", 23))
    }

    @Test
    fun `first use captures one key but not a second identity`() {
        val first = rsaKey()
        val verifier = PinningHostKeyVerifier("box.example", 23, null)

        assertTrue(verifier.verify("box.example", 23, first))
        assertTrue(verifier.verify("box.example", 23, first))
        assertFalse(verifier.verify("box.example", 23, rsaKey()))
        assertFalse(verifier.verify("other.example", 23, first))
    }

    private fun rsaKey() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
}
