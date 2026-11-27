// 保护：fork 的两个免 Key 网关（KiloCode Free / OpenCode PI）的注册契约——
// 空 Key 放行、默认端点、目录摘要资源、默认 provider、以及两处目录拉取共用的门槛函数。
// 这些一旦回退，新用户会在开屏配置页重新被要求填 API Key。
package com.niki914.zafiro.app.ui.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderSpecTest {

    private fun spec(id: String): ProviderSpec = ProviderSpecs.all.first { it.id == id }

    @Test
    fun `free gateways allow an empty api key`() {
        assertTrue(spec("kilo-free").allowsEmptyApiKey)
        assertTrue(spec("opencode-pi").allowsEmptyApiKey)
        assertFalse(spec("deepseek").allowsEmptyApiKey)
        assertFalse(spec("openrouter").allowsEmptyApiKey)
    }

    @Test
    fun `default provider is the zero config free gateway`() {
        assertEquals("kilo-free", ProviderSpecs.default.id)
    }

    @Test
    fun `spec ids are unique and both fork gateways are listed`() {
        val ids = ProviderSpecs.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains("kilo-free"))
        assertTrue(ids.contains("opencode-pi"))
    }

    @Test
    fun `unknown provider id falls back to default`() {
        assertEquals(ProviderSpecs.default, ProviderSpecs.find("no-such-provider"))
        assertEquals(ProviderSpecs.default, ProviderSpecs.find(null))
    }

    @Test
    fun `free gateway summaries resolve for the picker`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        listOf("kilo-free", "opencode-pi").forEach { id ->
            val res = spec(id).summaryRes
            assertNotNull("summaryRes missing for $id", res)
            assertTrue("blank summary for $id", context.getString(res!!).isNotBlank())
        }
    }

    @Test
    fun `fork gateways point at their documented endpoints`() {
        val kilo = spec("kilo-free")
        assertEquals("https://api.kilo.ai/api/gateway/chat/completions", kilo.officialEndpoint)
        assertEquals("kilo-auto/free", kilo.exampleModelId)
        assertTrue(kilo.allowsCustomEndpointInNewConfig)

        val pi = spec("opencode-pi")
        assertEquals("http://127.0.0.1:18080/v1/chat/completions", pi.officialEndpoint)
        assertTrue(pi.allowsCustomEndpointInNewConfig)
    }

    @Test
    fun `catalog gate honours empty key only for free gateways`() {
        val kilo = spec("kilo-free")
        val deepseek = spec("deepseek")

        assertTrue(canFetchModelCatalog(kilo.officialEndpoint, "", kilo))
        assertTrue(canFetchModelCatalog(deepseek.officialEndpoint, "sk-x", deepseek))
        assertFalse(canFetchModelCatalog(deepseek.officialEndpoint, "", deepseek))
        assertFalse(canFetchModelCatalog("", "sk-x", deepseek))
        assertFalse(canFetchModelCatalog("", "", kilo))
    }
}
