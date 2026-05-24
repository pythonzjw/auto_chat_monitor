package com.wework.forwarder

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 统一授权中心在线授权
 *
 * - 机器码 = ANDROID_ID
 * - 启动时校验 + 运行中每 60 秒心跳续租
 * - 授权失败静默拒绝，不向用户展示原因
 */
object LicenseManager {

    private const val TAG = "LicenseMgr"

    /** 保活校验间隔 */
    const val KEEPALIVE_INTERVAL_MS = 60L * 1000

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    private val gson = Gson()

    sealed class Result {
        object Ok : Result()
        data class Denied(val machineCode: String, val msg: String) : Result()
    }

    @SuppressLint("HardwareIds")
    fun getMachineCode(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
    }

    /** 调用统一授权中心校验，失败时静默返回 Denied。 */
    suspend fun verify(context: Context): Result {
        val code = getMachineCode(context)
        return try {
            val cache = Storage.loadLicense()
            val resp = withContext(Dispatchers.IO) {
                httpPostVerify(
                    code = code,
                    sessionId = cache?.sessionId.orEmpty(),
                    leaseToken = cache?.leaseToken.orEmpty()
                )
            }
            if (resp.ok) {
                Storage.saveLicense(
                    Storage.LicenseCache(
                        machineCode = code,
                        lastVerifiedAt = System.currentTimeMillis(),
                        sessionId = resp.session_id.orEmpty(),
                        leaseToken = resp.lease_token.orEmpty(),
                        leaseSeconds = resp.lease_seconds ?: 0
                    )
                )
                Log.i(TAG, "[授权] 统一授权中心通过: $code")
                Result.Ok
            } else {
                Log.w(TAG, "[授权] 统一授权中心拒绝: ${resp.reason ?: resp.msg ?: "denied"}")
                Result.Denied(code, resp.reason ?: resp.msg ?: "denied")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[授权] 校验异常: ${e.message}")
            Result.Denied(code, e.message ?: "verify_error")
        }
    }

    private fun httpPostVerify(code: String, sessionId: String, leaseToken: String): VerifyResponse {
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val signature = sign(
            projectKey = Config.LICENSE_PROJECT_KEY,
            machineId = code,
            sessionId = sessionId,
            leaseToken = leaseToken,
            timestamp = timestamp,
            nonce = nonce
        )
        val req = VerifyRequest(
            project_key = Config.LICENSE_PROJECT_KEY,
            machine_id = code,
            session_id = sessionId,
            lease_token = leaseToken,
            timestamp = timestamp,
            nonce = nonce,
            signature = signature
        )
        val conn = URL(Config.LICENSE_VERIFY_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doInput = true
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(gson.toJson(req)) }

            val httpCode = conn.responseCode
            val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (httpCode !in 200..299) {
                return VerifyResponse(ok = false, reason = "http_$httpCode", msg = body.take(100))
            }
            return gson.fromJson(body, VerifyResponse::class.java)
                ?: VerifyResponse(ok = false, reason = "empty_response")
        } finally {
            conn.disconnect()
        }
    }

    private fun sign(
        projectKey: String,
        machineId: String,
        sessionId: String,
        leaseToken: String,
        timestamp: Long,
        nonce: String
    ): String {
        val payload = listOf(projectKey, machineId, sessionId, leaseToken, timestamp.toString(), nonce).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Config.LICENSE_SIGNING_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private data class VerifyRequest(
        val project_key: String,
        val machine_id: String,
        val session_id: String,
        val lease_token: String,
        val timestamp: Long,
        val nonce: String,
        val signature: String
    )

    private data class VerifyResponse(
        val ok: Boolean = false,
        val reason: String? = null,
        val msg: String? = null,
        val message: String? = null,
        val session_id: String? = null,
        val lease_token: String? = null,
        val lease_seconds: Int? = null
    )
}
