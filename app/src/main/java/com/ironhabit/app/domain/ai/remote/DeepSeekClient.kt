package com.ironhabit.app.domain.ai.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DeepSeek「对话补全」的最小抽象。
 *
 * 抽成接口的唯一目的：**单测注入 fake**（不打真网络即可测回落与解析链路）；
 * 生产实现是 [DeepSeekClient]（`HttpURLConnection`，零第三方 HTTP 依赖）。
 */
interface DeepSeekApi {

    /**
     * 阻塞式调用（**必须在 `Dispatchers.IO` 上跑**，UseCase 已保证）。
     *
     * @param systemPrompt system 段提示词
     * @param userPrompt user 段提示词（含档案 / 动作库 / 现有计划的 JSON 载荷）
     * @param apiKey 用户的 DeepSeek Key；**只进 Authorization header，绝不落日志**
     * @return `choices[0].message.content`（应为一段 JSON 文本）
     * @throws IOException 网络 / 超时 / HTTP 非 2xx / 响应缺 content
     */
    fun complete(systemPrompt: String, userPrompt: String, apiKey: String): String
}

/**
 * DeepSeek HTTP 客户端（thin wrapper）。
 *
 * 刻意用 `HttpURLConnection` 而非 OkHttp/Retrofit：本项目依赖极简红线，
 * 一个端点 + 一个请求体不值得引入整套 HTTP 框架。
 *
 * 请求要点（对应派工单）：
 * - `POST https://api.deepseek.com/chat/completions`
 * - `Authorization: Bearer <key>`（**Key 只出现在 header，永不进异常消息 / 日志**）
 * - `model="deepseek-chat"`、`response_format={"type":"json_object"}`、
 *   `temperature=0.2`（压低随机性，输出尽量确定）、`max_tokens=2000`
 * - connect / read 超时各 30s
 *
 * 序列化走已有的 `kotlinx-serialization-json`（请求 DTO 内部私有，不出本文件）。
 */
class DeepSeekClient : DeepSeekApi {

    override fun complete(systemPrompt: String, userPrompt: String, apiKey: String): String {
        val request = ChatRequest(
            model = MODEL,
            messages = listOf(
                ChatMessage(role = ROLE_SYSTEM, content = systemPrompt),
                ChatMessage(role = ROLE_USER, content = userPrompt),
            ),
            responseFormat = ResponseFormat(type = RESPONSE_FORMAT_JSON),
            temperature = TEMPERATURE,
            maxTokens = MAX_TOKENS,
        )
        // 用显式 serializer 的重载：DTO 是文件私有类型，不能走 reified 泛型重载。
        val body: String = json.encodeToString(ChatRequest.serializer(), request)

        val connection: HttpURLConnection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = METHOD_POST
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            doOutput = true
            // Key 只进 header；任何异常消息里都不得携带它（见类注释红线）。
            setRequestProperty(HEADER_AUTHORIZATION, "$HEADER_BEARER_PREFIX$apiKey")
            setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
        }

        try {
            connection.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }

            val code: Int = connection.responseCode
            if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                // 只带状态码 + 错误体前 200 字（服务端错误体不含用户 Key，可安全入日志/异常）。
                val errorSnippet: String = connection.errorStream
                    ?.readBytes()
                    ?.toString(Charsets.UTF_8)
                    ?.take(ERROR_SNIPPET_LIMIT)
                    .orEmpty()
                throw IOException("DeepSeek HTTP $code${if (errorSnippet.isEmpty()) "" else ": $errorSnippet"}")
            }

            val responseBody: String = connection.inputStream
                .readBytes()
                .toString(Charsets.UTF_8)
            return parseContent(responseBody)
                ?: throw IOException("DeepSeek 响应缺少 choices[0].message.content")
        } finally {
            connection.disconnect()
        }
    }

    /** 从 chat/completions 响应里取 `choices[0].message.content`；畸形响应返回 `null`。 */
    private fun parseContent(responseBody: String): String? = runCatching {
        json.decodeFromString<ChatResponse>(responseBody).choices.firstOrNull()?.message?.content
    }.getOrNull()

    private companion object {
        const val ENDPOINT: String = "https://api.deepseek.com/chat/completions"
        const val MODEL: String = "deepseek-chat"
        const val METHOD_POST: String = "POST"
        const val ROLE_SYSTEM: String = "system"
        const val ROLE_USER: String = "user"
        const val RESPONSE_FORMAT_JSON: String = "json_object"
        const val HEADER_AUTHORIZATION: String = "Authorization"
        const val HEADER_BEARER_PREFIX: String = "Bearer "
        const val HEADER_CONTENT_TYPE: String = "Content-Type"
        const val CONTENT_TYPE_JSON: String = "application/json; charset=utf-8"
        const val TEMPERATURE: Double = 0.2
        const val MAX_TOKENS: Int = 2000
        const val TIMEOUT_MILLIS: Int = 30_000
        const val HTTP_OK_MIN: Int = 200
        const val HTTP_OK_MAX: Int = 299
        const val ERROR_SNIPPET_LIMIT: Int = 200

        /** 宽松解析：忽略未知字段（DeepSeek 加字段不崩）。 */
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

// ---------------- 请求 / 响应 DTO（内部私有，不出本文件）----------------

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ResponseFormat(
    val type: String,
)

@Serializable
private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
private data class Choice(
    val message: ChatMessage? = null,
)
