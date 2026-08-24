package com.d35p4c1t0.piffbackup.onboarding

import net.schmizz.sshj.common.SecurityUtils
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordKeyInstallerTest {
    @Test
    fun `ssh bootstrap uses default providers instead of the colliding BC name`() {
        configureSshjSecurityProviders()

        assertNull(SecurityUtils.getSecurityProvider())
        assertNotNull(SecurityUtils.getKeyAgreement("X25519"))
        assertNotNull(SecurityUtils.getSignature("Ed25519"))
    }

    @Test
    fun `android config excludes incompatible and legacy key exchanges`() {
        configureSshjSecurityProviders()

        val config = androidCompatibleSshConfig()
        val names = config.keyExchangeFactories.map { it.name }
        val hostKeyNames = config.keyAlgorithms.map { it.name }

        assertTrue(names.first() == "diffie-hellman-group16-sha512")
        assertTrue(names.none { it.contains("curve25519", ignoreCase = true) })
        assertTrue(names.none { it.startsWith("ecdh-", ignoreCase = true) })
        assertTrue(names.none { it.contains("sha1", ignoreCase = true) })
        assertTrue("diffie-hellman-group16-sha512" in names)
        assertTrue(hostKeyNames == listOf("rsa-sha2-512", "rsa-sha2-256"))
    }
}
