package com.example.screenguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screenguard.data.DetectionLog
import com.example.screenguard.data.DetectionLogRepository
import com.example.screenguard.data.PreferencesManager
import com.example.screenguard.service.MatchMode
import com.example.screenguard.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = PreferencesManager.getInstance(this)

        setContent {
            ScreenGuardTheme {
                ScreenGuardApp(preferences)
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenGuardApp(preferences: PreferencesManager) {
    val context = LocalContext.current
    val isEnabled by preferences.isEnabled.collectAsState()
    val blockPlayStore by preferences.blockPlayStore.collectAsState()
    val blockedPackages by preferences.blockedPackages.collectAsState()
    val blockedWords by preferences.blockedWords.collectAsState()
    val whitelist by preferences.whitelist.collectAsState()
    val matchMode by preferences.matchMode.collectAsState()
    val showToast by preferences.showToast.collectAsState()
    val detectionLogs by DetectionLogRepository.logs.collectAsState()

    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard & Words", "App Whitelist", "History", "Settings")

    // Periodic / resumed check for accessibility permission
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
            kotlinx.coroutines.delay(1500)
        }
    }

    Scaffold(
        containerColor = Slate900,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate900)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "🛡 SCREENguard",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBlue
                        )
                        Text(
                            text = "Fast Mobile Word Detection & App Exit",
                            fontSize = 12.sp,
                            color = Slate400
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isEnabled) EmeraldDark else RoseDark,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = if (isEnabled) "● ACTIVE" else "○ PAUSED",
                                color = if (isEnabled) EmeraldGreen else RoseRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { preferences.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = EmeraldGreen,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate700
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Permission Alert Card if accessibility is disabled
            if (!isAccessibilityEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Accessibility Service Disabled",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFFFDE68A)
                            )
                            Text(
                                text = "Required to inspect screen text & close apps.",
                                fontSize = 11.sp,
                                color = Color(0xFFFCD34D)
                            )
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // Tab Row
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Slate900,
                contentColor = SkyBlue,
                edgePadding = 16.dp,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) SkyBlue else Slate400,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Tab Contents
            when (selectedTab) {
                0 -> DashboardWordsTab(preferences, blockedWords, detectionLogs.size, blockPlayStore)
                1 -> WhitelistTab(preferences, whitelist)
                2 -> HistoryTab(detectionLogs)
                3 -> SettingsTab(preferences, matchMode, showToast, isAccessibilityEnabled, blockPlayStore, blockedPackages)
            }
        }
    }
}

