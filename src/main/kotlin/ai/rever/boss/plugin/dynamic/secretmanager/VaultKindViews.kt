package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Logins / Cards / Addresses / Keys & tokens switcher inside the Secrets section. */
@Composable
internal fun VaultKindFilter(
    selected: VaultKind,
    counts: Map<VaultKind, Int>,
    /** More pages exist, so counts are a floor. */
    partial: Boolean,
    onSelect: (VaultKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VaultKind.entries.forEach { kind ->
            val isSelected = kind == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) BossThemeColors.AccentColor.copy(alpha = 0.12f) else BossThemeColors.BackgroundColor)
                    .selectable(selected = isSelected, onClick = { onSelect(kind) }, role = Role.Tab)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    kind.label,
                    style = SecretPanelType.metaStrong,
                    color = if (isSelected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                    maxLines = 1,
                )
                Text(
                    "${counts[kind] ?: 0}${if (partial) "+" else ""}",
                    style = SecretPanelType.micro,
                    color = BossThemeColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun VaultCardItem(
    card: VaultRow.Card,
    canManage: Boolean,
    clipboard: ClipboardManager,
    onDelete: () -> Unit,
) {
    BossCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CreditCard, contentDescription = "Card", tint = BossThemeColors.TextSecondary, modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(card.brand, color = BossThemeColors.TextPrimary, style = SecretPanelType.bodyStrong, maxLines = 1)
                        Text("${VaultRow.MASK} ${card.last4}", color = BossThemeColors.TextPrimary, style = SecretPanelType.data, maxLines = 1)
                    }
                    val sub = listOfNotNull(
                        card.expiry.takeIf { it.isNotBlank() }?.let { "Expires $it" },
                        card.holder.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(sub, color = BossThemeColors.TextSecondary, style = SecretPanelType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ManageActions(canManage = canManage, onEdit = null, onDelete = onDelete)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (card.last4.isNotBlank()) CopyChip("Last 4", card.last4, clipboard)
                if (card.expiry.isNotBlank()) CopyChip("Expiry", card.expiry, clipboard)
                if (card.holder.isNotBlank()) CopyChip("Name", card.holder, clipboard)
            }
        }
    }
}

@Composable
internal fun VaultCardCodeItem(
    code: VaultRow.CardCode,
    canManage: Boolean,
    onDelete: () -> Unit,
) {
    BossCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = BossThemeColors.TextSecondary, modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(code.title, color = BossThemeColors.TextPrimary, style = SecretPanelType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Sealed. Used only at checkout.", color = BossThemeColors.TextSecondary, style = SecretPanelType.meta, maxLines = 1)
            }
            ManageActions(canManage = canManage, onEdit = null, onDelete = onDelete)
        }
    }
}

@Composable
internal fun VaultAddressItem(
    address: VaultRow.Address,
    canManage: Boolean,
    clipboard: ClipboardManager,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val f = address.fields
    BossCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Home, contentDescription = "Address", tint = BossThemeColors.TextSecondary, modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(address.title, color = BossThemeColors.TextPrimary, style = SecretPanelType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = f.oneLine()
                Text(
                    line.ifBlank { "No street address" },
                    color = BossThemeColors.TextSecondary,
                    style = SecretPanelType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val contact = listOf(f.email, f.phone).filter { it.isNotBlank() }.joinToString(" · ")
                if (contact.isNotBlank()) {
                    Text(contact, color = BossThemeColors.TextSecondary, style = SecretPanelType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (line.isNotBlank()) {
                    Row(modifier = Modifier.padding(top = 4.dp)) { CopyChip("Address", line, clipboard) }
                }
            }
            ManageActions(canManage = canManage, onEdit = onEdit, onDelete = onDelete)
        }
    }
}

@Composable
private fun ManageActions(canManage: Boolean, onEdit: (() -> Unit)?, onDelete: () -> Unit) {
    if (!canManage) {
        Icon(Icons.Default.Lock, contentDescription = "Read-only", tint = BossThemeColors.TextSecondary, modifier = Modifier.size(16.dp))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (onEdit != null) ActionIcon(Icons.Default.Edit, "Edit", BossThemeColors.TextSecondary, onEdit)
        ActionIcon(Icons.Default.Delete, "Delete", BossThemeColors.ErrorColor.copy(alpha = 0.75f), onDelete)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Copies a non-secret value (last 4, expiry, name). */
@Composable
private fun CopyChip(label: String, value: String, clipboard: ClipboardManager) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClickLabel = "Copy $label") {
                clipboard.setText(AnnotatedString(value))
                copied = true
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = BossThemeColors.TextSecondary, modifier = Modifier.size(12.dp))
        Text(
            if (copied) "Copied" else label,
            color = if (copied) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary,
            style = SecretPanelType.caption,
        )
    }
}

