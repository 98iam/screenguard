package com.example.screenguard.network

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

object PairingManager {
    private const val TAG = "ScreenGuardPairing"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PairingSession(
        val code: String,
        val parentId: String,
        val parentName: String,
        val status: String, // "WAITING" | "PAIRED" | "EXPIRED"
        val createdAt: Long,
        val expiresAt: Long,
        val childDeviceId: String? = null,
        val childModel: String? = null
    )

    /**
     * Called by Parent App:
     * Generates a 6-digit random code (e.g. 748-291) and writes it to Firebase.
     * Listens for the child device to submit the matching code.
     */
    fun createPairingCode(
        parentId: String,
        parentName: String,
        onCodeGenerated: (code: String) -> Unit,
        onChildPaired: (childDeviceId: String, childModel: String) -> Unit,
        onError: (message: String) -> Unit
    ): ValueEventListener? {
        val rawCode = (100000..999999).random().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + (15 * 60 * 1000) // 15 minutes validity

        val db = FirebaseDatabase.getInstance()
        val pairingRef = db.getReference("devices").child("pairings").child(rawCode)

        val sessionData = mapOf(
            "code" to rawCode,
            "parentId" to parentId,
            "parentName" to parentName,
            "status" to "WAITING",
            "createdAt" to now,
            "expiresAt" to expiresAt
        )

        pairingRef.setValue(sessionData)
            .addOnSuccessListener {
                mainHandler.post { onCodeGenerated(rawCode) }
            }
            .addOnFailureListener { err ->
                mainHandler.post { onError("Failed to create pairing session: ${err.message}") }
            }

        // Real-time listener waiting for child to link
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: "WAITING"
                if (status == "PAIRED") {
                    val childDeviceId = snapshot.child("childDeviceId").getValue(String::class.java) ?: ""
                    val childModel = snapshot.child("childModel").getValue(String::class.java) ?: "Android Device"
                    if (childDeviceId.isNotEmpty()) {
                        mainHandler.post { onChildPaired(childDeviceId, childModel) }
                        pairingRef.removeEventListener(this)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Pairing listener cancelled: ${error.message}")
            }
        }

        pairingRef.addValueEventListener(listener)
        return listener
    }

    /**
     * Called by Child App:
     * Validates the 6-digit code entered by user. If valid and not expired,
     * updates the session to PAIRED and links the device.
     */
    fun linkChildWithCode(
        inputCode: String,
        childDeviceId: String,
        childModel: String,
        onSuccess: (parentId: String, parentName: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        val cleanCode = inputCode.replace("-", "").trim()
        if (cleanCode.length != 6) {
            mainHandler.post { onError("Code must be 6 digits") }
            return
        }

        val db = FirebaseDatabase.getInstance()
        val pairingRef = db.getReference("devices").child("pairings").child(cleanCode)

        pairingRef.runTransaction(object : Transaction.Handler {
            var parentId = ""
            var parentName = ""
            var abortMessage = ""

            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val value = currentData.value as? Map<*, *>
                if (value == null) {
                    abortMessage = "Invalid pairing code. Please check and try again."
                    return Transaction.abort()
                }

                val status = value["status"] as? String ?: ""
                val expiresAt = (value["expiresAt"] as? Number)?.toLong() ?: 0L

                if (System.currentTimeMillis() > expiresAt || status == "EXPIRED") {
                    abortMessage = "Pairing code has expired. Please generate a new code on the Parent device."
                    return Transaction.abort()
                }

                if (status == "PAIRED") {
                    abortMessage = "This code has already been used."
                    return Transaction.abort()
                }

                parentId = value["parentId"] as? String ?: ""
                parentName = value["parentName"] as? String ?: "Parent"

                if (parentId.isEmpty()) {
                    abortMessage = "Corrupted pairing session."
                    return Transaction.abort()
                }

                currentData.child("status").value = "PAIRED"
                currentData.child("childDeviceId").value = childDeviceId
                currentData.child("childModel").value = childModel
                currentData.child("pairedAt").value = System.currentTimeMillis()

                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (error != null) {
                    mainHandler.post { onError("Database error: ${error.message}") }
                    return
                }

                if (!committed) {
                    mainHandler.post { onError(abortMessage.ifEmpty { "Failed to pair device." }) }
                    return
                }

                // Explicitly sync pairing node to guarantee listener trigger on parent
                pairingRef.updateChildren(
                    mapOf(
                        "status" to "PAIRED",
                        "childDeviceId" to childDeviceId,
                        "childModel" to childModel,
                        "pairedAt" to System.currentTimeMillis()
                    )
                )

                // Register child under family channel atomically
                val familyRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId)
                familyRef.child("info").setValue(
                    mapOf(
                        "childDeviceId" to childDeviceId,
                        "childModel" to childModel,
                        "pairedAt" to System.currentTimeMillis(),
                        "lastActive" to System.currentTimeMillis(),
                        "status" to "ACTIVE"
                    )
                )
                // Initialize permanent rules
                familyRef.child("rules").setValue(
                    mapOf(
                        "blockIncognito" to true,
                        "familyDns" to true,
                        "freeTimeUntil" to 0L,
                        "uninstallUnlockedUntil" to 0L,
                        "blockedWords" to com.example.screenguard.data.PRESET_18PLUS_MANHWA.toList(),
                        "blockedPackages" to com.example.screenguard.data.DEFAULT_BLOCKED_PACKAGES.toList()
                    )
                )

                mainHandler.post { onSuccess(parentId, parentName) }
            }
        })
    }
}