@Composable
fun DashboardWordsTab(
    preferences: PreferencesManager,
    blockedWords: Set<String>,
    detectionCount: Int,
    blockPlayStore: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingWord by remember { mutableStateOf<String?>(null) }
    var wordPendingDeletion by remember { mutableStateOf<String?>(null) }

    val filteredWords = remember(blockedWords, searchQuery) {
        if (searchQuery.isEmpty()) {
            blockedWords.toList()
        } else {
            blockedWords.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Quick Stats Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("DETECTIONS", detectionCount.toString(), Modifier.weight(1f))
            StatCard("BLOCKED WORDS", blockedWords.size.toString(), Modifier.weight(1f))
            StatCard("PLAY STORE", if (blockPlayStore) "LOCKED 🔒" else "OPEN", Modifier.weight(1f))
        }

        // Header with Add & Preset Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Blocked Words (${blockedWords.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate200
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { preferences.load18PlusPreset() },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("🛡 Load 18+ Preset", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                }

                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Word", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search blocked words...", fontSize = 13.sp, color = Slate400) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Slate400) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Slate400)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate800,
                unfocusedContainerColor = Slate800,
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = Slate700,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            singleLine = true
        )

        // Blocked Words List
        if (filteredWords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isEmpty()) "No blocked words added yet." else "No matches found.",
                    color = Slate400,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(filteredWords, key = { it }) { word ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Slate800),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = word,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate50,
                                modifier = Modifier.weight(1f)
                            )

                            Row {
                                IconButton(
                                    onClick = { editingWord = word },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = Slate400,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { wordPendingDeletion = word },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = RoseRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Word Dialog
    if (showAddDialog) {
        WordInputDialog(
            title = "Add Blocked Word / Phrase",
            initialValue = "",
            onConfirm = {
                preferences.addBlockedWord(it)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // Edit Word Dialog
    editingWord?.let { oldWord ->
        WordInputDialog(
            title = "Edit Blocked Word",
            initialValue = oldWord,
            onConfirm = { newWord ->
                preferences.editBlockedWord(oldWord, newWord)
                editingWord = null
            },
            onDismiss = { editingWord = null }
        )
    }

    // 5-Minute Cooldown Deletion Dialog
    wordPendingDeletion?.let { targetWord ->
        WordRemovalCooldownDialog(
            word = targetWord,
            onConfirm = {
                preferences.removeBlockedWord(targetWord)
                wordPendingDeletion = null
            },
            onDismiss = { wordPendingDeletion = null }
        )
    }
}

@Composable
fun WordRemovalCooldownDialog(
    word: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(300) } // 5 minutes = 300 seconds
    val targetPhrase = "I choose to protect my mind and stay clean."
    var typedText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            secondsLeft--
        }
    }

    val minutes = secondsLeft / 60
    val seconds = secondsLeft % 60
    val formattedTime = String.format("%02d:%02d", minutes, seconds)
    val isTextMatched = typedText.trim().equals(targetPhrase, ignoreCase = false)
    val isTimerComplete = secondsLeft == 0
    val canDelete = isTimerComplete && isTextMatched

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate800,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = "Lock", tint = RoseRed, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("5-Minute Removal Cooldown", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Removing: '$word'",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyBlue
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "To protect against impulsive urges, you must wait for the 5-minute timer and type the commitment sentence below.",
                    fontSize = 11.sp,
                    color = Slate400
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Timer Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isTimerComplete) EmeraldDark else Color(0xFF450A0A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isTimerComplete) "Timer Expired" else "Time Remaining",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isTimerComplete) EmeraldGreen else RoseRed
                        )
                        Text(
                            text = formattedTime,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTimerComplete) EmeraldGreen else RoseRed
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text("Type this sentence exactly:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate200)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Slate700
                ) {
                    Text(
                        text = targetPhrase,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFDE68A),
                        modifier = Modifier.padding(8.dp)
                    )
                }

                OutlinedTextField(
                    value = typedText,
                    onValueChange = { typedText = it },
                    placeholder = { Text("Type sentence here...", fontSize = 11.sp, color = Slate400) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isTextMatched) EmeraldGreen else PrimaryBlue,
                        unfocusedBorderColor = if (isTextMatched) EmeraldGreen else Slate700,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (isTextMatched) {
                    Text("✓ Sentence matched!", color = EmeraldGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RoseRed,
                    disabledContainerColor = Slate700,
                    disabledContentColor = Slate400
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (canDelete) "Confirm Remove" else if (!isTimerComplete) "Locked ($formattedTime)" else "Type Sentence to Unlock",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel & Stay Protected", color = EmeraldGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SkyBlue)
            Text(text = label, fontSize = 9.sp, color = Slate400, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun WhitelistTab(preferences: PreferencesManager, whitelist: Set<String>) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Whitelisted Applications",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Slate200,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = "Applications listed here will NEVER be closed by ScreenGuard.",
            fontSize = 12.sp,
            color = Slate400,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Button(
            onClick = { showAddDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add App Package to Whitelist", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(whitelist.toList(), key = { it }) { pkg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Slate800),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = pkg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate200,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = { preferences.removeWhitelistPackage(pkg) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = RoseRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        WordInputDialog(
            title = "Add Whitelist Package",
            initialValue = "",
            hint = "e.g. com.android.settings",
            onConfirm = {
                preferences.addWhitelistPackage(it)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
fun HistoryTab(logs: List<DetectionLog>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Detection History (${logs.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate200
            )

            if (logs.isNotEmpty()) {
                TextButton(onClick = { DetectionLogRepository.clearLogs() }) {
                    Text("Clear All", color = RoseRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No detection events recorded yet.",
                    color = Slate400,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Slate800),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = log.appName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = log.timestamp,
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Trigger: '${log.matchedWord}'",
                                    fontSize = 12.sp,
                                    color = RoseRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Action: ${log.actionTaken}",
                                    fontSize = 11.sp,
                                    color = EmeraldGreen
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    preferences: PreferencesManager,
    matchMode: MatchMode,
    showToast: Boolean,
    isAccessibilityEnabled: Boolean,
    blockPlayStore: Boolean,
    blockedPackages: Set<String>
) {
    val context = LocalContext.current
    var showAddPackageDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "App Blocker & Protection Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate200,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // 1. Google Play Store Blocker Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock", tint = RoseRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Block Google Play Store", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Instantly closes the Play Store whenever it is opened so you cannot download new apps.", fontSize = 11.sp, color = Slate400)
                    }
                    Switch(
                        checked = blockPlayStore,
                        onCheckedChange = { preferences.setBlockPlayStore(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RoseRed
                        )
                    )
                }
            }
        }

        // 2. Blacklisted App Packages Manager
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Blacklisted Apps (${blockedPackages.size})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Button(
                            onClick = { showAddPackageDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add App", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "These app packages will be closed immediately upon opening.",
                        fontSize = 11.sp,
                        color = Slate400,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    blockedPackages.forEach { pkg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (pkg == "com.android.vending") "Google Play Store ($pkg)" else pkg,
                                fontSize = 12.sp,
                                color = Slate200,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { preferences.removeBlockedPackage(pkg) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RoseRed, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        // 3. Word Matching Mode Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Word Matching Mode",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { preferences.setMatchMode(MatchMode.EXACT_WORD) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = matchMode == MatchMode.EXACT_WORD,
                            onClick = { preferences.setMatchMode(MatchMode.EXACT_WORD) },
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryBlue)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Exact Word Boundaries (Recommended)", fontSize = 13.sp, color = Slate50, fontWeight = FontWeight.Medium)
                            Text("Prevents false matches (e.g. 'cat' in 'education')", fontSize = 11.sp, color = Slate400)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { preferences.setMatchMode(MatchMode.SUBSTRING) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = matchMode == MatchMode.SUBSTRING,
                            onClick = { preferences.setMatchMode(MatchMode.SUBSTRING) },
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryBlue)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Substring Match", fontSize = 13.sp, color = Slate50, fontWeight = FontWeight.Medium)
                            Text("Matches any occurrence inside URLs and compound words", fontSize = 11.sp, color = Slate400)
                        }
                    }
                }
            }
        }

        // 4. Toast Feedback Toggle
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Feedback Toast", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Display a quick notification when an app is closed", fontSize = 11.sp, color = Slate400)
                    }
                    Switch(
                        checked = showToast,
                        onCheckedChange = { preferences.setShowToast(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PrimaryBlue
                        )
                    )
                }
            }
        }

        // 5. Accessibility Quick Shortcut
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Accessibility Service", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            if (isAccessibilityEnabled) "Active and monitoring screen" else "Service is not enabled",
                            fontSize = 11.sp,
                            color = if (isAccessibilityEnabled) EmeraldGreen else RoseRed
                        )
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Open Settings", fontSize = 11.sp, color = Slate200)
                    }
                }
            }
        }
    }

    if (showAddPackageDialog) {
        WordInputDialog(
            title = "Blacklist App Package",
            initialValue = "",
            hint = "e.g. com.instagram.android",
            onConfirm = {
                preferences.addBlockedPackage(it)
                showAddPackageDialog = false
            },
            onDismiss = { showAddPackageDialog = false }
        )
    }
}

@Composable
fun WordInputDialog(
    title: String,
    initialValue: String,
    hint: String = "e.g. confidential_token",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate800,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint, color = Slate400) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = Slate700,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Slate400)
            }
        }
    )
}
