package xyz.nextalone.nagram.network

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType

class NetworkRequestBuilder(
    private val url: String,
    private val method: String = "GET",
    private val headersMap: MutableMap<String, String> = mutableMapOf(),
    private val paramsMap: MutableMap<String, String> = mutableMapOf(),
    private var contentBody: String? = null
) {
    fun header(key: String, value: String): NetworkRequestBuilder {
        headersMap[key] = value
        return this
    }

    fun parameter(key: String, value: String): NetworkRequestBuilder {
        paramsMap[key] = value
        return this
    }

    fun setBody(body: String): NetworkRequestBuilder {
        this.contentBody = body
        return this
    }

    fun contentType(contentType: ContentType): NetworkRequestBuilder {
        headersMap["Content-Type"] = contentType.toString()
        return this
    }

    fun execute(): NetworkResponse {
        var responseBody = ""

        try {
            val response: HttpResponse = kotlinx.coroutines.runBlocking {
                NetworkLoggingInterceptor.executeWithLogging(
                    method = method.uppercase(),
                    url = url,
                    headers = headersMap.toMap(),
                    requestBody = contentBody,
                    requestParams = paramsMap.toMap()
                ) {
                    when (method.uppercase()) {
                        "GET" -> get(url) {
                            applyHeadersAndParams()
                        }
                        "POST" -> post(url) {
                            applyHeadersAndParams()
                            contentBody?.let { setBody(it) }
                        }
                        else -> get(url) {
                            applyHeadersAndParams()
                        }
                    }
                }
            }

            kotlinx.coroutines.runBlocking {
                try {
                    responseBody = response.bodyAsText()
                    if (responseBody.length > 10000) {
                        responseBody = responseBody.take(10000) + "\n... (truncated)"
                    }
                } catch (e: Exception) {
                    responseBody = "Unable to read response body: ${e.message}"
                }
            }

            return NetworkResponse(
                statusCode = response.status.value,
                body = responseBody
            )

        } catch (_: Exception) {
            return NetworkResponse(
                statusCode = 0,
                body = ""
            )
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeadersAndParams() {
        headersMap.forEach { (key, value) ->
            header(key, value)
        }
        paramsMap.forEach { (key, value) ->
            parameter(key, value)
        }
    }

    companion object {
        fun get(url: String, block: NetworkRequestBuilder.() -> Unit): NetworkRequestBuilder {
            return NetworkRequestBuilder(url, "GET").apply(block)
        }

        fun post(url: String, block: NetworkRequestBuilder.() -> Unit): NetworkRequestBuilder {
            return NetworkRequestBuilder(url, "POST").apply(block)
        }
    }
}

data class NetworkResponse(
    val statusCode: Int,
    val body: String
)
