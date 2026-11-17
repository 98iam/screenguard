package com.example.screenguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Incident(
    val id: String,
    val deviceId: String,
    val packageName: String,
    val appName: String,
    val matchedWord: String,
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

    private val imageCache = ConcurrentHashMap<String, Bitmap>()

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
        var viewingScreenshotUrl by remember { mutableStateOf<String?>(null) }

        fun refreshData() {
            if (serverUrl.isBlank()) return
            isRefreshing = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cleanUrl = serverUrl.trim().trimEnd('/')

                    // 1. Fetch Incidents
                    val incReq = Request.Builder().url("$cleanUrl/api/incidents?limit=40").build()
                    client.newCall(incReq).execute().use { res ->
                        if (res.isSuccessful) {
                            val json = JSONObject(res.body?.string() ?: "{}")
                            val arr = json.optJSONArray("incidents") ?: JSONArray()
                            val list = mutableListOf<Incident>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(
                                    Incident(
                                        id = obj.optString("id"),
                                        deviceId = obj.optString("device_id", "Android"),
                                        packageName = obj.optString("package_name"),
                                        appName = obj.optString("app_name", "Unknown"),
                                        matchedWord = obj.optString("matched_word"),
                                        screenshotUrl = obj.optString("screenshot_url").takeIf { it.isNotBlank() },
                                        timestamp = obj.optLong("timestamp")
                                    )
                                )
                            }
                            withContext(Dispatchers.Main) { incidents = list }
                        }
                    }

                    // 2. Fetch Pause Requests
                    val reqReq = Request.Builder().url("$cleanUrl/api/requests").build()
                    client.newCall(reqReq).execute().use { res ->
                        if (res.isSuccessful) {
                            val json = JSONObject(res.body?.string() ?: "{}")
                            val arr = json.optJSONArray("requests") ?: JSONArray()
                            val list = mutableListOf<PauseRequest>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                list.add(
                                    PauseRequest(
                                        id = obj.optString("id"),
                                        deviceId = obj.optString("device_id", "Android"),
                                        durationMinutes = obj.optInt("duration_minutes", 15),
                                        status = obj.optString("status", "PENDING"),
                                        requestedAt = obj.optLong("requested_at"),
                                        approvedUntil = obj.optLong("approved_until")
                                    )
                                )
                            }
                            withContext(Dispatchers.Main) { pauseRequests = list }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GuardianActivity, "Sync Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) { isRefreshing = false }
                }
            }
        }

        fun respondToRequest(requestId: String, decision: String) {
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
                                Toast.makeText(this@GuardianActivity, "Request $decision!", Toast.LENGTH_SHORT).show()
                                refreshData()
                            } else {
                                Toast.makeText(this@GuardianActivity, "Error: ${res.code}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GuardianActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        LaunchedEffect(serverUrl) {
            refreshData()
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
                                if (serverUrl.isBlank()) "No Cloudflare Server Configured" else "Connected to Cloudflare",
                                fontSize = 11.sp,
                                color = if (serverUrl.isBlank()) Color(0xFFEF4444) else Color(0xFF10B981)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        IconButton(onClick = { refreshData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
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
                            subtitle = "When a blocked keyword or bad content is detected, the screenshot and details will appear here.",
                            onRefresh = { refreshData() }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(incidents, key = { it.id }) { item ->
                                IncidentCard(
                                    incident = item,
                                    onViewScreenshot = { url -> viewingScreenshotUrl = url },
                                    loadImage = { url, onLoaded ->
                                        loadCachedImage(url, onLoaded)
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
                            onRefresh = { refreshData() }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(pauseRequests, key = { it.id }) { req ->
                                PauseRequestCard(
                                    request = req,
                                    onApprove = { respondToRequest(req.id, "APPROVED") },
                                    onReject = { respondToRequest(req.id, "REJECTED") }
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
        viewingScreenshotUrl?.let { url ->
            Dialog(onDismissRequest = { viewingScreenshotUrl = null }) {
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
                            IconButton(onClick = { viewingScreenshotUrl = null }) {
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
                            LaunchedEffect(url) {
                                loadCachedImage(url) { bmp = it }
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
                title = { Text("Cloudflare Backend URL") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Enter the URL of your deployed Cloudflare Worker:",
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
                        refreshData()
                    }) {
                        Text("Save & Connect")
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

    private fun loadCachedImage(url: String, onLoaded: (Bitmap?) -> Unit) {
        val cached = imageCache[url]
        if (cached != null) {
            onLoaded(cached)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        res.body?.byteStream()?.use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) {
                                imageCache[url] = bmp
                                withContext(Dispatchers.Main) { onLoaded(bmp) }
                                return@launch
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            withContext(Dispatchers.Main) { onLoaded(null) }
        }
    }
}

@Composable
fun IncidentCard(
    incident: Incident,
    onViewScreenshot: (String) -> Unit,
    loadImage: (String, (Bitmap?) -> Unit) -> Unit
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
                            text = incident.packageName,
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
            if (!incident.screenshotUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                var bitmap by remember { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(incident.screenshotUrl) {
                    loadImage(incident.screenshotUrl) { bitmap = it }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable { onViewScreenshot(incident.screenshotUrl) },
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
                                "Tap to View Full",
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
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
        }
    }
}
