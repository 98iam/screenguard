package com.example.screenguard.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.screenguard.data.PreferencesManager
import com.example.screenguard.network.FamilyRepository
import com.example.screenguard.network.PairingManager
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

class ParentMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager.getInstance(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = CyberSky,
                    background = Slate950,
                    surface = Slate900,
                    surfaceVariant = Slate800,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Slate950
                ) {
                    ParentAppRoot(prefs = prefs)
                }
            }
        }
    }
}

@Composable
fun ParentAppRoot(prefs: PreferencesManager) {
    var pairedChildId by remember { mutableStateOf(prefs.getPairedChildId()) }
    var parentId by remember { mutableStateOf(prefs.getParentId().ifEmpty { prefs.getDeviceId() }) }

    // Request notification permission on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* permission result handled */ }
        LaunchedEffect(Unit) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (pairedChildId.isEmpty()) {
        ParentPairingScreen(
            parentId = parentId,
            onPaired = { childId, childModel ->
                prefs.setParentPairing(parentId, "Guardian Parent")
                prefs.setPairedChild(childId)
                pairedChildId = childId
            }
        )
    } else {
        ParentDashboardScreen(
            parentId = parentId,
            childDeviceId = pairedChildId,
            onUnpair = {
                prefs.setPairedChild("")
                pairedChildId = ""
            }
        )
    }
}

