package com.example.igy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecurityUtilsTest {

    @Test
    fun testEncryptionDecryption() {
        val originalText = "super-secret-outline-key"
        val encryptedText = SecurityUtils.encrypt(originalText)
        
        assertNotEquals(originalText, encryptedText)
        
        val decryptedText = SecurityUtils.decrypt(encryptedText)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testEmptyString() {
        val originalText = ""
        val encryptedText = SecurityUtils.encrypt(originalText)
        val decryptedText = SecurityUtils.decrypt(encryptedText)
        assertEquals(originalText, decryptedText)
    }
}
