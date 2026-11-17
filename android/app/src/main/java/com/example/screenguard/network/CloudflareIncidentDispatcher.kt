package com.example.screenguard.network

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object CloudflareIncidentDispatcher {
    private const val TAG = "ScreenGuardCloudflare"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    fun getDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val model = Build.MODEL ?: "Android"
            "${model}_${androidId.take(6)}"
        } catch (e: Exception) {
            "AndroidDevice"
        }
    }

    /**
     * Uploads the captured screenshot along with incident metadata to Cloudflare Worker.
     */
    fun dispatchIncident(
        context: Context,
        endpointUrl: String,
        bitmap: Bitmap?,
        packageName: String,
        appName: String,
        matchedWord: String
    ) {
        if (endpointUrl.isBlank()) {
            Log.d(TAG, "No Cloudflare endpoint configured, skipping upload.")
            return
        }

        scope.launch {
            try {
                val cleanUrl = endpointUrl.trim().trimEnd('/')
                val uploadUrl = "$cleanUrl/api/incidents"
                val deviceId = getDeviceId(context)
                val timestamp = System.currentTimeMillis()

                val multipartBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("device_id", deviceId)
                    .addFormDataPart("package_name", packageName)
                    .addFormDataPart("app_name", appName)
                    .addFormDataPart("matched_word", matchedWord)
                    .addFormDataPart("timestamp", timestamp.toString())

                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, stream)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                    }
                    val imageBytes = stream.toByteArray()
                    val mediaType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        "image/webp".toMediaTypeOrNull()
                    } else {
                        "image/jpeg".toMediaTypeOrNull()
                    }

                    multipartBuilder.addFormDataPart(
                        "screenshot",
                        "incident_${timestamp}.webp",
                        imageBytes.toRequestBody(mediaType)
                    )
                }

                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBuilder.build())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "✓ Incident uploaded successfully to Cloudflare!")
                    } else {
                        Log.w(TAG, "Incident upload failed with code: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading incident to Cloudflare: ${e.message}", e)
            }
        }
    }

    /**
     * Requests a temporary pause from the accountability guardian.
     */
    fun requestPause(
        context: Context,
        endpointUrl: String,
        durationMinutes: Int,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (endpointUrl.isBlank()) {
            onComplete(false, "Cloudflare endpoint not configured")
            return
        }

        scope.launch {
            try {
                val cleanUrl = endpointUrl.trim().trimEnd('/')
                val url = "$cleanUrl/api/requests/pause"
                val deviceId = getDeviceId(context)

                val json = JSONObject().apply {
                    put("device_id", deviceId)
                    put("duration_minutes", durationMinutes)
                    put("timestamp", System.currentTimeMillis())
                }

                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url(url).post(body).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        onComplete(true, "Request sent to Guardian!")
                    } else {
                        onComplete(false, "Server returned code: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                onComplete(false, e.message ?: "Network error")
            }
        }
    }
}