@Composable
fun ParentPairingScreen(
    parentId: String,
    onPaired: (childDeviceId: String, childModel: String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var listenerRef by remember { mutableStateOf<ValueEventListener?>(null) }

    DisposableEffect(pairingCode) {
        onDispose {
            pairingCode?.let { code ->
                listenerRef?.let { l ->
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("devices").child("pairings").child(code)
                        .removeEventListener(l)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CyberSky.copy(alpha = 0.25f), Slate900)
                    )
                )
                .border(2.dp, CyberSky, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AdminPanelSettings, contentDescription = "Parent Admin", tint = CyberSky, modifier = Modifier.size(40.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ScreenGuard Parent",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Generate a 6-digit code to pair and monitor your child's phone.",
            fontSize = 13.sp,
            color = Slate400,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        if (pairingCode == null) {
            Button(
                onClick = {
                    isGenerating = true
                    listenerRef = PairingManager.createPairingCode(
                        parentId = parentId,
                        parentName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} (Parent)",
                        onCodeGenerated = { code ->
                            isGenerating = false
                            pairingCode = code
                        },
                        onChildPaired = { childId, childModel ->
                            Toast.makeText(context, "✓ Linked with $childModel!", Toast.LENGTH_LONG).show()
                            onPaired(childId, childModel)
                        },
                        onError = { err ->
                            isGenerating = false
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                enabled = !isGenerating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyberSky)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Slate950, strokeWidth = 2.dp)
                } else {
                    Text("Generate 6-Digit Pairing Code", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        } else {
            // Display Generated Code
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberSky.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("PAIRING CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate400, letterSpacing = 2.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    val formatted = "${pairingCode?.take(3)} - ${pairingCode?.takeLast(3)}"
                    Text(
                        text = formatted,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 4.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(pairingCode ?: ""))
                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = CyberSky, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tap to copy", fontSize = 12.sp, color = CyberSky)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Divider(color = Slate800)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = EmeraldGreen, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Waiting for child device to enter code...",
                            fontSize = 12.sp,
                            color = Slate200
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Valid for 15 minutes", fontSize = 11.sp, color = Slate400)
                }
            }
        }
    }
}

@Composable
fun ParentDashboardScreen(
    parentId: String,
    childDeviceId: String,
    onUnpair: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Incidents", "Word Guard", "App Lock", "Approvals", "Rules & Status")

    var incidents by remember { mutableStateOf<List<FamilyRepository.Incident>>(emptyList()) }
    var freeTimeRequests by remember { mutableStateOf<List<FamilyRepository.FreeTimeRequest>>(emptyList()) }
    var uninstallRequests by remember { mutableStateOf<List<FamilyRepository.UninstallRequest>>(emptyList()) }
    var childRules by remember { mutableStateOf<FamilyRepository.ChildRules?>(null) }
    var viewingScreenshot by remember { mutableStateOf<FamilyRepository.Incident?>(null) }

    // Real-time synchronization
    DisposableEffect(parentId, childDeviceId) {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val incRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("incidents")
        val reqRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("requests")
        val rulesRef = db.getReference("devices").child("families").child(parentId).child(childDeviceId).child("rules")

        var isInitialSync = true
        val knownIncidentIds = mutableSetOf<String>()

        val incListener = FamilyRepository.attachIncidentsListener(parentId, childDeviceId) { list ->
            if (isInitialSync) {
                knownIncidentIds.addAll(list.map { it.id })
                isInitialSync = false
            } else {
                for (incident in list) {
                    if (incident.id !in knownIncidentIds) {
                        knownIncidentIds.add(incident.id)
                        ParentNotificationHelper.showIncidentNotification(context, incident)
                    }
                }
            }
            incidents = list
        }
        val reqListener = FamilyRepository.attachRequestsListener(
            parentId = parentId,
            childDeviceId = childDeviceId,
            onFreeTime = { ftList -> freeTimeRequests = ftList },
            onUninstall = { uList -> uninstallRequests = uList }
        )
        val rulesListener = FamilyRepository.attachRulesListener(
            parentId = parentId,
            childDeviceId = childDeviceId,
            onRules = { r -> childRules = r }
        )

        onDispose {
            incRef.removeEventListener(incListener)
            reqRef.removeEventListener(reqListener)
            rulesRef.removeEventListener(rulesListener)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate900)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(EmeraldGreen)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Child Device", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("ID: ${childDeviceId.take(8)}...", fontSize = 11.sp, color = Slate400)
                }
            }

            Button(
                onClick = {
                    FamilyRepository.grantInstantFreeTime(parentId, childDeviceId, 30)
                    Toast.makeText(context, "Granted 30 mins free time to child!", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.HourglassTop, contentDescription = "Break", tint = CyberSky, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Give 30m Break", fontSize = 11.sp, color = Color.White)
            }
        }

        // Tab Navigation
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Slate900,
            contentColor = CyberSky,
            edgePadding = 12.dp
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) CyberSky else Slate400
                        )
                    }
                )
            }
        }

        // Tab Contents
        when (selectedTab) {
            0 -> IncidentsTab(incidents = incidents, onScreenshotClick = { viewingScreenshot = it })
            1 -> WordGuardTab(
                parentId = parentId,
                childDeviceId = childDeviceId,
                blockedWords = childRules?.blockedWords ?: emptyList()
            )
            2 -> AppLockTab(
                parentId = parentId,
                childDeviceId = childDeviceId,
                blockedPackages = childRules?.blockedPackages ?: emptyList()
            )
            3 -> ApprovalsTab(
                parentId = parentId,
                childDeviceId = childDeviceId,
                freeTimeRequests = freeTimeRequests,
                uninstallRequests = uninstallRequests
            )
            4 -> RulesTab(onUnpair = onUnpair)
        }
    }

    // Screenshot Lightbox Dialog
    if (viewingScreenshot != null) {
        val inc = viewingScreenshot!!
        Dialog(onDismissRequest = { viewingScreenshot = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(inc.appName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Matched: '${inc.matchedWord}'", fontSize = 12.sp, color = RoseRed)
                        }
                        IconButton(onClick = { viewingScreenshot = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val bitmap = remember(inc) {
                        inc.screenshotBase64?.let { decodeBase64ToBitmap(it) }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Incident Screenshot",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(Slate800, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Screenshot not available", color = Slate400, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IncidentsTab(
    incidents: List<FamilyRepository.Incident>,
    onScreenshotClick: (FamilyRepository.Incident) -> Unit
) {
    if (incidents.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Clean", tint = EmeraldGreen, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("All Clear!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("No incidents detected on the child device.", fontSize = 13.sp, color = Slate400)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(incidents, key = { it.id }) { inc ->
                IncidentItem(incident = inc, onClick = { onScreenshotClick(inc) })
            }
        }
    }
}

@Composable
fun IncidentItem(
    incident: FamilyRepository.Incident,
    onClick: () -> Unit
) {
    val timeFormatted = remember(incident.timestamp) {
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        sdf.format(Date(incident.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RoseRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Alert", tint = RoseRed, modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = incident.appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(timeFormatted, fontSize = 11.sp, color = Slate400)
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Triggered: '${incident.matchedWord}'",
                    fontSize = 12.sp,
                    color = RoseRed,
                    fontWeight = FontWeight.Medium
                )
            }

            if (incident.screenshotBase64 != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.PhotoCamera, contentDescription = "Screenshot", tint = CyberSky, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ApprovalsTab(
    parentId: String,
    childDeviceId: String,
    freeTimeRequests: List<FamilyRepository.FreeTimeRequest>,
    uninstallRequests: List<FamilyRepository.UninstallRequest>
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Section 1: Free Time Requests
        Text(
            text = "FREE TIME / BREAK REQUESTS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        val pendingFreeTime = freeTimeRequests.filter { it.status == "PENDING" }
        if (pendingFreeTime.isEmpty()) {
            Text("No pending break requests.", fontSize = 12.sp, color = Slate400)
        } else {
            pendingFreeTime.forEach { req ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberSky.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "${req.durationMinutes} Minutes Break Requested",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Incognito and adult DNS filters will stay strictly active during this break.",
                            fontSize = 11.sp,
                            color = Slate400
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    FamilyRepository.approveFreeTime(parentId, childDeviceId, req.id, req.durationMinutes)
                                    Toast.makeText(context, "Approved ${req.durationMinutes}m break", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Approve", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    FamilyRepository.rejectFreeTime(parentId, childDeviceId, req.id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Deny", color = Slate200, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section 2: Uninstall Requests with 50-Word Friction
        Text(
            text = "APP REMOVAL REQUESTS (50-WORD ESSAYS)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        val pendingUninstall = uninstallRequests.filter { it.status == "PENDING" }
        if (pendingUninstall.isEmpty()) {
            Text("No pending uninstall requests.", fontSize = 12.sp, color = Slate400)
        } else {
            pendingUninstall.forEach { req ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RoseRed.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Removal Requested", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RoseRed)
                            Text("${req.wordCount} words written", fontSize = 11.sp, color = EmeraldGreen, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Essay quote box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate950, RoundedCornerShape(8.dp))
                                .border(1.dp, Slate800, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "\"${req.reason}\"",
                                fontSize = 12.sp,
                                color = Slate200,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    FamilyRepository.approveUninstall(parentId, childDeviceId, req.id)
                                    Toast.makeText(context, "Approved! Child has 15 mins to uninstall.", Toast.LENGTH_LONG).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberOrange),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Allow 15m Window", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    FamilyRepository.rejectUninstall(parentId, childDeviceId, req.id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Deny", color = Slate200, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WordGuardTab(
    parentId: String,
    childDeviceId: String,
    blockedWords: List<String>
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newWordInput by remember { mutableStateOf("") }

    val filteredWords = remember(blockedWords, searchQuery) {
        if (searchQuery.isBlank()) blockedWords
        else blockedWords.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar & Add Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search blocked words...", fontSize = 13.sp, color = Slate400) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Slate400, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Slate400, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberSky,
                    unfocusedBorderColor = Slate800,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Slate900,
                    unfocusedContainerColor = Slate900
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    newWordInput = ""
                    showAddDialog = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberSky),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Slate950, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Info count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${filteredWords.size} words active",
                fontSize = 12.sp,
                color = Slate400,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Syncs instantly to child",
                fontSize = 11.sp,
                color = EmeraldGreen
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredWords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty()) "No words matching '$searchQuery'" else "No blocked words.",
                    color = Slate400,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredWords, key = { it }) { word ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Slate900),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = word,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    FamilyRepository.removeBlockedWord(parentId, childDeviceId, blockedWords, word)
                                    Toast.makeText(context, "Removed '$word'", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = RoseRed.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Word Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Slate900,
            title = {
                Text("Block New Word / Phrase", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Enter the exact word or phrase to block. When detected on the child's screen, ScreenGuard blocks it immediately.",
                        color = Slate400,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newWordInput,
                        onValueChange = { newWordInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. badsite, gamble, mature", fontSize = 13.sp, color = Slate400) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberSky,
                            unfocusedBorderColor = Slate800,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val word = newWordInput.trim()
                        if (word.isNotEmpty()) {
                            FamilyRepository.addBlockedWord(parentId, childDeviceId, blockedWords, word)
                            Toast.makeText(context, "Added '$word' to blocklist", Toast.LENGTH_SHORT).show()
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSky)
                ) {
                    Text("Add Word", color = Slate950, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = Slate400)
                }
            }
        )
    }
}

data class PresetApp(val name: String, val packageName: String, val category: String)

val PRESET_APPS = listOf(
    PresetApp("Google Play Store", "com.android.vending", "Prevents installing new apps"),
    PresetApp("Cookie Manga", "chat.kijang.manga.cookie.browser", "Manhwa / Comics reader"),
    PresetApp("TikTok", "com.zhiliaoapp.musically", "Short video feed"),
    PresetApp("Roblox", "com.roblox.client", "Online gaming platform"),
    PresetApp("Instagram", "com.instagram.android", "Social media & reels"),
    PresetApp("Snapchat", "com.snapchat.android", "Photo & video messaging"),
    PresetApp("Discord", "com.discord", "Community & chat servers"),
    PresetApp("YouTube", "com.google.android.youtube", "Video streaming"),
    PresetApp("Google Chrome", "com.android.chrome", "Web browser")
)

@Composable
fun AppLockTab(
    parentId: String,
    childDeviceId: String,
    blockedPackages: List<String>
) {
    val context = LocalContext.current
    var showCustomAppDialog by remember { mutableStateOf(false) }
    var customPackageInput by remember { mutableStateOf("") }

    val presetPackageSet = remember { PRESET_APPS.map { it.packageName }.toSet() }
    val customBlocked = remember(blockedPackages) {
        blockedPackages.filter { it !in presetPackageSet }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "APP RESTRICTIONS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate400,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Instantly closed if launched on child device",
                    fontSize = 11.sp,
                    color = Slate400
                )
            }
            Button(
                onClick = {
                    customPackageInput = ""
                    showCustomAppDialog = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Custom App", tint = CyberSky, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Block Custom", fontSize = 11.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Preset App List
        PRESET_APPS.forEach { app ->
            val isBlocked = blockedPackages.any { it.equals(app.packageName, ignoreCase = true) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isBlocked) RoseRed.copy(alpha = 0.4f) else Slate800)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = app.category,
                            fontSize = 11.sp,
                            color = if (isBlocked) RoseRed else Slate400
                        )
                    }

                    Switch(
                        checked = isBlocked,
                        onCheckedChange = { checked ->
                            FamilyRepository.toggleBlockedPackage(parentId, childDeviceId, blockedPackages, app.packageName, checked)
                            val msg = if (checked) "Blocked ${app.name}" else "Unblocked ${app.name}"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RoseRed,
                            uncheckedThumbColor = Slate400,
                            uncheckedTrackColor = Slate800
                        )
                    )
                }
            }
        }

        // Custom Blocked Packages Section
        if (customBlocked.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "CUSTOM BLOCKED PACKAGES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            customBlocked.forEach { pkg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pkg,
                            fontSize = 13.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                FamilyRepository.removeBlockedPackage(parentId, childDeviceId, blockedPackages, pkg)
                                Toast.makeText(context, "Unblocked $pkg", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = RoseRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showCustomAppDialog) {
        AlertDialog(
            onDismissRequest = { showCustomAppDialog = false },
            containerColor = Slate900,
            title = {
                Text("Block Custom App Package", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Enter Android package name (e.g. com.zhiliaoapp.musically, com.supercell.clashofclans).",
                        color = Slate400,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customPackageInput,
                        onValueChange = { customPackageInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("com.example.app", fontSize = 13.sp, color = Slate400) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberSky,
                            unfocusedBorderColor = Slate800,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pkg = customPackageInput.trim().lowercase()
                        if (pkg.isNotEmpty()) {
                            FamilyRepository.toggleBlockedPackage(parentId, childDeviceId, blockedPackages, pkg, true)
                            Toast.makeText(context, "Blocked $pkg", Toast.LENGTH_SHORT).show()
                            showCustomAppDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoseRed)
                ) {
                    Text("Block App", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomAppDialog = false }) {
                    Text("Cancel", color = Slate400)
                }
            }
        )
    }
}

@Composable
fun RulesTab(onUnpair: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("PERMANENT ENFORCEMENT RULES", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate400, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(10.dp))

        RuleCard(
            title = "Incognito Mode",
            status = "Permanently Blocked",
            description = "Private & secret tabs are closed immediately across Chrome, Brave, Samsung Internet, Edge, Firefox, and Opera.",
            tint = EmeraldGreen
        )

        Spacer(modifier = Modifier.height(10.dp))

        RuleCard(
            title = "Family DNS Filter",
            status = "Permanently Active",
            description = "Cloudflare 1.1.1.3 adult content filter enforced via local VPN proxy + Private DNS Settings Guard.",
            tint = EmeraldGreen
        )

        Spacer(modifier = Modifier.height(10.dp))

        RuleCard(
            title = "Anti-Uninstall Guard",
            status = "50-Word Friction Locked",
            description = "Device admin blocks drag-to-uninstall. Removal requires writing a 50-word justification and parent approval.",
            tint = EmeraldGreen
        )

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = onUnpair,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Unpair Child Device", color = RoseRed, fontSize = 13.sp)
        }
    }
}

@Composable
fun RuleCard(
    title: String,
    status: String,
    description: String,
    tint: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, fontSize = 11.sp, color = Slate400, lineHeight = 16.sp)
        }
    }
}

fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        null
    }
}
