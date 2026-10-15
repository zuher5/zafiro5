package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.launch

@Composable
fun AutomationCommandCenter(
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionManager = remember { findService<PermissionManager>() }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var accessibilityGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var shellGranted by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        permissionManager?.let { pm ->
            accessibilityGranted = pm.status(Permission.ACCESSIBILITY) == PermissionState.GRANTED
            overlayGranted = pm.status(Permission.OVERLAY) == PermissionState.GRANTED
            val shizukuOk = pm.status(Permission.SHIZUKU) == PermissionState.GRANTED
            val rootOk = pm.status(Permission.ROOT) == PermissionState.GRANTED
            shellGranted = shizukuOk || rootOk
        }
    }

    DisposableEffect(lifecycleOwner) {
        refreshPermissions()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero Header
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.automation_center_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.automation_center_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Section 1: System Permissions Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.automation_health_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Accessibility Status Row
                PermissionStatusRow(
                    icon = Icons.Rounded.TouchApp,
                    title = stringResource(R.string.automation_perm_accessibility_title),
                    description = stringResource(R.string.automation_perm_accessibility_desc),
                    isGranted = accessibilityGranted,
                    activeLabel = stringResource(R.string.automation_status_active),
                    actionLabel = stringResource(R.string.automation_status_enable),
                    onRequest = {
                        coroutineScope.launch {
                            permissionManager?.request(Permission.ACCESSIBILITY)
                            refreshPermissions()
                        }
                    },
                )

                // Overlay Status Row
                PermissionStatusRow(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.automation_perm_overlay_title),
                    description = stringResource(R.string.automation_perm_overlay_desc),
                    isGranted = overlayGranted,
                    activeLabel = stringResource(R.string.automation_status_active),
                    actionLabel = stringResource(R.string.automation_status_enable),
                    onRequest = {
                        coroutineScope.launch {
                            permissionManager?.request(Permission.OVERLAY)
                            refreshPermissions()
                        }
                    },
                )

                // Shell Status Row (Shizuku / Root)
                PermissionStatusRow(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.automation_perm_shell_title),
                    description = stringResource(R.string.automation_perm_shell_desc),
                    isGranted = shellGranted,
                    activeLabel = stringResource(R.string.automation_status_ready),
                    actionLabel = stringResource(R.string.automation_status_check),
                    onRequest = {
                        coroutineScope.launch {
                            permissionManager?.request(Permission.SHIZUKU)
                            refreshPermissions()
                        }
                    },
                )
            }
        }

        // Section 2: Quick Action Cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.automation_actions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )

            // Card 1: Inspect Screen
            QuickActionCard(
                icon = Icons.Rounded.Screenshot,
                title = stringResource(R.string.automation_action_screen_title),
                description = stringResource(R.string.automation_action_screen_desc),
                prompt = stringResource(R.string.automation_action_screen_prompt),
                onSelectPrompt = onSelectPrompt,
            )

            // Card 2: Web Search (Tavily)
            QuickActionCard(
                icon = Icons.Rounded.Search,
                title = stringResource(R.string.automation_action_search_title),
                description = stringResource(R.string.automation_action_search_desc),
                prompt = stringResource(R.string.automation_action_search_prompt),
                onSelectPrompt = onSelectPrompt,
            )

            // Card 3: Device Automation
            QuickActionCard(
                icon = Icons.Rounded.PhoneAndroid,
                title = stringResource(R.string.automation_action_device_title),
                description = stringResource(R.string.automation_action_device_desc),
                prompt = stringResource(R.string.automation_action_device_prompt),
                onSelectPrompt = onSelectPrompt,
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    activeLabel: String,
    actionLabel: String,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val badgeContainerColor by animateColorAsState(
            targetValue = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
            animationSpec = tween(durationMillis = 200),
            label = "badgeContainerColor",
        )
        val badgeContentColor by animateColorAsState(
            targetValue = if (isGranted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
            animationSpec = tween(durationMillis = 200),
            label = "badgeContentColor",
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(badgeContainerColor)
                .clickable(enabled = !isGranted, onClick = onRequest)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isGranted) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = badgeContentColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = if (isGranted) activeLabel else actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = badgeContentColor,
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    prompt: String,
    onSelectPrompt: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelectPrompt(prompt) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
