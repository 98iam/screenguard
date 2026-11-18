package com.example.screenguard.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screenguard.data.PreferencesManager
import com.example.screenguard.network.FamilyRepository
import com.example.screenguard.network.PairingManager
import com.example.screenguard.receiver.ScreenGuardDeviceAdminReceiver
import com.example.screenguard.service.FamilyDnsVpnService
import com.example.screenguard.service.ScreenGuardAccessibilityService
import kotlinx.coroutines.delay

val Slate950 = Color(0xFF0B0F19)
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Slate400 = Color(0xFF94A3B8)
val Slate200 = Color(0xFFE2E8F0)
val CyberSky = Color(0xFF38BDF8)
val ElectricBlue = Color(0xFF0284C7)
val EmeraldGreen = Color(0xFF10B981)
val RoseRed = Color(0xFFF43F5E)
val AmberOrange = Color(0xFFF59E0B)

class ChildMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager.getInstance(this)

        // Automatically start Family DNS VPN if paired
        if (prefs.isPairedWithParent()) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent == null) {
                FamilyDnsVpnService.start(this)
            }
        }

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
                    ChildAppRoot(prefs = prefs)
                }
            }
        }
    }
}

@Composable
fun ChildAppRoot(prefs: PreferencesManager) {
    val context = LocalContext.current
    var isPaired by remember { mutableStateOf(prefs.isPairedWithParent()) }
    var parentName by remember { mutableStateOf(prefs.getParentName()) }

    var freeTimeUntil by remember { mutableLongStateOf(prefs.freeTimeUntil.value) }
    var uninstallUnlockedUntil by remember { mutableLongStateOf(prefs.uninstallUnlockedUntil.value) }

    // Listen to real-time rule updates from Parent
    LaunchedEffect(isPaired) {
        if (isPaired) {
            FamilyRepository.attachChildRulesListener(context) { rules ->
                freeTimeUntil = rules.freeTimeUntil
                uninstallUnlockedUntil = rules.uninstallUnlockedUntil
            }
        }
    }

    if (!isPaired) {
        ChildPairingScreen(
            prefs = prefs,
            onPairedSuccess = { pId, pName ->
                prefs.setParentPairing(pId, pName)
                parentName = pName
                isPaired = true
                FamilyDnsVpnService.start(context)
            }
        )
    } else {
        ChildProtectedHubScreen(
            prefs = prefs,
            parentName = parentName,
            freeTimeUntil = freeTimeUntil,
            uninstallUnlockedUntil = uninstallUnlockedUntil
        )
    }
}

