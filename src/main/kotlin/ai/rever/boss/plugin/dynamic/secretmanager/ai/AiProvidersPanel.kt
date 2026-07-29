package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossSection
import ai.rever.boss.plugin.ui.BossTextField
import ai.rever.boss.plugin.ui.BossThemeColors
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings surface for AI providers: credentials, and a model picker driven by each
 * provider's own model list.
 *
 * Rendered in two places from one definition — inside this plugin's panel and, via
 * `LlmProviderSettingsAPI`, in the host's Settings window.
 */
@Composable
fun AiProvidersPanel(
    viewModel: AiProvidersViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val selected = ProviderRegistry.findOrDefault(state.selectedProviderId)

    // Scrolls itself: the host registers this as an embedded panel and does not wrap it
    // in a scroll container (nesting two would measure with infinite height and crash).
    // Padding is vertical-only — the host's embedded-panel path already pads and renders
    // the section header.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.legacyOffer?.let { offer ->
            LegacyImportBanner(
                offer = offer,
                onImport = viewModel::importLegacyKeys,
                onDismiss = viewModel::dismissLegacyOffer,
            )
        }

        if (!state.storeAvailable) {
            MessageBanner(
                text =
                    "Credential storage is unavailable — sign in to save keys. " +
                        "Providers configured by environment variable still work.",
                tint = BossThemeColors.WarningColor,
            )
        }
        state.error?.let { MessageBanner(it, BossThemeColors.ErrorColor) }
        state.notice?.let { MessageBanner(it, BossThemeColors.SuccessColor) }

        BossSection(
            title = "Providers",
            description = "Choose a provider, add its API key, then pick a model from its live list.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.providers.forEach { descriptor ->
                    ProviderRow(
                        descriptor = descriptor,
                        connection = state.connectionOf(descriptor.id),
                        isSelected = descriptor.id == state.selectedProviderId,
                        isActive = descriptor.id == state.activeProviderId,
                        onClick = { viewModel.selectProvider(descriptor.id) },
                    )
                }
            }
        }

        ProviderDetail(
            descriptor = selected,
            state = state,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun ProviderRow(
    descriptor: ProviderDescriptor,
    connection: ProviderConnection,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) BossThemeColors.AccentColor else BossThemeColors.BorderColor
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BossThemeColors.SurfaceColor)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(connection.source)
        Text(
            text = descriptor.displayName,
            fontSize = 13.sp,
            color = BossThemeColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (isActive) {
            Text(
                text = "Active",
                fontSize = 11.sp,
                color = BossThemeColors.AccentColor,
            )
        }
        Text(
            text = statusLabel(connection),
            fontSize = 11.sp,
            color = BossThemeColors.TextMuted,
        )
    }
}

