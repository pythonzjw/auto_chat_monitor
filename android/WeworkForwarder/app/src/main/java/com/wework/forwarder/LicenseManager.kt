package com.wework.forwarder

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 云端机器码授权
 *
 * - 机器码 = ANDROID_ID(无权限,设备级,卸载重装不变,恢复出厂会变)
 * - 启动时校验 + 运行中每 6 小时保活复检
 * - 任意一次 ok=false 立即拦截
 * - 网络异常时:本地缓存 6 小时宽限期内放行,超时拦截
 */
object LicenseManager {

    private const val TAG = "LicenseMgr"

    /** 网络异常时本地缓存的最大宽限,= 保活间隔 */
    private const val OFFLINE_GRACE_MS = 6L * 3600 * 1000

    /** 保活校验间隔,MainActivity 起循环用 */
    const val KEEPALIVE_INTERVAL_MS = 6L * 3600 * 1000

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

    /**
     * 调用云端校验,返回 Ok / Denied
     *
     * 顺序:联网校验 → 失败时读本地缓存判断是否在 6h 宽限期内
     */
    suspend fun verify(context: Context): Result {
        val code = getMachineCode(context)
        val now = System.currentTimeMillis()

        return try {
            val resp = withContext(Dispatchers.IO) { httpGetVerify(code) }
            if (resp.ok) {
                Storage.saveLicense(Storage.LicenseCache(code, now))
                Log.i(TAG, "[授权] 云端校验通过: $code")
                Result.Ok
            } else {
                Storage.clearLicense()
                val msg = resp.msg ?: "未授权"
                Log.w(TAG, "[授权] 云端拒绝: $msg")
                Result.Denied(code, msg)
            }
        } catch (e: Exception) {
            // 网络异常 → 6h 宽限内放过
            val cache = Storage.loadLicense()
            if (cache != null && cache.machineCode == code &&
                now - cache.lastVerifiedAt < OFFLINE_GRACE_MS) {
                Log.w(TAG, "[授权] 网络异常,本地宽限期内放行: ${e.message}")
                Result.Ok
            } else {
                Log.w(TAG, "[授权] 网络异常且无有效缓存: ${e.message}")
                Result.Denied(code, "网络不可用且本地授权已过期: ${e.message}")
            }
        }
    }

    private fun httpGetVerify(code: String): VerifyResponse {
        val url = URL("${Config.LICENSE_BASE_URL}/verify?code=${URLEncoder.encode(code, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doInput = true
            val httpCode = conn.responseCode
            // 非 2xx 一律视为未授权(读 errorStream 避免 IOException)
            val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (httpCode !in 200..299) {
                return VerifyResponse(ok = false, msg = "HTTP $httpCode: ${body.take(100)}")
            }
            return gson.fromJson(body, VerifyResponse::class.java)
                ?: VerifyResponse(ok = false, msg = "服务端返回空响应")
        } finally {
            conn.disconnect()
        }
    }

    private data class VerifyResponse(
        val ok: Boolean = false,
        val msg: String? = null
    )
}
