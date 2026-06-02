package com.arslan.clipshot

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.arslan.clipshot.ui.theme.ClipShotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipShotTheme {
                ClipShotApp()
            }
        }
    }
}

@Composable
fun ClipShotApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Permission state, refreshed whenever the screen resumes.
    var permRefresh by remember { mutableIntStateOf(0) }
    val hasAllFiles = remember(permRefresh) { Permissions.hasAllFilesAccess() }
    val hasNotif = remember(permRefresh) { Permissions.hasNotificationPermission(context) }
    val hasOverlay = remember(permRefresh) { Permissions.hasOverlayPermission(context) }
    val ignoresBattery = remember(permRefresh) { Permissions.isIgnoringBatteryOptimizations(context) }

    val lifecycleOwner = context.findActivity() as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permRefresh++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // Mode toggles are mutually exclusive: they share the same trigger event.
    var watcherEnabled by remember { mutableStateOf(prefs.watcherEnabled) }
    var overlayEnabled by remember { mutableStateOf(prefs.overlayEnabled) }

    fun setWatcher(on: Boolean) {
        watcherEnabled = on
        prefs.watcherEnabled = on
        if (on) {
            if (overlayEnabled) {
                overlayEnabled = false
                prefs.overlayEnabled = false
                OverlayService.stop(context)
            }
            ScreenshotService.start(context)
        } else {
            ScreenshotService.stop(context)
        }
    }

    fun setOverlay(on: Boolean) {
        overlayEnabled = on
        prefs.overlayEnabled = on
        if (on) {
            if (watcherEnabled) {
                watcherEnabled = false
                prefs.watcherEnabled = false
                ScreenshotService.stop(context)
            }
            OverlayService.start(context)
        } else {
            OverlayService.stop(context)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = {
                        Icon(painterResource(R.drawable.ic_stat_screenshot), contentDescription = null)
                    },
                    label = { Text(stringRes(R.string.nav_notification)) }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = {
                        Icon(painterResource(R.drawable.ic_overlay_copy), contentDescription = null)
                    },
                    label = { Text(stringRes(R.string.nav_overlay)) }
                )
            }
        }
    ) { innerPadding ->
        when (tab) {
            0 -> NotificationScreen(
                modifier = Modifier.padding(innerPadding),
                prefs = prefs,
                hasAllFiles = hasAllFiles,
                hasNotif = hasNotif,
                ignoresBattery = ignoresBattery,
                watcherEnabled = watcherEnabled,
                onSetWatcher = ::setWatcher,
                onRequestNotif = { permRefresh++ }
            )
            else -> OverlayScreen(
                modifier = Modifier.padding(innerPadding),
                prefs = prefs,
                hasAllFiles = hasAllFiles,
                hasOverlay = hasOverlay,
                overlayEnabled = overlayEnabled,
                onSetOverlay = ::setOverlay
            )
        }
    }
}

