package com.example.screenguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.screenguard.network.CloudflareIncidentDispatcher
import com.example.screenguard.network.FirebaseIncidentDispatcher
import com.example.screenguard.receiver.ScreenGuardDeviceAdminReceiver
import com.example.screenguard.service.MatchMode
import com.example.screenguard.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    var showPinPrompt by remember { mutableStateOf(false) }
    var pinPromptValue by remember { mutableStateOf("") }
    var pinPromptError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

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
                            onCheckedChange = { targetState ->
                                if (!targetState && preferences.hasGuardianPin() && !preferences.isGuardianUnlocked()) {
                                    pendingAction = { preferences.setEnabled(false) }
                                    pinPromptValue = ""
                                    pinPromptError = null
                                    showPinPrompt = true
                                } else {
                                    preferences.setEnabled(targetState)
                                }
                            },
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

            val requireGuardianAuth: (() -> Unit) -> Unit = { action ->
                if (preferences.hasGuardianPin() && !preferences.isGuardianUnlocked()) {
                    pendingAction = action
                    pinPromptValue = ""
                    pinPromptError = null
                    showPinPrompt = true
                } else {
                    action()
                }
            }

            // Tab Contents
            when (selectedTab) {
                0 -> DashboardWordsTab(preferences, blockedWords, detectionLogs.size, blockPlayStore, requireGuardianAuth)
                1 -> WhitelistTab(preferences, whitelist, requireGuardianAuth)
                2 -> HistoryTab(detectionLogs)
                3 -> SettingsTab(preferences, matchMode, showToast, isAccessibilityEnabled, blockPlayStore, blockedPackages, requireGuardianAuth)
            }
        }

        if (showPinPrompt) {
            AlertDialog(
                onDismissRequest = { showPinPrompt = false },
                title = { Text("Guardian Verification", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter the Guardian PIN to modify protection settings:", fontSize = 13.sp, color = Slate400)
                        OutlinedTextField(
                            value = pinPromptValue,
                            onValueChange = { pinPromptValue = it },
                            singleLine = true,
                            placeholder = { Text("Enter 4-6 Digit PIN") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pinPromptError != null) {
                            Text(pinPromptError!!, color = RoseRed, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (preferences.verifyGuardianPin(pinPromptValue.trim())) {
                                preferences.unlockGuardianSession()
                                showPinPrompt = false
                                pendingAction?.invoke()
                                pendingAction = null
                            } else {
                                pinPromptError = "Incorrect Guardian PIN"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text("Unlock")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPinPrompt = false }) {
                        Text("Cancel", color = Slate400)
                    }
                }
            )
        }
    }
}

@Composable
fun DashboardWordsTab(
    preferences: PreferencesManager,
    blockedWords: Set<String>,
    detectionCount: Int,
    blockPlayStore: Boolean,
    requireGuardianAuth: (() -> Unit) -> Unit
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
                    onClick = { requireGuardianAuth { preferences.load18PlusPreset() } },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("🛡 Load 18+ Preset", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                }

                Button(
                    onClick = { requireGuardianAuth { showAddDialog = true } },
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
                                    onClick = { requireGuardianAuth { editingWord = word } },
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
                                    onClick = { requireGuardianAuth { wordPendingDeletion = word } },
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
fun WhitelistTab(
    preferences: PreferencesManager,
    whitelist: Set<String>,
    requireGuardianAuth: (() -> Unit) -> Unit
) {
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
            onClick = { requireGuardianAuth { showAddDialog = true } },
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
                            onClick = { requireGuardianAuth { preferences.removeWhitelistPackage(pkg) } },
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
    blockedPackages: Set<String>,
    requireGuardianAuth: (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    var showAddPackageDialog by remember { mutableStateOf(false) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var newPinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var pinDialogError by remember { mutableStateOf<String?>(null) }

    val hasGuardianPin = preferences.hasGuardianPin()
    val isUnlocked = preferences.isGuardianUnlocked()
    val pauseUntil by preferences.pauseUntil.collectAsState()
    val isAppPaused = System.currentTimeMillis() < pauseUntil

    val blockIncognito by preferences.blockIncognito.collectAsState()
    val blockHistoryDeletion by preferences.blockHistoryDeletion.collectAsState()
    val protectPrivateDns by preferences.protectPrivateDns.collectAsState()

    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager }
    val adminComponent = remember { ComponentName(context, ScreenGuardDeviceAdminReceiver::class.java) }
    var isAdminActive by remember { mutableStateOf(dpm?.isAdminActive(adminComponent) == true) }

    // Periodic check for device admin status
    LaunchedEffect(Unit) {
        while (true) {
            isAdminActive = dpm?.isAdminActive(adminComponent) == true
            kotlinx.coroutines.delay(2000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Guardian Protection & Security",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate200,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // 1. Guardian PIN & Protection Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = "Guardian",
                                tint = if (hasGuardianPin) EmeraldGreen else RoseRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Guardian Protection PIN",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (!hasGuardianPin) RoseDark else if (isUnlocked) Color(0xFF1E3A8A) else EmeraldDark
                        ) {
                            Text(
                                text = if (!hasGuardianPin) "NOT SET" else if (isUnlocked) "UNLOCKED (3m)" else "PROTECTED",
                                color = if (!hasGuardianPin) RoseRed else if (isUnlocked) SkyBlue else EmeraldGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasGuardianPin) {
                            "ScreenGuard settings, uninstallation, and app controls are protected. Only the Guardian with the secret PIN can modify them."
                        } else {
                            "⚠️ No Guardian PIN configured! Anyone can turn off ScreenGuard or delete blocked words. Set a PIN known only to your accountability partner."
                        },
                        fontSize = 11.sp,
                        color = Slate400
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (hasGuardianPin) {
                                    requireGuardianAuth {
                                        newPinText = ""
                                        confirmPinText = ""
                                        pinDialogError = null
                                        showSetPinDialog = true
                                    }
                                } else {
                                    newPinText = ""
                                    confirmPinText = ""
                                    pinDialogError = null
                                    showSetPinDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (hasGuardianPin) "Change PIN" else "Set Guardian PIN", fontSize = 12.sp)
                        }

                        if (hasGuardianPin) {
                            if (isUnlocked) {
                                OutlinedButton(
                                    onClick = { preferences.lockGuardianSession() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseRed),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Lock Now", fontSize = 12.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        requireGuardianAuth {
                                            preferences.unlockGuardianSession()
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SkyBlue),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Unlock (3m)", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Anti-Uninstall Device Admin Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AdminPanelSettings,
                                contentDescription = "Admin",
                                tint = if (isAdminActive) EmeraldGreen else Color(0xFFF59E0B),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Anti-Uninstall Protection",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isAdminActive) EmeraldDark else Color(0xFF451A03)
                        ) {
                            Text(
                                text = if (isAdminActive) "ACTIVE" else "INACTIVE",
                                color = if (isAdminActive) EmeraldGreen else Color(0xFFF59E0B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isAdminActive) {
                            "✓ Device Administrator is active. The Android system blocks uninstallation without first revoking admin privileges (which is guarded by PIN)."
                        } else {
                            "Activate Device Administrator to prevent long-press uninstall or uninstallation via Android Settings."
                        },
                        fontSize = 11.sp,
                        color = Slate400
                    )

                    if (!isAdminActive) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                    putExtra(
                                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Required to protect ScreenGuard against unauthorized uninstallation."
                                    )
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Activate Uninstall Protection", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. Temporary Break / Pause Request Card (Firebase Realtime)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = "Break",
                                tint = if (isAppPaused) SkyBlue else Color(0xFF10B981),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Temporary Break / Pause",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isAppPaused) Color(0xFF1E3A8A) else EmeraldDark
                        ) {
                            Text(
                                text = if (isAppPaused) "PAUSED" else "ENFORCING",
                                color = if (isAppPaused) SkyBlue else EmeraldGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isAppPaused) {
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(pauseUntil))
                            "A temporary break is active until $timeStr. Enforcement will resume automatically."
                        } else {
                            "Need temporary access for a legitimate task? Send a 15-minute break request to your Guardian's phone for approval via Firebase."
                        },
                        fontSize = 11.sp,
                        color = Slate400
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    if (isAppPaused) {
                        OutlinedButton(
                            onClick = { preferences.clearPause() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("End Break Early & Resume Protection", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                FirebaseIncidentDispatcher.requestPause(context, 15) { success, msg ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(
                                            context,
                                            if (success) "✓ 15-Min Break Request Sent to Guardian!" else "Error: $msg",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Request 15-Min Break from Guardian", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 4. Guardian Admin Dashboard Card
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
                            Icon(Icons.Default.Dashboard, contentDescription = "Dashboard", tint = SkyBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Guardian Admin Dashboard", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Live incident feed, screenshot lightbox, and pause approval panel.", fontSize = 11.sp, color = Slate400)
                    }

                    Button(
                        onClick = {
                            requireGuardianAuth {
                                context.startActivity(Intent(context, GuardianActivity::class.java))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Open", fontSize = 12.sp, color = Slate200)
                    }
                }
            }
        }

        // 4b. Browser Incognito & History Guard Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = "Incognito",
                                tint = RoseRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Incognito & History Guard",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (blockIncognito) EmeraldDark else RoseDark
                        ) {
                            Text(
                                text = if (blockIncognito) "BLOCKED" else "OFF",
                                color = if (blockIncognito) EmeraldGreen else RoseRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Closes secret tabs and prevents erasing browsing history across Chrome, Brave, Samsung Internet, Edge, Firefox, and Opera.",
                        fontSize = 11.sp,
                        color = Slate400
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Block Incognito & Private Tabs", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Closes secret tabs immediately upon opening.", fontSize = 11.sp, color = Slate400)
                        }
                        Switch(
                            checked = blockIncognito,
                            onCheckedChange = { targetState ->
                                requireGuardianAuth {
                                    preferences.setBlockIncognito(targetState)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = RoseRed
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Prevent Deleting Browser History", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Blocks 'Clear browsing data' and history deletion screens.", fontSize = 11.sp, color = Slate400)
                        }
                        Switch(
                            checked = blockHistoryDeletion,
                            onCheckedChange = { targetState ->
                                requireGuardianAuth {
                                    preferences.setBlockHistoryDeletion(targetState)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = RoseRed
                            )
                        )
                    }
                }
            }
        }

        // 4c. Family DNS (System-Wide Adult Filter) Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = "DNS",
                                tint = SkyBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Family DNS Protection",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (protectPrivateDns) EmeraldDark else RoseDark
                        ) {
                            Text(
                                text = if (protectPrivateDns) "LOCKED" else "UNLOCKED",
                                color = if (protectPrivateDns) EmeraldGreen else RoseRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Android's built-in Private DNS filters adult sites across the entire phone (all apps, browsers, Wi-Fi & data) with 0% battery usage.",
                        fontSize = 11.sp,
                        color = Slate400
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Slate700
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Recommended Hostname (Cloudflare 1.1.1.3):", fontSize = 10.sp, color = Slate400)
                                Text("family.cloudflare-dns.com", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SkyBlue)
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Family DNS", "family.cloudflare-dns.com")
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied 'family.cloudflare-dns.com' to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = SkyBlue, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            requireGuardianAuth {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                } catch (e: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Android Network Settings to Configure", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lock Private DNS in Settings", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Kicks user to Home if anyone tries to turn off or change Private DNS in phone settings.", fontSize = 11.sp, color = Slate400)
                        }
                        Switch(
                            checked = protectPrivateDns,
                            onCheckedChange = { targetState ->
                                requireGuardianAuth {
                                    preferences.setProtectPrivateDns(targetState)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PrimaryBlue
                            )
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "General App & Detection Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Slate200,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
        }

        // 5. Google Play Store Blocker Card
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
                        Text("Instantly closes Play Store to prevent installing unmonitored apps.", fontSize = 11.sp, color = Slate400)
                    }
                    Switch(
                        checked = blockPlayStore,
                        onCheckedChange = { targetState ->
                            requireGuardianAuth {
                                preferences.setBlockPlayStore(targetState)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RoseRed
                        )
                    )
                }
            }
        }

        // 6. Blacklisted App Packages Manager
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
                            onClick = { requireGuardianAuth { showAddPackageDialog = true } },
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
                                onClick = { requireGuardianAuth { preferences.removeBlockedPackage(pkg) } },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RoseRed, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        // 7. Word Matching Mode Card
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
                            .clickable {
                                requireGuardianAuth { preferences.setMatchMode(MatchMode.EXACT_WORD) }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = matchMode == MatchMode.EXACT_WORD,
                            onClick = {
                                requireGuardianAuth { preferences.setMatchMode(MatchMode.EXACT_WORD) }
                            },
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
                            .clickable {
                                requireGuardianAuth { preferences.setMatchMode(MatchMode.SUBSTRING) }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = matchMode == MatchMode.SUBSTRING,
                            onClick = {
                                requireGuardianAuth { preferences.setMatchMode(MatchMode.SUBSTRING) }
                            },
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

        // 8. Toast Feedback Toggle
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

        // 9. Accessibility Quick Shortcut
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

    if (showSetPinDialog) {
        AlertDialog(
            onDismissRequest = { showSetPinDialog = false },
            containerColor = Slate800,
            title = { Text(if (hasGuardianPin) "Change Guardian PIN" else "Set Guardian PIN", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a 4-6 digit secret PIN known only to the guardian/accountability partner:", fontSize = 12.sp, color = Slate400)
                    OutlinedTextField(
                        value = newPinText,
                        onValueChange = { newPinText = it },
                        placeholder = { Text("New PIN (4-6 digits)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPinText,
                        onValueChange = { confirmPinText = it },
                        placeholder = { Text("Confirm New PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinDialogError != null) {
                        Text(pinDialogError!!, color = RoseRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pin = newPinText.trim()
                        val confirm = confirmPinText.trim()
                        if (pin.length < 4 || pin.length > 8) {
                            pinDialogError = "PIN must be between 4 and 8 digits"
                        } else if (pin != confirm) {
                            pinDialogError = "PINs do not match"
                        } else {
                            preferences.setGuardianPin(pin)
                            preferences.lockGuardianSession()
                            showSetPinDialog = false
                            Toast.makeText(context, "✓ Guardian PIN Set & Active!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetPinDialog = false }) {
                    Text("Cancel", color = Slate400)
                }
            }
        )
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
