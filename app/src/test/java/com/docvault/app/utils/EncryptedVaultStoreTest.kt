package com.docvault.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EncryptedVaultStoreTest {
    @Test fun encryptedPayloadRoundTripsAndAuthenticates() {
        val password = "correct horse battery".toCharArray()
        val salt = EncryptedVaultStore.newSalt()
        val plain = "protected metadata and page index".toByteArray()
        val encrypted = EncryptedVaultStore.encryptBytes(plain, password, salt)
        assertNotEquals(String(plain), String(encrypted))
        assertArrayEquals(plain, EncryptedVaultStore.decryptBytes(encrypted, password, salt))
        val corrupt = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try {
            EncryptedVaultStore.decryptBytes(corrupt, password, salt)
            throw AssertionError("tampered payload unexpectedly decrypted")
        } catch (_: Exception) { }
    }
}