@Composable
private fun StatusDot(source: CredentialSource) {
    val color =
        when (source) {
            CredentialSource.STORED -> BossThemeColors.SuccessColor
            CredentialSource.ENVIRONMENT -> BossThemeColors.SecondaryColor
            CredentialSource.NONE -> BossThemeColors.TextMuted
        }
    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

private fun statusLabel(connection: ProviderConnection): String =
    when (connection.source) {
        CredentialSource.STORED -> "Stored"
        CredentialSource.ENVIRONMENT -> connection.label?.let { "From $it" } ?: "From environment"
        CredentialSource.NONE -> "Not configured"
    }

@Composable
private fun ProviderDetail(
    descriptor: ProviderDescriptor,
    state: AiProvidersUiState,
    viewModel: AiProvidersViewModel,
) {
    val connection = state.connectionOf(descriptor.id)
    val busy = descriptor.id in state.busyProviderIds
    val fromEnvironment = connection.source == CredentialSource.ENVIRONMENT

    BossSection(title = descriptor.displayName) {
        BossCard {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (fromEnvironment) {
                    Text(
                        text =
                            "This key comes from the environment" +
                                (connection.label?.let { " ($it)" } ?: "") +
                                " and is read-only here. Unset it to manage the key in BOSS.",
                        fontSize = 12.sp,
                        color = BossThemeColors.TextSecondary,
                    )
                } else {
                    // A stored key is never rendered back — the field is for replacing
                    // it. Settings has no business displaying credential material.
                    BossTextField(
                        value = state.keyDrafts[descriptor.id].orEmpty(),
                        onValueChange = { viewModel.updateKeyDraft(descriptor.id, it) },
                        label = "API key",
                        placeholder =
                            if (connection.source == CredentialSource.STORED) {
                                "A key is stored — enter a new one to replace it"
                            } else {
                                descriptor.keyPlaceholder
                            },
                        enabled = state.storeAvailable && !busy,
                        singleLine = true,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BossPrimaryButton(
                            text = "Save key",
                            onClick = { viewModel.saveKey(descriptor.id) },
                            enabled = state.storeAvailable && !busy &&
                                state.keyDrafts[descriptor.id]?.isNotBlank() == true,
                        )
                        if (connection.source == CredentialSource.STORED) {
                            BossSecondaryButton(
                                text = "Remove",
                                onClick = { viewModel.clearKey(descriptor.id) },
                                enabled = !busy,
                                isDestructive = true,
                            )
                        }
                        if (descriptor.consoleUrl != null) {
                            BossSecondaryButton(
                                text = "Get API key",
                                onClick = { viewModel.openProviderConsole(descriptor.id) },
                                enabled = !busy,
                            )
                        }
                    }
                }

                if (descriptor.envVarNames.isNotEmpty() && !fromEnvironment) {
                    Text(
                        text = "Or set ${descriptor.envVarNames.joinToString(" / ")} in the environment.",
                        fontSize = 11.sp,
                        color = BossThemeColors.TextMuted,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ModelSection(
            descriptor = descriptor,
            connection = connection,
            catalog = state.catalogOf(descriptor.id),
            busy = busy,
            onSelectModel = { viewModel.selectModel(descriptor.id, it) },
            onRefresh = { viewModel.refreshModels(descriptor.id) },
            onTest = { viewModel.testConnection(descriptor.id) },
        )

        if (connection.isConfigured && descriptor.id != state.activeProviderId) {
            Spacer(modifier = Modifier.height(12.dp))
            BossSecondaryButton(
                text = "Use ${descriptor.displayName} for AI features",
                onClick = { viewModel.setActiveProvider(descriptor.id) },
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun ModelSection(
    descriptor: ProviderDescriptor,
    connection: ProviderConnection,
    catalog: CatalogState,
    busy: Boolean,
    onSelectModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
) {
    BossCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Model",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossThemeColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = BossThemeColors.AccentColor,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (descriptor.modelsEndpoint != null && connection.isConfigured) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh model list",
                        tint = BossThemeColors.TextSecondary,
                        modifier =
                            Modifier
                                .size(16.dp)
                                .clickable(enabled = !busy, onClick = onRefresh),
                    )
                }
            }

            when {
                descriptor.modelsEndpoint == null ->
                    Text(
                        text =
                            "${descriptor.displayName} has no model list to query — " +
                                "enter the model id your endpoint expects.",
                        fontSize = 12.sp,
                        color = BossThemeColors.TextSecondary,
                    )

                catalog is CatalogState.NotConfigured ->
                    Text(
                        text = "Add an API key to load ${descriptor.displayName}'s models.",
                        fontSize = 12.sp,
                        color = BossThemeColors.TextSecondary,
                    )

                catalog is CatalogState.Loading ->
                    Text(
                        text = "Loading models from ${descriptor.displayName}…",
                        fontSize = 12.sp,
                        color = BossThemeColors.TextSecondary,
                    )

                else -> {
                    val loaded =
                        when (catalog) {
                            is CatalogState.Loaded -> catalog
                            is CatalogState.Failed -> catalog.lastKnown
                            else -> null
                        }

                    if (catalog is CatalogState.Failed) {
                        Text(
                            text = catalog.message,
                            fontSize = 12.sp,
                            color = BossThemeColors.ErrorColor,
                        )
                    }

                    if (loaded == null) {
                        Text(
                            text = "No models available yet.",
                            fontSize = 12.sp,
                            color = BossThemeColors.TextSecondary,
                        )
                    } else {
                        ModelPicker(
                            models = loaded.models,
                            selectedModelId = connection.selectedModelId,
                            enabled = !busy,
                            onSelect = onSelectModel,
                        )
                        FreshnessLine(loaded)
                        loaded.models
                            .firstOrNull { it.id == connection.selectedModelId }
                            ?.let { ModelFacts(it) }
                    }
                }
            }

            if (connection.isConfigured && descriptor.modelsEndpoint != null) {
                BossSecondaryButton(
                    text = "Test connection",
                    onClick = onTest,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<AiModel>,
    selectedModelId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedModelId }

    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BossThemeColors.BackgroundColor)
                    .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.displayName ?: "Select a model",
                fontSize = 13.sp,
                color = if (selected == null) BossThemeColors.TextMuted else BossThemeColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp).background(BossThemeColors.SurfaceColor),
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSelect(model.id)
                    },
                ) {
                    Column {
                        Text(
                            text = model.displayName,
                            fontSize = 13.sp,
                            color = BossThemeColors.TextPrimary,
                        )
                        if (model.displayName != model.id) {
                            Text(
                                text = model.id,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = BossThemeColors.TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * States when the list was retrieved. The whole point of fetching live is that the
 * user can tell — a silent list is indistinguishable from the hardcoded one this
 * replaced.
 */
@Composable
private fun FreshnessLine(loaded: CatalogState.Loaded) {
    val ageMinutes = ((System.currentTimeMillis() - loaded.fetchedAtEpochMs) / 60_000L).coerceAtLeast(0)
    val age =
        when {
            ageMinutes < 1 -> "just now"
            ageMinutes < 60 -> "$ageMinutes min ago"
            ageMinutes < 60 * 24 -> "${ageMinutes / 60} h ago"
            else -> "${ageMinutes / (60 * 24)} d ago"
        }
    val origin = if (loaded.fromCache) "cached" else "live"
    Text(
        text = "${loaded.models.size} models · $origin · updated $age",
        fontSize = 11.sp,
        color = BossThemeColors.TextMuted,
    )
}

@Composable
private fun ModelFacts(model: AiModel) {
    val facts =
        buildList {
            model.contextLength?.let { add("${formatTokens(it)} context") }
            model.maxOutputTokens?.let { add("${formatTokens(it)} max output") }
            if (model.capabilities.isNotEmpty()) add(model.capabilities.joinToString(", "))
            model.ownedBy?.let { add(it) }
        }
    if (facts.isEmpty()) return
    Text(
        text = facts.joinToString(" · "),
        fontSize = 11.sp,
        color = BossThemeColors.TextSecondary,
    )
}

private fun formatTokens(tokens: Int): String =
    when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }

@Composable
private fun LegacyImportBanner(
    offer: LegacyImportOffer,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    BossCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Import ${offer.providerIds.size} key(s) from previous settings",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
            )
            Text(
                text =
                    "Keys for ${offer.providerIds.joinToString(", ")} were found in " +
                        "${offer.sourcePath}. Importing moves them into encrypted storage and " +
                        "retires that file. Model choices are not imported — pick from each " +
                        "provider's current list instead.",
                fontSize = 12.sp,
                color = BossThemeColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BossPrimaryButton(text = "Import", onClick = onImport)
                BossSecondaryButton(text = "Not now", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun MessageBanner(
    text: String,
    tint: Color,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.12f))
                .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = text, fontSize = 12.sp, color = BossThemeColors.TextPrimary)
    }
}
