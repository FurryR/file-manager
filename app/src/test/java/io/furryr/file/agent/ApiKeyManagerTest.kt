package io.furryr.file.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for [ApiKeyManager].
 *
 * Uses Robolectric to provide the Android Context needed by EncryptedSharedPreferences.
 * Requires dependencies added by Task 5:
 *   - `androidx.security:security-crypto`
 *   - `org.robolectric:robolectric`
 *   - `androidx.test:core`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApiKeyManagerTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ApiKeyManager.init(context)
    }

    @After
    fun tearDown() {
        // Clean up all keys after each test
        ApiKeyManager.listProviders().forEach { provider ->
            ApiKeyManager.deleteKey(provider)
        }
    }

    @Test
    fun `saveKey stores value that getKey retrieves`() {
        ApiKeyManager.saveKey("openai", "sk-test123")
        assertEquals("sk-test123", ApiKeyManager.getKey("openai"))
    }

    @Test
    fun `getKey returns null for unknown provider`() {
        assertNull(ApiKeyManager.getKey("nonexistent"))
    }

    @Test
    fun `hasKey returns true only after save`() {
        assertFalse(ApiKeyManager.hasKey("anthropic"))
        ApiKeyManager.saveKey("anthropic", "sk-ant-test")
        assertTrue(ApiKeyManager.hasKey("anthropic"))
    }

    @Test
    fun `deleteKey removes stored key`() {
        ApiKeyManager.saveKey("gemini", "sk-gem-test")
        assertNotNull(ApiKeyManager.getKey("gemini"))

        ApiKeyManager.deleteKey("gemini")
        assertNull(ApiKeyManager.getKey("gemini"))
        assertFalse(ApiKeyManager.hasKey("gemini"))
    }

    @Test
    fun `listProviders returns all saved providers`() {
        ApiKeyManager.saveKey("openai", "sk-1")
        ApiKeyManager.saveKey("anthropic", "sk-2")

        val providers = ApiKeyManager.listProviders()
        assertTrue(providers.contains("openai"))
        assertTrue(providers.contains("anthropic"))
        assertEquals(2, providers.size)
    }

    @Test
    fun `overwrite updates existing key`() {
        ApiKeyManager.saveKey("openai", "old-value")
        ApiKeyManager.saveKey("openai", "new-value")
        assertEquals("new-value", ApiKeyManager.getKey("openai"))
    }

    @Test
    fun `saveKey with empty string stores empty value`() {
        ApiKeyManager.saveKey("openai", "")
        assertEquals("", ApiKeyManager.getKey("openai"))
    }

    @Test
    fun `deleteKey on nonexistent provider does not throw`() {
        ApiKeyManager.deleteKey("nosuchprovider")
        // Should not throw
    }

    @Test
    fun `listProviders returns empty list when no keys stored`() {
        assertTrue(ApiKeyManager.listProviders().isEmpty())
    }
}
