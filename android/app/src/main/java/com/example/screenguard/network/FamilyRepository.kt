package com.example.screenguard.network

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.screenguard.data.PreferencesManager
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

object FamilyRepository {
    private const val TAG = "FamilyRepository"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Incident(
        val id: String = "",
        val deviceId: String = "",
        val packageName: String = "",
        val appName: String = "",
        val matchedWord: String = "",
        val screenshotBase64: String? = null,
        val screenshotUrl: String? = null,
        val timestamp: Long = 0L
    )

    data class FreeTimeRequest(
        val id: String = "",
        val durationMinutes: Int = 15,
        val requestedAt: Long = 0L,
        val status: String = "PENDING" // "PENDING" | "APPROVED" | "DENIED"
    )

    data class UninstallRequest(
        val id: String = "",
        val reason: String = "",
        val wordCount: Int = 0,
        val requestedAt: Long = 0L,
        val status: String = "PENDING" // "PENDING" | "APPROVED" | "DENIED"
    )

    data class ChildRules(
        val blockIncognito: Boolean = true,
        val familyDns: Boolean = true,
        val freeTimeUntil: Long = 0L,
        val uninstallUnlockedUntil: Long = 0L
    )

    // ==========================================
    // CHILD ACTIONS
    // ==========================================

    fun pushIncident(
        context: Context,
        bitmap: Bitmap?,
        packageName: String,
        appName: String,
        matchedWord: String
    ) {
        scope.launch {
            try {
                val prefs = PreferencesManager.getInstance(context)
                val parentId = prefs.getParentId()
                val childDeviceId = prefs.getDeviceId()

                val timestamp = System.currentTimeMillis()
                val incidentId = "inc_${timestamp}_${UUID.randomUUID().toString().take(6)}"

                var base64Thumbnail: String? = null
                var imageBytes: ByteArray? = null

                if (bitmap != null) {
                    try {
                        val maxDimension = 640
                        val scaled = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            val ratio = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                            val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                            val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                        } else {
                            bitmap
                        }

                        val stream = ByteArrayOutputStream()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 60, stream)
                        } else {
                            scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                        }
                        imageBytes = stream.toByteArray()
                        base64Thumbnail = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed compressing screenshot", e)
                    }
                }

                val db = FirebaseDatabase.getInstance()
                val targetRef = if (parentId.isNotEmpty()) {
                    db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("incidents").child(incidentId)
                } else {
                    db.getReference("incidents").child(incidentId)
                }

                val incidentData = mutableMapOf<String, Any>(
                    "id" to incidentId,
                    "deviceId" to childDeviceId,
                    "packageName" to packageName,
                    "appName" to appName,
                    "matchedWord" to matchedWord,
                    "timestamp" to timestamp
                )

                if (base64Thumbnail != null) {
                    incidentData["screenshotBase64"] = base64Thumbnail
                }

                targetRef.setValue(incidentData)

                // Optional high-res Storage upload
                if (imageBytes != null) {
                    try {
                        val storageRef = FirebaseStorage.getInstance().reference
                            .child("screenshots")
                            .child("${incidentId}.webp")
                        storageRef.putBytes(imageBytes).addOnSuccessListener {
                            storageRef.downloadUrl.addOnSuccessListener { uri ->
                                targetRef.child("screenshotUrl").setValue(uri.toString())
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Storage upload skipped", e)
                    }
                }

                // Heartbeat
                if (parentId.isNotEmpty()) {
                    db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("info").child("lastActive").setValue(timestamp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in pushIncident", e)
            }
        }
    }

    fun requestFreeTime(
        context: Context,
        durationMinutes: Int,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val prefs = PreferencesManager.getInstance(context)
        val parentId = prefs.getParentId()
        val childDeviceId = prefs.getDeviceId()

        if (parentId.isEmpty()) {
            onResult(false, "Device is not paired with a parent yet.")
            return
        }

        val db = FirebaseDatabase.getInstance()
        val reqId = "ft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
        val reqRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests").child("free_time").child(reqId)

        val data = mapOf(
            "id" to reqId,
            "durationMinutes" to durationMinutes,
            "requestedAt" to System.currentTimeMillis(),
            "status" to "PENDING"
        )

        reqRef.setValue(data)
            .addOnSuccessListener { onResult(true, "Request sent for $durationMinutes minutes break.") }
            .addOnFailureListener { onResult(false, it.message ?: "Failed sending request.") }
    }

    fun requestUninstall(
        context: Context,
        reason: String,
        wordCount: Int,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val prefs = PreferencesManager.getInstance(context)
        val parentId = prefs.getParentId()
        val childDeviceId = prefs.getDeviceId()

        if (parentId.isEmpty()) {
            onResult(false, "Device is not paired with a parent.")
            return
        }

        val db = FirebaseDatabase.getInstance()
        val reqId = "uninst_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
        val reqRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests").child("uninstall").child(reqId)

        val data = mapOf(
            "id" to reqId,
            "reason" to reason,
            "wordCount" to wordCount,
            "requestedAt" to System.currentTimeMillis(),
            "status" to "PENDING"
        )

        reqRef.setValue(data)
            .addOnSuccessListener { onResult(true, "Uninstallation request sent to parent.") }
            .addOnFailureListener { onResult(false, it.message ?: "Failed sending request.") }
    }

    fun attachChildRulesListener(
        context: Context,
        onUpdate: (rules: ChildRules) -> Unit
    ): ValueEventListener? {
        val prefs = PreferencesManager.getInstance(context)
        val parentId = prefs.getParentId()
        val childDeviceId = prefs.getDeviceId()

        if (parentId.isEmpty()) return null

        val db = FirebaseDatabase.getInstance()
        val rulesRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("rules")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val freeTimeUntil = snapshot.child("freeTimeUntil").getValue(Long::class.java) ?: 0L
                val uninstallUnlockedUntil = snapshot.child("uninstallUnlockedUntil").getValue(Long::class.java) ?: 0L
                val blockIncognito = snapshot.child("blockIncognito").getValue(Boolean::class.java) ?: true
                val familyDns = snapshot.child("familyDns").getValue(Boolean::class.java) ?: true

                prefs.setFreeTimeUntil(freeTimeUntil)
                prefs.setUninstallUnlockedUntil(uninstallUnlockedUntil)

                val rules = ChildRules(
                    blockIncognito = blockIncognito,
                    familyDns = familyDns,
                    freeTimeUntil = freeTimeUntil,
                    uninstallUnlockedUntil = uninstallUnlockedUntil
                )
                mainHandler.post { onUpdate(rules) }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Child rules cancelled: ${error.message}")
            }
        }