@Composable
fun ChildPairingScreen(
    prefs: PreferencesManager,
    onPairedSuccess: (parentId: String, parentName: String) -> Unit
) {
    val context = LocalContext.current
    var pairingCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Permission check states
    var isAccessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    val adminComp = ComponentName(context, ScreenGuardDeviceAdminReceiver::class.java)
    var isAdminOn by remember { mutableStateOf(dpm?.isAdminActive(adminComp) == true) }

    // Re-check permissions when coming back
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityOn = isAccessibilityEnabled(context)
            isAdminOn = dpm?.isAdminActive(adminComp) == true
            delay(1500)
        }
    }

    // VPN launcher
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            FamilyDnsVpnService.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Shield Logo Badge
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
            Icon(Icons.Default.Security, contentDescription = "Shield", tint = CyberSky, modifier = Modifier.size(40.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ScreenGuard Shield",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Enter the 6-digit pairing code shown on your parent's phone to connect.",
            fontSize = 13.sp,
            color = Slate400,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 6-Digit Code Input Box
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(6)
                pairingCode = digits
                errorMessage = null
            },
            placeholder = { Text("000000", color = Slate700, fontSize = 28.sp, fontWeight = FontWeight.Bold) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberSky,
                unfocusedBorderColor = Slate700,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Slate900,
                unfocusedContainerColor = Slate900
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 8.sp
            )
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = errorMessage ?: "", color = RoseRed, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (pairingCode.length != 6) {
                    errorMessage = "Please enter the full 6-digit code"
                    return@Button
                }
                isLoading = true
                errorMessage = null

                val childDeviceId = prefs.getDeviceId()
                val childModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

                PairingManager.linkChildWithCode(
                    inputCode = pairingCode,
                    childDeviceId = childDeviceId,
                    childModel = childModel,
                    onSuccess = { parentId, parentName ->
                        isLoading = false
                        // Request VPN permission
                        val vpnIntent = VpnService.prepare(context)
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent)
                        } else {
                            FamilyDnsVpnService.start(context)
                        }
                        onPairedSuccess(parentId, parentName)
                    },
                    onError = { err ->
                        isLoading = false
                        errorMessage = err
                    }
                )
            },
            enabled = !isLoading && pairingCode.length == 6,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CyberSky, disabledContainerColor = Slate800),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Slate950, strokeWidth = 2.dp)
            } else {
                Text("Connect to Guardian", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Required Permissions Checklist
        Text(
            text = "REQUIRED SECURITY SETUP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Permission Card 1: Accessibility
        PermissionCard(
            title = "Accessibility Service",
            description = "Enforces instant app exits when restricted content appears.",
            isGranted = isAccessibilityOn,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Permission Card 2: Device Admin
        PermissionCard(
            title = "Anti-Uninstall Protection",
            description = "Locks the app so it cannot be dragged to uninstall.",
            isGranted = isAdminOn,
            onClick = {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects ScreenGuard from unauthorized removal.")
                }
                context.startActivity(intent)
            }
        )
    }
}

@Composable
fun ChildProtectedHubScreen(
    prefs: PreferencesManager,
    parentName: String,
    freeTimeUntil: Long,
    uninstallUnlockedUntil: Long
) {
    val context = LocalContext.current
    var isBreakRequesting by remember { mutableStateOf(false) }
    var selectedMinutes by remember { mutableIntStateOf(15) }
    var freeTimeRequestStatus by remember { mutableStateOf<String?>(null) }

    var isUninstallExpanded by remember { mutableStateOf(false) }
    var uninstallReason by remember { mutableStateOf("") }
    var isSubmittingUninstall by remember { mutableStateOf(false) }
    var uninstallRequestStatus by remember { mutableStateOf<String?>(null) }

    val isFreeTimeActive = System.currentTimeMillis() < freeTimeUntil
    val isUninstallUnlocked = System.currentTimeMillis() < uninstallUnlockedUntil

    val wordCount = remember(uninstallReason) {
        uninstallReason.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Status Card: Protection Active
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isFreeTimeActive) AmberOrange.copy(alpha = 0.6f) else EmeraldGreen.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (isFreeTimeActive) AmberOrange.copy(alpha = 0.15f) else EmeraldGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFreeTimeActive) Icons.Default.PauseCircle else Icons.Default.VerifiedUser,
                        contentDescription = "Protection",
                        tint = if (isFreeTimeActive) AmberOrange else EmeraldGreen,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isFreeTimeActive) "Free Time Active" else "Protection Active",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isFreeTimeActive) AmberOrange else EmeraldGreen)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Linked to $parentName",
                        fontSize = 12.sp,
                        color = Slate400
                    )
                }

                if (isFreeTimeActive) {
                    val remainingMins = ((freeTimeUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⏳ $remainingMins minutes remaining in your approved break.",
                        fontSize = 12.sp,
                        color = AmberOrange,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active Protection Badges
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProtectionBadge(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Dns,
                title = "Family DNS",
                subtitle = "Cloudflare 1.1.1.3",
                tint = CyberSky
            )
            ProtectionBadge(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.VisibilityOff,
                title = "Incognito",
                subtitle = "100% Blocked",
                tint = ElectricBlue
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // CARD 1: Request Free Time (Break)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HourglassTop, contentDescription = "Break", tint = CyberSky, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Request Free Time / Break", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "If a safe educational or gaming site is blocked by mistake, you can request temporary free time. (Incognito and Adult DNS remain 100% blocked).",
                    fontSize = 12.sp,
                    color = Slate400,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Pill Duration Selectors
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { mins ->
                        val isSelected = selectedMinutes == mins
                        Button(
                            onClick = { selectedMinutes = mins },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) CyberSky else Slate800
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                text = "$mins min",
                                color = if (isSelected) Slate950 else Slate200,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        isBreakRequesting = true
                        freeTimeRequestStatus = null
                        FamilyRepository.requestFreeTime(context, selectedMinutes) { success, msg ->
                            isBreakRequesting = false
                            freeTimeRequestStatus = msg
                        }
                    },
                    enabled = !isBreakRequesting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Slate700)
                ) {
                    if (isBreakRequesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Send Request for $selectedMinutes Mins", fontSize = 13.sp, color = Color.White)
                    }
                }

                if (freeTimeRequestStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = freeTimeRequestStatus ?: "", fontSize = 12.sp, color = CyberSky)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // CARD 2: 50-Word Friction Uninstall Request
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isUninstallUnlocked) AmberOrange else Slate800)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Uninstall", tint = if (isUninstallUnlocked) AmberOrange else RoseRed, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isUninstallUnlocked) "Uninstall Unlocked" else "Need to Uninstall?",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    TextButton(onClick = { isUninstallExpanded = !isUninstallExpanded }) {
                        Text(if (isUninstallExpanded) "Hide" else "Expand", color = CyberSky, fontSize = 12.sp)
                    }
                }

                if (isUninstallUnlocked) {
                    val remainingMins = ((uninstallUnlockedUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ Uninstallation Approved by Parent! You have $remainingMins minutes to deactivate Device Admin and uninstall ScreenGuard.",
                        fontSize = 12.sp,
                        color = AmberOrange,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open Security Settings", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                AnimatedVisibility(visible = isUninstallExpanded && !isUninstallUnlocked) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To prevent impulsive removal, you must write at least 50 words explaining why you need to uninstall. Your parent will review your reason.",
                            fontSize = 12.sp,
                            color = Slate400,
                            lineHeight = 17.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = uninstallReason,
                            onValueChange = { uninstallReason = it },
                            placeholder = { Text("Write your 50-word reason here...", color = Slate700, fontSize = 13.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (wordCount >= 50) EmeraldGreen else CyberSky,
                                unfocusedBorderColor = Slate700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Slate950,
                                unfocusedContainerColor = Slate950
                            )
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Word count: $wordCount / 50 required",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (wordCount >= 50) EmeraldGreen else RoseRed
                            )
                            if (wordCount < 50) {
                                Text(
                                    text = "${50 - wordCount} words needed",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                isSubmittingUninstall = true
                                uninstallRequestStatus = null
                                FamilyRepository.requestUninstall(context, uninstallReason, wordCount) { success, msg ->
                                    isSubmittingUninstall = false
                                    uninstallRequestStatus = msg
                                    if (success) {
                                        Toast.makeText(context, "Request sent to parent!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = wordCount >= 50 && !isSubmittingUninstall,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (wordCount >= 50) RoseRed else Slate800
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isSubmittingUninstall) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("Submit Request to Parent", fontSize = 13.sp, color = Color.White)
                            }
                        }

                        if (uninstallRequestStatus != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = uninstallRequestStatus ?: "", fontSize = 12.sp, color = EmeraldGreen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProtectionBadge(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = subtitle, fontSize = 11.sp, color = Slate400)
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isGranted) EmeraldGreen.copy(alpha = 0.5f) else Slate800)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(2.dp))
                Text(description, fontSize = 11.sp, color = Slate400, lineHeight = 15.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isGranted) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = EmeraldGreen, modifier = Modifier.size(24.dp))
            } else {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Enable", fontSize = 12.sp, color = CyberSky)
                }
            }
        }
    }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val expected = ComponentName(context, ScreenGuardAccessibilityService::class.java).flattenToString()
    return enabledServices.any {
        val current = ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name).flattenToString()
        current.equals(expected, ignoreCase = true)
    }
}
