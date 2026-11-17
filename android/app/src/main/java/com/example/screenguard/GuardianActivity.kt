package com.example.screenguard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.screenguard.data.PreferencesManager
import com.google.firebase.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class Incident(
    val id: String,
    val deviceId: String,
    val packageName: String,
    val appName: String,
    val matchedWord: String,
    val screenshotBase64: String?,
    val screenshotUrl: String?,
    val timestamp: Long
)

data class PauseRequest(
    val id: String,
    val deviceId: String,
    val durationMinutes: Int,
    val status: String,
    val requestedAt: Long,
    val approvedUntil: Long
)

class GuardianActivity : ComponentActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // LruCache bounded by memory (max 30MB) to prevent OOM
    private val imageCache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager.getInstance(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    surfaceVariant = Color(0xFF334155),
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GuardianScreen(prefs = prefs)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun GuardianScreen(prefs: PreferencesManager) {
        val serverUrlState = prefs.cloudflareEndpoint.collectAsState()
        var serverUrl by remember { mutableStateOf(serverUrlState.value) }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableIntStateOf(0) }
        var isRefreshing by remember { mutableStateOf(false) }

        var incidents by remember { mutableStateOf<List<Incident>>(emptyList()) }
        var pauseRequests by remember { mutableStateOf<List<PauseRequest>>(emptyList()) }
        var viewingScreenshotPayload by remember { mutableStateOf<Pair<String?, String?>?>(null) }
        var isFirebaseActive by remember { mutableStateOf(false) }

        // 1. Firebase Real-time Synchronization
        DisposableEffect(Unit) {
            val db = try {
                FirebaseDatabase.getInstance()
            } catch (e: Exception) {
                null
            }

            if (db != null) {
                isFirebaseActive = true

                // Real-time Incidents listener
                val incidentsRef = db.getReference("incidents")
                val incidentsListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val list = mutableListOf<Incident>()
                        for (child in snapshot.children) {
                            val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                            val deviceId = child.child("deviceId").getValue(String::class.java) ?: "Android"
                            val packageName = child.child("packageName").getValue(String::class.java) ?: ""
                            val appName = child.child("appName").getValue(String::class.java) ?: "Unknown"
                            val matchedWord = child.child("matchedWord").getValue(String::class.java) ?: ""
                            val screenshotBase64 = child.child("screenshotBase64").getValue(String::class.java)
                            val screenshotUrl = child.child("screenshotUrl").getValue(String::class.java)
                            val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L

                            list.add(
                                Incident(
                                    id = id,
                                    deviceId = deviceId,
                                    packageName = packageName,
                                    appName = appName,
                                    matchedWord = matchedWord,
                                    screenshotBase64 = screenshotBase64,
                                    screenshotUrl = screenshotUrl,
                                    timestamp = timestamp
                                )
                            )
                        }
                        // Sort newest first
                        incidents = list.sortedByDescending { it.timestamp }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("GuardianActivity", "Incidents listener error: ${error.message}")
                    }
                }
                incidentsRef.limitToLast(50).addValueEventListener(incidentsListener)

                // Real-time Pause Requests listener
                val requestsRef = db.getReference("pause_requests")
                val requestsListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val list = mutableListOf<PauseRequest>()
                        for (child in snapshot.children) {
                            val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                            val deviceId = child.child("deviceId").getValue(String::class.java) ?: "Android"
                            val durationMinutes = child.child("durationMinutes").getValue(Int::class.java) ?: 15
                            val status = child.child("status").getValue(String::class.java) ?: "PENDING"
                            val requestedAt = child.child("requestedAt").getValue(Long::class.java) ?: 0L
                            val approvedUntil = child.child("approvedUntil").getValue(Long::class.java) ?: 0L

                            list.add(
                                PauseRequest(
                                    id = id,
                                    deviceId = deviceId,
                                    durationMinutes = durationMinutes,
                                    status = status,
                                    requestedAt = requestedAt,
                                    approvedUntil = approvedUntil
                                )
                            )
                        }
                        pauseRequests = list.sortedByDescending { it.requestedAt }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("GuardianActivity", "Pause requests listener error: ${error.message}")
                    }
                }
                requestsRef.limitToLast(20).addValueEventListener(requestsListener)

                onDispose {
                    incidentsRef.removeEventListener(incidentsListener)
                    requestsRef.removeEventListener(requestsListener)
                }
            } else {
                onDispose {}
            }
        }

        fun respondToFirebaseRequest(request: PauseRequest, decision: String) {
            val db = FirebaseDatabase.getInstance()
            val reqRef = db.getReference("pause_requests").child(request.id)
            val now = System.currentTimeMillis()

            if (decision == "APPROVED") {
                val durationMs = request.durationMinutes * 60 * 1000L
                val approvedUntil = now + durationMs

                reqRef.child("status").setValue("APPROVED")
                reqRef.child("approvedUntil").setValue(approvedUntil)

                // Update device pause state in Firebase
                val deviceRef = db.getReference("devices").child(request.deviceId)
                deviceRef.child("isPaused").setValue(true)
                deviceRef.child("pauseUntil").setValue(approvedUntil)

                Toast.makeText(this@GuardianActivity, "✓ Break Approved for ${request.durationMinutes} mins!", Toast.LENGTH_SHORT).show()
            } else {
                reqRef.child("status").setValue("REJECTED")
                val deviceRef = db.getReference("devices").child(request.deviceId)
                deviceRef.child("isPaused").setValue(false)
                deviceRef.child("pauseUntil").setValue(0L)

                Toast.makeText(this@GuardianActivity, "✕ Break Request Denied", Toast.LENGTH_SHORT).show()
            }
        }

        fun respondToCloudflareRequest(requestId: String, decision: String) {
            if (serverUrl.isBlank()) return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cleanUrl = serverUrl.trim().trimEnd('/')
                    val json = JSONObject().apply {
                        put("request_id", requestId)
                        put("decision", decision)
                    }
                    val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val req = Request.Builder().url("$cleanUrl/api/requests/respond").post(body).build()
                    client.newCall(req).execute().use { res ->
                        withContext(Dispatchers.Main) {
                            if (res.isSuccessful) {
                                Toast.makeText(this@GuardianActivity, "Cloudflare: Request $decision", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GuardianActivity, "Cloudflare error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "ScreenGuard Guardian",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Text(
                                if (isFirebaseActive) "● Firebase Live Sync Active" else "Offline / Cloudflare",
                                fontSize = 11.sp,
                                color = if (isFirebaseActive) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Shield, contentDescription = "Incidents") },
                        label = { Text("Incidents (${incidents.size})") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.AccessTime, contentDescription = "Requests") },
                        label = {
                            val pending = pauseRequests.count { it.status == "PENDING" }
                            Text(if (pending > 0) "Requests ($pending)" else "Requests")
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (selectedTab == 0) {
                    // Incidents Feed
                    if (incidents.isEmpty()) {
                        EmptyState(
                            title = "No Incidents Recorded",
                            subtitle = "When a blocked keyword or bad content is detected, the screenshot and details will appear here in real time.",
                            onRefresh = {}
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(incidents, key = { it.id }) { item ->
                                IncidentCard(
                                    incident = item,
                                    onViewScreenshot = { base64, url ->
                                        viewingScreenshotPayload = Pair(base64, url)
                                    },
                                    loadImage = { base64, url, onLoaded ->
                                        loadCachedImage(base64, url, onLoaded)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Pause Requests
                    if (pauseRequests.isEmpty()) {
                        EmptyState(
                            title = "No Pause Requests",
                            subtitle = "When the monitored device requests a temporary 15-minute pause, you can approve or reject it here.",
                            onRefresh = {}
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(pauseRequests, key = { it.id }) { req ->
                                PauseRequestCard(
                                    request = req,
                                    onApprove = {
                                        respondToFirebaseRequest(req, "APPROVED")
                                        respondToCloudflareRequest(req.id, "APPROVED")
                                    },
                                    onReject = {
                                        respondToFirebaseRequest(req, "REJECTED")
                                        respondToCloudflareRequest(req.id, "REJECTED")
                                    }
                                )
                            }
                        }
                    }
                }

                if (isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Full Screen Screenshot Lightbox Dialog
        viewingScreenshotPayload?.let { (base64, url) ->
            Dialog(onDismissRequest = { viewingScreenshotPayload = null }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Offending Screenshot", fontWeight = FontWeight.Bold, color = Color.White)
                            IconButton(onClick = { viewingScreenshotPayload = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            var bmp by remember { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(base64, url) {
                                loadCachedImage(base64, url) { bmp = it }
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp!!.asImageBitmap(),
                                    contentDescription = "Full Screenshot",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        // Settings Dialog
        if (showSettingsDialog) {
            var tempUrl by remember { mutableStateOf(serverUrl) }
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Backend Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Firebase Realtime Database is active and handles real-time screenshot alerts.\n\nOptionally configure a Cloudflare Worker URL:",
                            fontSize = 13.sp,
                            color = Color.LightGray
                        )
                        OutlinedTextField(
                            value = tempUrl,
                            onValueChange = { tempUrl = it },
                            placeholder = { Text("https://screenguard-backend.workers.dev") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        serverUrl = tempUrl.trim()
                        prefs.setCloudflareEndpoint(serverUrl)
                        showSettingsDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    private fun loadCachedImage(base64: String?, url: String?, onLoaded: (Bitmap?) -> Unit) {
        val cacheKey = base64?.take(32) ?: url ?: return onLoaded(null)
        val cached = imageCache.get(cacheKey)
        if (cached != null) {
            onLoaded(cached)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. If base64 exists, decode directly
                if (!base64.isNullOrBlank()) {
                    val rawBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
                    val decodedBytes = Base64.decode(rawBase64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    if (bmp != null) {
                        imageCache.put(cacheKey, bmp)
                        withContext(Dispatchers.Main) { onLoaded(bmp) }
                        return@launch
                    }
                }

                // 2. Otherwise fetch from URL
                if (!url.isNullOrBlank()) {
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            res.body?.byteStream()?.use { stream ->
                                val bmp = BitmapFactory.decodeStream(stream)
                                if (bmp != null) {
                                    imageCache.put(cacheKey, bmp)
                                    withContext(Dispatchers.Main) { onLoaded(bmp) }
                                    return@launch
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GuardianActivity", "Image load error", e)
            }
            withContext(Dispatchers.Main) { onLoaded(null) }
        }
    }
}

@Composable
fun IncidentCard(
    incident: Incident,
    onViewScreenshot: (base64: String?, url: String?) -> Unit,
    loadImage: (base64: String?, url: String?, (Bitmap?) -> Unit) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFEF4444).copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Alert",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = incident.appName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${incident.packageName} · ${incident.deviceId}",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val dateStr = remember(incident.timestamp) {
                    val sdf = SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault())
                    sdf.format(Date(incident.timestamp))
                }
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Detected Keyword Tag
            Surface(
                color = Color(0xFF7F1D1D).copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "🚨 Blocked: ${incident.matchedWord}",
                    fontSize = 12.sp,
                    color = Color(0xFFFCA5A5),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Screenshot Thumbnail
            val hasImage = !incident.screenshotBase64.isNullOrBlank() || !incident.screenshotUrl.isNullOrBlank()
            if (hasImage) {
                Spacer(modifier = Modifier.height(12.dp))
                var bitmap by remember { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(incident.screenshotBase64, incident.screenshotUrl) {
                    loadImage(incident.screenshotBase64, incident.screenshotUrl) { bitmap = it }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable { onViewScreenshot(incident.screenshotBase64, incident.screenshotUrl) },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Screenshot Thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            Text(
                                "Tap to View Full Screen",
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading screenshot...", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PauseRequestCard(
    request: PauseRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (request.status == "PENDING") Color(0xFF1E293B) else Color(0xFF1E293B).copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Request from ${request.deviceId}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )

                Surface(
                    color = when (request.status) {
                        "APPROVED" -> Color(0xFF065F46)
                        "REJECTED" -> Color(0xFF7F1D1D)
                        else -> Color(0xFF78350F)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = request.status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (request.status) {
                            "APPROVED" -> Color(0xFF34D399)
                            "REJECTED" -> Color(0xFFF87171)
                            else -> Color(0xFFFBBF24)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Requested a temporary ${request.durationMinutes}-minute break from ScreenGuard.",
                fontSize = 13.sp,
                color = Color.LightGray
            )

            if (request.status == "PENDING") {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Approve 15m")
                    }

                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Deny")
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                subtitle,
                fontSize = 13.sp,
                color = Color.Gray,
                lineHeight = 18.sp
            )
        }
    }
}