        rulesRef.addValueEventListener(listener)
        return listener
    }

    // ==========================================
    // PARENT ACTIONS
    // ==========================================

    fun attachIncidentsListener(
        parentId: String,
        childDeviceId: String,
        onIncidents: (List<Incident>) -> Unit
    ): ValueEventListener {
        val db = FirebaseDatabase.getInstance()
        val ref = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("incidents")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Incident>()
                for (child in snapshot.children) {
                    val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                    val devId = child.child("deviceId").getValue(String::class.java) ?: childDeviceId
                    val pkg = child.child("packageName").getValue(String::class.java) ?: ""
                    val appName = child.child("appName").getValue(String::class.java) ?: "App"
                    val word = child.child("matchedWord").getValue(String::class.java) ?: ""
                    val base64 = child.child("screenshotBase64").getValue(String::class.java)
                    val url = child.child("screenshotUrl").getValue(String::class.java)
                    val ts = child.child("timestamp").getValue(Long::class.java) ?: 0L

                    list.add(Incident(id, devId, pkg, appName, word, base64, url, ts))
                }
                val sorted = list.sortedByDescending { it.timestamp }
                mainHandler.post { onIncidents(sorted) }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Incidents listener error: ${error.message}")
            }
        }

        ref.limitToLast(60).addValueEventListener(listener)
        return listener
    }

    fun attachRequestsListener(
        parentId: String,
        childDeviceId: String,
        onFreeTime: (List<FreeTimeRequest>) -> Unit,
        onUninstall: (List<UninstallRequest>) -> Unit
    ): ValueEventListener {
        val db = FirebaseDatabase.getInstance()
        val ref = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Free Time requests
                val ftList = mutableListOf<FreeTimeRequest>()
                for (c in snapshot.child("free_time").children) {
                    val id = c.child("id").getValue(String::class.java) ?: c.key ?: ""
                    val duration = c.child("durationMinutes").getValue(Int::class.java) ?: 15
                    val reqAt = c.child("requestedAt").getValue(Long::class.java) ?: 0L
                    val status = c.child("status").getValue(String::class.java) ?: "PENDING"
                    ftList.add(FreeTimeRequest(id, duration, reqAt, status))
                }
                val sortedFt = ftList.sortedByDescending { it.requestedAt }

                // Uninstall requests
                val uninstList = mutableListOf<UninstallRequest>()
                for (c in snapshot.child("uninstall").children) {
                    val id = c.child("id").getValue(String::class.java) ?: c.key ?: ""
                    val reason = c.child("reason").getValue(String::class.java) ?: ""
                    val wc = c.child("wordCount").getValue(Int::class.java) ?: 0
                    val reqAt = c.child("requestedAt").getValue(Long::class.java) ?: 0L
                    val status = c.child("status").getValue(String::class.java) ?: "PENDING"
                    uninstList.add(UninstallRequest(id, reason, wc, reqAt, status))
                }
                val sortedUninst = uninstList.sortedByDescending { it.requestedAt }

                mainHandler.post {
                    onFreeTime(sortedFt)
                    onUninstall(sortedUninst)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Requests listener error: ${error.message}")
            }
        }

        ref.addValueEventListener(listener)
        return listener
    }

    fun approveFreeTime(parentId: String, childDeviceId: String, requestId: String, durationMinutes: Int) {
        val db = FirebaseDatabase.getInstance()
        val until = System.currentTimeMillis() + (durationMinutes * 60 * 1000)

        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests").child("free_time").child(requestId)
            .child("status").setValue("APPROVED")

        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("rules")
            .child("freeTimeUntil").setValue(until)
    }

    fun rejectFreeTime(parentId: String, childDeviceId: String, requestId: String) {
        val db = FirebaseDatabase.getInstance()
        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests").child("free_time").child(requestId)
            .child("status").setValue("DENIED")
    }

    fun approveUninstall(parentId: String, childDeviceId: String, requestId: String) {
        val db = FirebaseDatabase.getInstance()
        val until = System.currentTimeMillis() + (15 * 60 * 1000) // 15-minute window to uninstall

        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests").child("uninstall").child(requestId)
            .child("status").setValue("APPROVED")

        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("rules")
            .child("uninstallUnlockedUntil").setValue(until)
    }

    fun rejectUninstall(parentId: String, childDeviceId: String, requestId: String) {
        val db = FirebaseDatabase.getInstance()
        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests").child("uninstall").child(requestId)
            .child("status").setValue("DENIED")
    }

    fun grantInstantFreeTime(parentId: String, childDeviceId: String, durationMinutes: Int) {
        val db = FirebaseDatabase.getInstance()
        val until = System.currentTimeMillis() + (durationMinutes * 60 * 1000)
        db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("rules")
            .child("freeTimeUntil").setValue(until)
    }
}