@Composable
private fun NotificationScreen(
    modifier: Modifier,
    prefs: Prefs,
    hasAllFiles: Boolean,
    hasNotif: Boolean,
    ignoresBattery: Boolean,
    watcherEnabled: Boolean,
    onSetWatcher: (Boolean) -> Unit,
    onRequestNotif: () -> Unit
) {
    val context = LocalContext.current
    var watchedPath by remember { mutableStateOf(prefs.watchedPath) }
    var delaySeconds by remember { mutableIntStateOf(prefs.notificationDelaySeconds) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRequestNotif() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = PathUtils.treeUriToPath(uri)
        if (path == null) {
            Toast.makeText(context, R.string.path_unresolved, Toast.LENGTH_LONG).show()
        } else {
            watchedPath = path
            prefs.watchedPath = path
            if (watcherEnabled) ScreenshotService.start(context) // re-watch new path
        }
    }

    val canWatch = hasAllFiles && hasNotif

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringRes(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        SectionCard(title = stringRes(R.string.section_permissions)) {
            PermissionRow(
                label = stringRes(R.string.perm_all_files),
                granted = hasAllFiles
            ) { context.startActivity(Permissions.allFilesAccessIntent(context)) }

            HorizontalDivider()

            PermissionRow(
                label = stringRes(R.string.perm_notifications),
                granted = hasNotif
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            HorizontalDivider()

            PermissionRow(
                label = stringRes(R.string.perm_battery),
                granted = ignoresBattery
            ) { context.startActivity(Permissions.batteryOptimizationIntent(context)) }
        }

        SectionCard(title = stringRes(R.string.section_folder)) {
            OutlinedTextField(
                value = watchedPath,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringRes(R.string.folder_path_label)) }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringRes(R.string.choose_folder)) }
        }

        SectionCard(title = stringRes(R.string.section_delay)) {
            ValueDropdown(
                label = stringRes(R.string.delay_label),
                selected = delaySeconds,
                options = Prefs.DELAY_OPTIONS,
                display = { "$it s" },
                onSelect = {
                    delaySeconds = it
                    prefs.notificationDelaySeconds = it
                }
            )
        }

        SectionCard(title = stringRes(R.string.section_watcher)) {
            ToggleRow(
                label = stringRes(R.string.watcher_switch_label),
                checked = watcherEnabled,
                enabled = canWatch,
                onCheckedChange = onSetWatcher
            )
            if (!canWatch) {
                Text(
                    text = stringRes(R.string.watcher_needs_perms),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun OverlayScreen(
    modifier: Modifier,
    prefs: Prefs,
    hasAllFiles: Boolean,
    hasOverlay: Boolean,
    overlayEnabled: Boolean,
    onSetOverlay: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var overlayX by remember { mutableIntStateOf(prefs.overlayX) }
    var overlayY by remember { mutableIntStateOf(prefs.overlayY) }
    var durationMs by remember { mutableIntStateOf(prefs.overlayDurationMs) }

    // Transient overlay used by the "Test position" button.
    val tester = remember { ScreenshotOverlay(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) { onDispose { tester.hide() } }

    val canOverlay = hasAllFiles && hasOverlay

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringRes(R.string.section_overlay),
            style = MaterialTheme.typography.headlineMedium
        )

        SectionCard(title = stringRes(R.string.section_permissions)) {
            PermissionRow(
                label = stringRes(R.string.perm_all_files),
                granted = hasAllFiles
            ) { context.startActivity(Permissions.allFilesAccessIntent(context)) }

            HorizontalDivider()

            PermissionRow(
                label = stringRes(R.string.perm_overlay),
                granted = hasOverlay
            ) { context.startActivity(Permissions.overlayPermissionIntent(context)) }
        }

        SectionCard(title = stringRes(R.string.section_overlay)) {
            ToggleRow(
                label = stringRes(R.string.overlay_switch_label),
                checked = overlayEnabled,
                enabled = canOverlay,
                onCheckedChange = onSetOverlay
            )
            Text(
                text = stringRes(R.string.overlay_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (canOverlay) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }

        SectionCard(title = stringRes(R.string.section_overlay_position)) {
            PositionSlider(
                label = stringRes(R.string.overlay_x_label),
                value = overlayX,
                onChange = { overlayX = it; prefs.overlayX = it }
            )
            PositionSlider(
                label = stringRes(R.string.overlay_y_label),
                value = overlayY,
                onChange = { overlayY = it; prefs.overlayY = it }
            )
            ValueDropdown(
                label = stringRes(R.string.overlay_duration_label),
                selected = durationMs,
                options = Prefs.OVERLAY_DURATION_OPTIONS,
                display = { "${it / 1000} s" },
                onSelect = {
                    durationMs = it
                    prefs.overlayDurationMs = it
                }
            )
            OutlinedButton(
                onClick = {
                    if (canOverlay) {
                        tester.hide()
                        tester.show(overlayX, overlayY) { tester.hide() }
                        mainHandler.postDelayed({ tester.hide() }, 1500L)
                    }
                },
                enabled = canOverlay,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringRes(R.string.overlay_test)) }
        }
    }
}

@Composable
private fun PositionSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: $value dp",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..Prefs.OVERLAY_POSITION_MAX.toFloat()
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueDropdown(
    label: String,
    selected: Int,
    options: List<Int>,
    display: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = display(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (granted) stringRes(R.string.granted) else stringRes(R.string.not_granted),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!granted) {
            Button(onClick = onGrant) { Text(stringRes(R.string.grant)) }
        }
    }
}

@Composable
private fun stringRes(id: Int): String = LocalContext.current.getString(id)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
