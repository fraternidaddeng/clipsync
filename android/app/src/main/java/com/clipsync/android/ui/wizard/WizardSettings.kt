package com.clipsync.android.ui.wizard

import com.clipsync.android.pairing.KeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import com.clipsync.android.ui.settings.formatSettingFlag
import com.clipsync.android.ui.settings.parseSettingFlag

/**
 * Persistence seam for wizard choices. The orchestrator binds this to the
 * existing settings store; this package does not import storage plumbing.
 */
interface WizardSettings {
    fun load(): WizardChoices

    fun save(choices: WizardChoices)

    fun loadSkippedSteps(): Set<WizardStepId>

    fun saveSkippedSteps(skipped: Set<WizardStepId>)
}

/** In-memory impl for JVM tests. */
class InMemoryWizardSettings(
    initial: WizardChoices = WizardChoices(),
    initialSkipped: Set<WizardStepId> = emptySet(),
) : WizardSettings {
    private var choices: WizardChoices = initial
    private var skipped: Set<WizardStepId> = initialSkipped.toSet()

    override fun load(): WizardChoices = choices

    override fun save(choices: WizardChoices) {
        this.choices = choices
    }

    override fun loadSkippedSteps(): Set<WizardStepId> = skipped

    override fun saveSkippedSteps(skipped: Set<WizardStepId>) {
        this.skipped = skipped.toSet()
    }
}

/**
 * [KeyValueStore] / SharedPreferences-backed wizard choices. Uses the same
 * store type as [com.clipsync.android.ui.settings.ClipServices] pairing
 * access. Keys are wizard-prefixed so existing settings flags stay untouched.
 */
class KeyValueWizardSettings(
    private val keyValues: KeyValueStore,
) : WizardSettings {
    override fun load(): WizardChoices {
        val defaults = WizardChoices()
        return WizardChoices(
            preferredReadMode = keyValues.read(KEY_PREFERRED_READ_MODE)
                ?.toEnumOrNull<ClipboardReadMode>()
                ?: defaults.preferredReadMode,
            autoFallbackAllowed = parseSettingFlag(
                keyValues.read(KEY_AUTO_FALLBACK),
                default = defaults.autoFallbackAllowed,
            ),
            pollingIntervalMs = WizardChoices.clampPollingIntervalMs(
                keyValues.read(KEY_POLLING_INTERVAL)?.toIntOrNull()
                    ?: defaults.pollingIntervalMs,
            ),
            backgroundAutoUpload = parseSettingFlag(
                keyValues.read(KEY_BACKGROUND_AUTO_UPLOAD),
                default = defaults.backgroundAutoUpload,
            ),
            backgroundAutoApply = parseSettingFlag(
                keyValues.read(KEY_BACKGROUND_AUTO_APPLY),
                default = defaults.backgroundAutoApply,
            ),
            overlayConsented = parseSettingFlag(
                keyValues.read(KEY_OVERLAY_CONSENTED),
                default = defaults.overlayConsented,
            ),
            writeMode = keyValues.read(KEY_WRITE_MODE)
                ?.toEnumOrNull<ClipboardWriteMode>()
                ?: defaults.writeMode,
            wizardCompleted = parseSettingFlag(
                keyValues.read(KEY_WIZARD_COMPLETED),
                default = defaults.wizardCompleted,
            ),
        )
    }

    override fun save(choices: WizardChoices) {
        keyValues.write(
            mapOf(
                KEY_PREFERRED_READ_MODE to choices.preferredReadMode.name,
                KEY_AUTO_FALLBACK to formatSettingFlag(choices.autoFallbackAllowed),
                KEY_POLLING_INTERVAL to WizardChoices.clampPollingIntervalMs(
                    choices.pollingIntervalMs,
                ).toString(),
                KEY_BACKGROUND_AUTO_UPLOAD to formatSettingFlag(choices.backgroundAutoUpload),
                KEY_BACKGROUND_AUTO_APPLY to formatSettingFlag(choices.backgroundAutoApply),
                KEY_OVERLAY_CONSENTED to formatSettingFlag(choices.overlayConsented),
                KEY_WRITE_MODE to choices.writeMode.name,
                KEY_WIZARD_COMPLETED to formatSettingFlag(choices.wizardCompleted),
            ),
        )
    }

    override fun loadSkippedSteps(): Set<WizardStepId> {
        val raw = keyValues.read(KEY_SKIPPED_STEPS) ?: return emptySet()
        if (raw.isBlank()) {
            return emptySet()
        }
        return raw.split(',')
            .mapNotNull { part -> part.trim().toEnumOrNull<WizardStepId>() }
            .toSet()
    }

    override fun saveSkippedSteps(skipped: Set<WizardStepId>) {
        val encoded = skipped.joinToString(",") { it.name }.ifEmpty { null }
        keyValues.write(mapOf(KEY_SKIPPED_STEPS to encoded))
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        runCatching { java.lang.Enum.valueOf(T::class.java, this) }.getOrNull()

    companion object {
        const val PREFIX = "wizard."
        const val KEY_PREFERRED_READ_MODE = PREFIX + "preferred_read_mode"
        const val KEY_AUTO_FALLBACK = PREFIX + "auto_fallback_allowed"
        const val KEY_POLLING_INTERVAL = PREFIX + "polling_interval_ms"
        const val KEY_BACKGROUND_AUTO_UPLOAD = PREFIX + "background_auto_upload"
        const val KEY_BACKGROUND_AUTO_APPLY = PREFIX + "background_auto_apply"
        const val KEY_OVERLAY_CONSENTED = PREFIX + "overlay_consented"
        const val KEY_WRITE_MODE = PREFIX + "write_mode"
        const val KEY_WIZARD_COMPLETED = PREFIX + "completed"
        const val KEY_SKIPPED_STEPS = PREFIX + "skipped_steps"
    }
}
