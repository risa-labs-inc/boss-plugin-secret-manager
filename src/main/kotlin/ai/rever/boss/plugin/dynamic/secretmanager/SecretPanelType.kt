package ai.rever.boss.plugin.dynamic.secretmanager

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The panel's type scale, mirroring the host's "Operator's Console" system.
 *
 * **Why a local copy rather than the real tokens.** `BossTypography` and `object BossTheme` live in
 * the host's `plugin-ui-core` module and are **not** on the plugin api - the api jar ships only
 * `BossThemeColors`, `BossColors`, the `BossTheme` wrapper and `BossComponents`. So a plugin cannot
 * read `BossTheme.type`, and the choice is between this and the 118 loose `fontSize = N.sp`
 * literals that were here before, spread across nine different sizes (9, 10, 11, 12, 13, 14, 16,
 * 18, 20) with no rule about which meant what.
 *
 * The values are not invented. They are taken from
 * `BossConsole/plugin-platform/plugin-ui-core/.../BossDesignSystem.kt` (`bossTypography()`) and
 * cross-checked against what the plugin-facing `BossComponents` actually paint, since those
 * components render beside this panel's own text and any disagreement shows: `BossSection` titles
 * at 16/SemiBold, `BossInfoRow` values at 13 with 11 sub-labels, `BossEmptyState` at 14/Medium over
 * 12, `BossBadge` at 10/Medium.
 *
 * **Mono is used sparingly and deliberately.** The system's voice is mono for display and data,
 * sans for running copy - but the host injects **MesloLGS** into its own typography and a plugin
 * cannot reach that `FontFamily`, so mono here resolves to the platform's. Using it for every
 * heading would put a *different* mono beside the host's chrome. It is therefore confined to the
 * two roles where the shape carries meaning rather than brand: [label], where the tracking is the
 * identity, and [data], where a credential should be read a character at a time.
 *
 * If the tokens ever reach the plugin api, this object should be deleted and its call sites
 * pointed at `BossTheme.type`.
 */
internal object SecretPanelType {
    /**
     * Section and dialog headings. Matches `BossSection`'s own title so the two can sit together.
     *
     * Every call site used to pass `fontWeight = FontWeight.Bold` straight after this, so the
     * weight declared here never reached the screen and the panel's headings were a step heavier
     * than the host's. The overrides are gone; if a heading needs to be heavier than the system's,
     * that is a change to this token, not to one `Text`.
     */
    val title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

    /** The primary line of a card - the thing being named. */
    val bodyStrong = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp)

    /** Running copy, values, button labels. The system's `body`. */
    val body = TextStyle(fontSize = 13.sp)

    /** A credential or other value read character by character. The system's `data`. */
    val data = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)

    /** Supporting text: a second line, a hint, a dialog's explanation. */
    val meta = TextStyle(fontSize = 12.sp)

    /**
     * [meta] where it labels something rather than saying it - "Recovery codes:", "Currently
     * shared with:", a field's name above its value.
     *
     * It exists so those four sites stop carrying an inline `fontWeight`. Weight is part of a
     * type scale, and a scale with a named size but a hand-set weight at every call site is the
     * 118-loose-`fontSize` problem again in a slower form.
     */
    val metaStrong = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)

    /**
     * The quietest sans register: a timestamp, a field hint, a warning under a form. Matches
     * `BossInfoRow`'s own sub-label, which is why it is 11 and not 10 - [micro] is mono and reads
     * as data, which a sentence is not.
     */
    val caption = TextStyle(fontSize = 11.sp)

    /**
     * Eyebrows, tab labels and badges. The tracking is what makes this read as an operator's
     * label rather than small body text, so it is the one place mono is load-bearing.
     */
    val label =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
        )

    /** Counts, timings, footnotes - the quietest register. */
    val micro =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 1.0.sp,
        )
}
