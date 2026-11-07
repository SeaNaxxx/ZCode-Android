package io.github.zcodeandroid.app.ui.settings

import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.zcodeandroid.app.BuildConfig
import io.github.zcodeandroid.app.local.RuntimeBus
import io.github.zcodeandroid.app.local.RuntimeState
import io.github.zcodeandroid.app.ui.components.SectionHeader
import io.github.zcodeandroid.app.ui.components.ZCodeTopBar
import io.github.zcodeandroid.app.ui.theme.TEXT_SIZE_DEFAULT
import io.github.zcodeandroid.app.ui.theme.THEME_DARK
import io.github.zcodeandroid.app.ui.theme.THEME_SYSTEM
import io.github.zcodeandroid.app.ui.theme.ZcodeTheme
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * Native settings (REVISED §5 Phase 1 surface; parity §2 — desktop settings
 * IA mapped to Android). Desktop groups (settingsPageConfig.ts) become:
 * General (appearance, device facts), Model (honest pointer to the per-task
 * picker), Agent capabilities (each desktop capability listed with its real
 * availability — nothing is presented as working when it is not, 10.3), and
 * Data (the verified backup/restore path, honest rows for the rest).
 *
 * Every control here is real: theme + text size are Android-owned UI state
 * (§7) backed by persisted prefs; backup/restore opens the verified
 * diagnostics flow. Rows labeled "Not on Android" are static honesty, not
 * disabled buttons pretending to work.
 */
@Composable
fun SettingsScreen(
    themeMode: Int,
    onSetThemeMode: (Int) -> Unit,
    textSize: Int,
    onSetTextSize: (Int) -> Unit,
    onOpenBackup: () -> Unit,
    onBack: () -> Unit,
) {
    val runtimeState by RuntimeBus.state.collectAsState()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            ZCodeTopBar(
                title = "Settings",
                subtitle = "This device",
                statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                statusLabel = "settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("General")
            SettingsCard {
                SettingChipsRow(
                    label = "Theme",
                    labels = listOf("System", "Light", "Dark"),
                    selected = themeMode,
                    onSelect = onSetThemeMode,
                )
                SettingChipsRow(
                    label = "Text size",
                    labels = listOf("Small", "Default", "Large"),
                    selected = textSize,
                    onSelect = onSetTextSize,
                )
            }
            SettingsCard {
                MetadataLine(
                    label = "Version",
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                MetadataLine(
                    label = "Device",
                    value = "${Build.MANUFACTURER} ${Build.MODEL} · Android " +
                        "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                )
                MetadataLine(
                    label = "ABIs",
                    value = Build.SUPPORTED_ABIS.joinToString(", "),
                )
                MetadataLine(
                    label = "Runtime",
                    value = when (val s = runtimeState) {
                        is RuntimeState.Running -> "running"
                        is RuntimeState.Provisioning -> "setting up (${s.percent}%)"
                        is RuntimeState.Ready -> "ready"
                        is RuntimeState.Failed -> "failed"
                        else -> s::class.simpleName ?: "unknown"
                    },
                )
            }

            SectionHeader("Model")
            SettingsCard {
                Text(
                    "Model selection happens per conversation — use the " +
                        "Model button in a chat's top bar. Provider configuration " +
                        "is owned by the runtime on this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Agent capabilities")
            SettingsCard {
                Text(
                    "Desktop capabilities that are not implemented on Android " +
                        "yet are listed so nothing presents itself as working " +
                        "when it is not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(ZcodeThemeValues.spacing.xs))
                UnavailableCapabilityRow("Memory")
                UnavailableCapabilityRow("Subagents")
                UnavailableCapabilityRow("Plugins")
                UnavailableCapabilityRow("MCP")
                UnavailableCapabilityRow("Skills")
                UnavailableCapabilityRow("Commands")
                UnavailableCapabilityRow("Hooks")
            }

            SectionHeader("Data")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBackup() }
                        .padding(vertical = ZcodeThemeValues.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Backup & restore",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Sessions, memory, and settings as a schema-versioned " +
                                "zip. Credentials are excluded by policy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                UnavailableCapabilityRow("Usage statistics")
                UnavailableCapabilityRow("Archive")
            }
            Spacer(Modifier.padding(ZcodeThemeValues.spacing.xl))
        }
    }
}

/** One bordered workbench card — same surface language as Home task cards. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.xs,
            ),
    ) {
        Column(
            modifier = Modifier.padding(ZcodeThemeValues.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
        ) {
            content()
        }
    }
}

/** Label + one chip row of mutually exclusive values (theme, text size). */
@Composable
private fun SettingChipsRow(
    label: String,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm)) {
            labels.forEachIndexed { index, chipLabel ->
                FilterChip(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    label = { Text(chipLabel) },
                )
            }
        }
    }
}

/** Read-only fact line (device identity, runtime state) — never a fake control. */
@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Honest availability row: the desktop capability name plus its real state.
 * Static by design (10.3: desktop-only must not be presented as available).
 */
@Composable
private fun UnavailableCapabilityRow(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZcodeThemeValues.spacing.sm),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Not on Android",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Settings — light", showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun SettingsScreenPreview() {
    ZcodeTheme(darkTheme = false) {
        SettingsScreen(
            themeMode = THEME_SYSTEM,
            onSetThemeMode = {},
            textSize = TEXT_SIZE_DEFAULT,
            onSetTextSize = {},
            onOpenBackup = {},
            onBack = {},
        )
    }
}

@Preview(
    name = "Settings — dark",
    showBackground = true,
    widthDp = 380,
    heightDp = 760,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsScreenDarkPreview() {
    ZcodeTheme(darkTheme = true) {
        SettingsScreen(
            themeMode = THEME_DARK,
            onSetThemeMode = {},
            textSize = TEXT_SIZE_DEFAULT,
            onSetTextSize = {},
            onOpenBackup = {},
            onBack = {},
        )
    }
}
