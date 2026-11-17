package com.example.screenguard.network

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.screenguard.data.PreferencesManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

object FirebaseIncidentDispatcher {
    private const val TAG = "ScreenGuardFirebase"
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Dispatches an incident report with optional downscaled screenshot to Firebase.
     */
    fun dispatchIncident(
        context: Context,
        bitmap: Bitmap?,
        packageName: String,
        appName: String,
        matchedWord: String
    ) {
        scope.launch {
            try {
                val prefs = PreferencesManager.getInstance(context)
                val deviceId = prefs.getDeviceId()
                val timestamp = System.currentTimeMillis()
                val incidentId = "inc_${timestamp}_${UUID.randomUUID().toString().take(6)}"

                var base64Thumbnail: String? = null
                var imageBytes: ByteArray? = null

                if (bitmap != null) {
                    try {
                        val maxDimension = 640
                        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            val ratio = minOf(
                                maxDimension.toFloat() / bitmap.width,
                                maxDimension.toFloat() / bitmap.height
                            )
                            val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                            val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                        } else {
                            bitmap
                        }

                        val stream = ByteArrayOutputStream()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            scaledBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 60, stream)
                        } else {
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                        }
                        imageBytes = stream.toByteArray()
                        base64Thumbnail = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed compressing screenshot", e)
                    }
                }

                val db = FirebaseDatabase.getInstance()
                val incidentRef = db.getReference("incidents").child(incidentId)

                val incidentData = mutableMapOf<String, Any>(
                    "id" to incidentId,
                    "deviceId" to deviceId,
                    "packageName" to packageName,
                    "appName" to appName,
                    "matchedWord" to matchedWord,
                    "timestamp" to timestamp
                )

                if (base64Thumbnail != null) {
                    incidentData["screenshotBase64"] = base64Thumbnail
                }

                // Push to Realtime Database
                incidentRef.setValue(incidentData)
                    .addOnSuccessListener {
                        Log.i(TAG, "✓ Incident $incidentId pushed to Firebase RTDB")
                    }
                    .addOnFailureListener { err ->
                        Log.e(TAG, "Failed pushing incident to Firebase RTDB", err)
                    }

                // If Storage is accessible, optionally upload high-res WEBP
                if (imageBytes != null) {
                    try {
                        val storageRef = FirebaseStorage.getInstance().reference
                            .child("screenshots")
                            .child("${incidentId}.webp")

                        storageRef.putBytes(imageBytes)
                            .addOnSuccessListener {
                                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                                    incidentRef.child("screenshotUrl").setValue(downloadUri.toString())
                                }
                            }
                            .addOnFailureListener {
                                // RTDB already has the base64 fallback, so non-fatal
                                Log.w(TAG, "Storage upload skipped/failed, RTDB base64 preserved")
                            }
                    } catch (e: Exception) {
                        Log.w(TAG, "Firebase Storage not initialized or accessible", e)
                    }
                }

                // Update device heartbeat
                val deviceRef = db.getReference("devices").child(deviceId)
                deviceRef.child("lastActive").setValue(timestamp)
                deviceRef.child("deviceName").setValue(Build.MODEL ?: "Android Device")

            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatchIncident", e)
            }
        }
    }

    /**
     * Submits a request for a temporary break/pause to the Guardian.
     */
    fun requestPause(
        context: Context,
        durationMinutes: Int = 15,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val prefs = PreferencesManager.getInstance(context)
        val deviceId = prefs.getDeviceId()
        val timestamp = System.currentTimeMillis()
        val requestId = "req_${timestamp}_${UUID.randomUUID().toString().take(6)}"

        val requestData = mapOf(
            "id" to requestId,
            "deviceId" to deviceId,
            "durationMinutes" to durationMinutes,
            "status" to "PENDING",
            "requestedAt" to timestamp,
            "approvedUntil" to 0L
        )

        val db = FirebaseDatabase.getInstance()
        db.getReference("pause_requests").child(requestId).setValue(requestData)
            .addOnSuccessListener {
                onComplete(true, requestId)
            }
            .addOnFailureListener { e ->
                onComplete(false, e.localizedMessage)
            }
    }

    /**
     * Listens for changes to the device's pause status in Firebase Realtime Database.
     */
    fun attachPauseStatusListener(
        context: Context,
        onStatusChange: (isPaused: Boolean, pauseUntil: Long) -> Unit
    ): ValueEventListener {
        val prefs = PreferencesManager.getInstance(context)
        val deviceId = prefs.getDeviceId()
        val db = FirebaseDatabase.getInstance()
        val deviceRef = db.getReference("devices").child(deviceId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isPaused = snapshot.child("isPaused").getValue(Boolean::class.java) ?: false
                val pauseUntil = snapshot.child("pauseUntil").getValue(Long::class.java) ?: 0L
                val now = System.currentTimeMillis()

                if (isPaused && pauseUntil > now) {
                    prefs.setPauseUntil(pauseUntil)
                    onStatusChange(true, pauseUntil)
                } else {
                    if (prefs.isAppPaused()) {
                        prefs.clearPause()
                    }
                    onStatusChange(false, 0L)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Pause status listener cancelled: ${error.message}")
            }
        }

        deviceRef.addValueEventListener(listener)
        return listener
    }
}
