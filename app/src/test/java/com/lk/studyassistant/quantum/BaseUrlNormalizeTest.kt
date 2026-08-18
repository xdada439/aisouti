package com.lk.studyassistant.quantum

import com.lk.studyassistant.quantum.data.ApiConfigStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Base URL 规范化回归测试。
 *
 * 真机测试时撞到的：用户从阿里云控制台复制了专属 endpoint 的裸域名
 * （`llm-xxx.cn-beijing.maas.aliyuncs.com`），没带 https://，
 * OkHttp 直接抛 "Expected URL scheme 'http' or 'https'"，
 * 而这个报错完全看不出该怎么改。
 */
class BaseUrlNormalizeTest {

    private fun norm(s: String) = ApiConfigStore.normalizeBaseUrl(s)

    @Test
    fun 裸域名自动补协议头和标准路径() {
        assertEquals(
            "https://llm-y3oho3xy9fsqlfxg.cn-beijing.maas.aliyuncs.com/v1/chat/completions",
            norm("llm-y3oho3xy9fsqlfxg.cn-beijing.maas.aliyuncs.com")
        )
    }

    @Test
    fun 带协议头但只有域名时补标准路径() {
        assertEquals(
            "https://my-endpoint.example.com/v1/chat/completions",
            norm("https://my-endpoint.example.com")
        )
    }

    @Test
    fun 已经是完整路径的原样返回() {
        val full = "https://api.openai.com/v1/chat/completions"
        assertEquals(full, norm(full))
    }

    @Test
    fun 通义千问兼容模式补全() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            norm("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
    }

    @Test
    fun 豆包方舟补全() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            norm("https://ark.cn-beijing.volces.com/api/v3")
        )
    }

    @Test
    fun Kimi补全() {
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            norm("https://api.moonshot.cn/v1")
        )
    }

    @Test
    fun 智谱补全() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            norm("https://open.bigmodel.cn/api/paas/v4")
        )
    }

    @Test
    fun 尾部斜杠不影响结果() {
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            norm("  https://api.moonshot.cn/v1/  ")
        )
    }

    @Test
    fun http明文地址保留原协议() {
        assertEquals(
            "http://192.168.1.10:8000/v1/chat/completions",
            norm("http://192.168.1.10:8000")
        )
    }

    @Test
    fun 空串原样返回() {
        assertEquals("", norm("   "))
    }

    /** 服务商预设本身必须是能直接用的：规范化后都要落在 /chat/completions 上。 */
    @Test
    fun 所有预设的BaseUrl都能规范化到聊天补全端点() {
        ApiConfigStore.PROVIDER_PRESETS.forEach { p ->
            val n = norm(p.baseUrl)
            assert(n.endsWith("/chat/completions")) { "${p.name} 规范化结果异常: $n" }
        }
    }

    /** 预设的提示文案是给 TextView 显示的，TextView 不渲染 Markdown，出现星号就是界面上的乱码。 */
    @Test
    fun 预设提示文案不含Markdown标记() {
        ApiConfigStore.PROVIDER_PRESETS.forEach { p ->
            assert(!p.hint.contains("**")) { "${p.name} 的 hint 含 Markdown 星号: ${p.hint}" }
        }
    }
}