@Composable
internal fun AddCardDialog(
    takenWebsites: Set<String>,
    onConfirm: (VaultRows.NewRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    val digits = number.filter { it.isDigit() }
    val row = VaultRows.newCard(number, expiry, holder)
    val error = when {
        digits.length >= 13 && !VaultRows.luhn(digits) -> "That number is not a valid card number."
        expiry.length >= 4 && VaultRows.parseExpiry(expiry) == null -> "Use MM/YY for the expiry."
        else -> null
    }
    val duplicate = row != null && row.website in takenWebsites
    FormDialog(
        title = "Add card",
        confirmLabel = "Save card",
        canConfirm = row != null,
        onConfirm = { row?.let(onConfirm) },
        onDismiss = onDismiss,
    ) {
        DialogTextField(value = number, onValueChange = { v -> number = v.filter { it.isDigit() || it == ' ' }.take(23) }, label = "Card number", isPassword = true)
        DialogTextField(value = expiry, onValueChange = { expiry = it.take(7) }, label = "Expiry", placeholder = "MM/YY")
        DialogTextField(value = holder, onValueChange = { holder = it }, label = "Name on card")
        if (duplicate) {
            Text(
                "A card ending ${digits.takeLast(4)} is already saved. Saving adds a second one.",
                color = BossThemeColors.WarningColor,
                style = SecretPanelType.caption,
            )
        }
        Text(
            error ?: "The security code is not stored here. Autofill and Max ask for it at checkout.",
            color = if (error != null) BossThemeColors.ErrorColor else BossThemeColors.TextSecondary,
            style = SecretPanelType.caption,
        )
    }
}

@Composable
internal fun AddressDialog(
    initial: AddressFields?,
    onConfirm: (AddressFields) -> Unit,
    onDismiss: () -> Unit,
) {
    var f by remember { mutableStateOf(initial ?: AddressFields()) }
    val filled = f.street.isNotBlank() || f.city.isNotBlank() || f.zip.isNotBlank()
    FormDialog(
        title = if (initial == null) "Add address" else "Edit address",
        confirmLabel = "Save",
        canConfirm = filled,
        onConfirm = { onConfirm(f) },
        onDismiss = onDismiss,
    ) {
        DialogTextField(value = f.name, onValueChange = { f = f.copy(name = it) }, label = "Full name")
        DialogTextField(value = f.street, onValueChange = { f = f.copy(street = it) }, label = "Street")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { DialogTextField(value = f.city, onValueChange = { f = f.copy(city = it) }, label = "City") }
            Box(Modifier.weight(1f)) { DialogTextField(value = f.state, onValueChange = { f = f.copy(state = it) }, label = "State / region") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { DialogTextField(value = f.zip, onValueChange = { f = f.copy(zip = it) }, label = "Postal code") }
            Box(Modifier.weight(1f)) { DialogTextField(value = f.country, onValueChange = { f = f.copy(country = it) }, label = "Country") }
        }
        DialogTextField(value = f.email, onValueChange = { f = f.copy(email = it) }, label = "Email (optional)")
        DialogTextField(value = f.phone, onValueChange = { f = f.copy(phone = it) }, label = "Phone (optional)")
        DialogTextField(value = f.company, onValueChange = { f = f.copy(company = it) }, label = "Company (optional)")
    }
}

@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BossDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(400.dp), color = BossThemeColors.SurfaceColor, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = BossThemeColors.TextPrimary, style = SecretPanelType.title)
                content()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = BossThemeColors.TextSecondary, style = SecretPanelType.body) }
                    Spacer(Modifier.width(8.dp))
                    BossPrimaryButton(text = confirmLabel, onClick = onConfirm, enabled = canConfirm)
                }
            }
        }
    }
}

/** Empty list for one kind, with a footer action when more pages exist. */
@Composable
internal fun VaultKindEmpty(kind: VaultKind, hasMore: Boolean, onLoadMore: () -> Unit, onAdd: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when (kind) {
                VaultKind.LOGIN -> "No logins loaded"
                VaultKind.CARD -> "No cards loaded"
                VaultKind.ADDRESS -> "No addresses loaded"
                VaultKind.KEY -> "No keys or tokens loaded"
            },
            color = BossThemeColors.TextPrimary,
            style = SecretPanelType.bodyStrong,
        )
        if (kind == VaultKind.CARD || kind == VaultKind.ADDRESS) {
            Text(
                "Saved by BOSS browser autofill and Max.",
                color = BossThemeColors.TextSecondary,
                style = SecretPanelType.meta,
            )
        }
        if (hasMore) {
            TextButton(onClick = onLoadMore) { Text("Load more", color = BossThemeColors.AccentColor, style = SecretPanelType.body) }
        }
        if (onAdd != null) {
            TextButton(onClick = onAdd) {
                Text(if (kind == VaultKind.CARD) "Add card" else "Add address", color = BossThemeColors.AccentColor, style = SecretPanelType.body)
            }
        }
    }
}

/** Website keys already used, for picking a free `address/<label>`. */
internal fun takenWebsites(secrets: List<SecretEntryData>): Set<String> = secrets.mapTo(mutableSetOf()) { it.website }
