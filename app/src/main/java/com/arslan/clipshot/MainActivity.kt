package com.arslan.clipshot

import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ClipShotScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ClipShotScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // Permission state, refreshed whenever the screen resumes.
    var permRefresh by remember { mutableIntStateOf(0) }
    val hasAllFiles = remember(permRefresh) { Permissions.hasAllFilesAccess() }
    val hasNotif = remember(permRefresh) { Permissions.hasNotificationPermission(context) }
    val ignoresBattery = remember(permRefresh) { Permissions.isIgnoringBatteryOptimizations(context) }

    var watchedPath by remember { mutableStateOf(prefs.watchedPath) }
    var delaySeconds by remember { mutableIntStateOf(prefs.notificationDelaySeconds) }
    var watcherEnabled by remember { mutableStateOf(prefs.watcherEnabled) }

    val lifecycleOwner = context.findActivity() as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permRefresh++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permRefresh++ }

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

        // ---- Permissions ----
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

        // ---- Watched folder ----
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

        // ---- Notification delay ----
        SectionCard(title = stringRes(R.string.section_delay)) {
            DelayDropdown(
                selected = delaySeconds,
                onSelect = {
                    delaySeconds = it
                    prefs.notificationDelaySeconds = it
                }
            )
        }

        // ---- Watcher toggle ----
        SectionCard(title = stringRes(R.string.section_watcher)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringRes(R.string.watcher_switch_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = watcherEnabled,
                    enabled = canWatch,
                    onCheckedChange = { checked ->
                        watcherEnabled = checked
                        prefs.watcherEnabled = checked
                        if (checked) ScreenshotService.start(context)
                        else ScreenshotService.stop(context)
                    }
                )
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelayDropdown(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = "$selected s",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringRes(R.string.delay_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Prefs.DELAY_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option s") },
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
