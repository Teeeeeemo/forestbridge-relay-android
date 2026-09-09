package com.forestbridge.relay

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

class RelayBridge(
    private val activity: Activity,
    private val webView: WebView,
    webAppUrl: String,
    private val uiToken: String
) {
    companion object {
        private const val RESPONSE_EVENT =
            "forestbridge:native-relay-response"
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MAX_REQUEST_TEXT_LENGTH = 500
        private val TASK_ID_PATTERN = Regex("^[a-fA-F0-9]{32}$")
        private val ALLOWED_PRESETS = setOf("table_pick_place_01")
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val relayOrigin = relayOrigin(webAppUrl)

    @JavascriptInterface
    fun requestState(requestId: String) {
        execute(
            requestId = requestId,
            operation = "state",
            method = "GET",
            path = "/api/state"
        )
    }

    @JavascriptInterface
    fun createTask(
        requestId: String,
        preset: String,
        requestText: String
    ) {
        if (preset !in ALLOWED_PRESETS) {
            dispatchError(
                requestId,
                "create_task",
                "Unsupported task preset"
            )
            return
        }

        val body = JSONObject()
            .put("task_type", "navigate_then_pick_place")
            .put("preset", preset)
            .put(
                "request_text",
                requestText.take(MAX_REQUEST_TEXT_LENGTH)
            )
            .toString()

        execute(
            requestId = requestId,
            operation = "create_task",
            method = "POST",
            path = "/api/tasks",
            body = body,
            idempotencyKey = UUID.randomUUID().toString()
        )
    }

    @JavascriptInterface
    fun stopTask(requestId: String, taskId: String) {
        if (!TASK_ID_PATTERN.matches(taskId)) {
            dispatchError(
                requestId,
                "stop_task",
                "Invalid task ID"
            )
            return
        }

        execute(
            requestId = requestId,
            operation = "stop_task",
            method = "POST",
            path = "/api/tasks/$taskId/stop",
            body = "{}"
        )
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun execute(
        requestId: String,
        operation: String,
        method: String,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null
    ) {
        val safeRequestId = requestId.take(MAX_REQUEST_ID_LENGTH)
        if (safeRequestId.isBlank()) return

        if (uiToken.isBlank()) {
            dispatchError(
                safeRequestId,
                operation,
                "Relay UI token is not configured"
            )
            return
        }

        executor.execute {
            var connection: HttpsURLConnection? = null
            try {
                connection = URL(relayOrigin + path)
                    .openConnection() as HttpsURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $uiToken"
                )
                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )
                if (idempotencyKey != null) {
                    connection.setRequestProperty(
                        "Idempotency-Key",
                        idempotencyKey
                    )
                }

                if (body != null) {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                val status = connection.responseCode
                val responseText =
                    (if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    })?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()

                val payload = parsePayload(responseText)
                dispatchResponse(
                    requestId = safeRequestId,
                    operation = operation,
                    ok = status in 200..299,
                    status = status,
                    payload = payload
                )
            } catch (error: Exception) {
                dispatchError(
                    safeRequestId,
                    operation,
                    error.javaClass.simpleName
                )
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun parsePayload(responseText: String): Any =
        if (responseText.isBlank()) {
            JSONObject()
        } else {
            try {
                JSONTokener(responseText).nextValue()
            } catch (_: Exception) {
                JSONObject().put("error", "Invalid Relay response")
            }
        }

    private fun dispatchResponse(
        requestId: String,
        operation: String,
        ok: Boolean,
        status: Int,
        payload: Any
    ) {
        val envelope = JSONObject()
            .put("request_id", requestId)
            .put("operation", operation)
            .put("ok", ok)
            .put("status", status)
            .put("data", payload)

        if (!ok) {
            val message = (payload as? JSONObject)
                ?.optString("error")
                ?.takeIf { it.isNotBlank() }
                ?: "Relay request failed"
            envelope.put("error", message)
        }

        dispatch(envelope)
    }

    private fun dispatchError(
        requestId: String,
        operation: String,
        message: String
    ) {
        if (requestId.isBlank()) return
        dispatch(
            JSONObject()
                .put(
                    "request_id",
                    requestId.take(MAX_REQUEST_ID_LENGTH)
                )
                .put("operation", operation)
                .put("ok", false)
                .put("status", 0)
                .put("error", message)
        )
    }

    private fun dispatch(envelope: JSONObject) {
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            val script =
                "window.dispatchEvent(new CustomEvent(" +
                    "'$RESPONSE_EVENT', { detail: " +
                    envelope.toString() +
                    " }));"
            webView.evaluateJavascript(script, null)
        }
    }

    private fun relayOrigin(webAppUrl: String): String {
        val uri = URI(webAppUrl)
        require(uri.scheme == "https") {
            "Relay origin must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) {
            "Relay origin must include a host"
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://${uri.host}$port"
    }
}
