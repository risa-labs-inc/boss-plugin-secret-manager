package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Lock

/**
 * Panel info for Secret Manager
 *
 * This panel allows users with 'secrets.write' permission to:
 * - View all their stored credentials (website:username)
 * - Add new secrets with encryption
 * - Update existing secrets
 * - Delete secrets
 * - Manage 2FA information and recovery codes
 * - Organize secrets with tags
 * - Track password expiration dates
 *
 * Access Control:
 * - Only accessible to users with 'secrets.write' permission OR admin role
 * - RLS policies enforce server-side authorization
 * - Users can only manage their own secrets
 * - Non-authorized users will never see this panel
 */
object SecretManagerInfo : PanelInfo {
    override val id = PanelId("secret-manager", 24)
    override val displayName = "Secret Manager"
    override val icon = FeatherIcons.Lock
    override val defaultSlotPosition = right.top.bottom
}
