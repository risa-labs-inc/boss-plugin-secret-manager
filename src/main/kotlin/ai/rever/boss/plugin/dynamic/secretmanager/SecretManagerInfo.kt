package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock

object SecretManagerInfo : PanelInfo {
    override val id = PanelId("secret-manager", 24)
    override val displayName = "Secret Manager"
    override val icon = Icons.Outlined.Lock
    override val defaultSlotPosition = right.top.bottom
}
