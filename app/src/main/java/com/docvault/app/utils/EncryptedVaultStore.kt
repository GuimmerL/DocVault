package com.docvault.app.utils

import java.io.File
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-based encryption primitives for protected category storage. */
object EncryptedVaultStore {
    private const val saltLength = 16
    private const val ivLength = 12
    private const val keyBits = 256
    private const val iterations = 210_000
    private const val tagBits = 128

    fun encrypt(source: File, destination: File, password: CharArray, salt: ByteArray) {
        require(salt.size == saltLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, iv))
        destination.parentFile?.mkdirs()
        try {
            FileOutputStream(destination).use { output ->
                output.write(iv)
                CipherOutputStream(output, cipher).use { encrypted ->
                    FileInputStream(source).use { input -> input.copyTo(encrypted) }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun decrypt(source: File, destination: File, password: CharArray, salt: ByteArray) {
        require(salt.size == saltLength)
        try {
            FileInputStream(source).use { input ->
                val iv = ByteArray(ivLength)
                if (input.read(iv) != ivLength) error("Encrypted document is truncated")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, iv))
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { output ->
                    CipherInputStream(input, cipher).use { decrypted -> decrypted.copyTo(output) }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    /** Encrypt a small payload with AES-GCM; the random IV is prefixed to the result. */
    fun encryptBytes(plain: ByteArray, password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == saltLength)
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decryptBytes(encrypted: ByteArray, password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == saltLength)
        require(encrypted.size >= ivLength + tagBits / 8) { "Encrypted payload is truncated" }
        val iv = encrypted.copyOfRange(0, ivLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, iv))
        return cipher.doFinal(encrypted, ivLength, encrypted.size - ivLength)
    }

    fun newSalt(): ByteArray = ByteArray(saltLength).also { SecureRandom().nextBytes(it) }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, keyBits)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
