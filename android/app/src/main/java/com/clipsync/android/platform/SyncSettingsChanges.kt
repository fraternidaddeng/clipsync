package com.clipsync.android.platform

import android.content.Context
import android.content.SharedPreferences
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Change ticks for the sync settings file ([SyncSettingsStore.PREFERENCES_NAME]).
 * Several surfaces write that file — the preferences screen, the resident
 * notification's 暂停/恢复 actions ([com.clipsync.android.sync.SyncServiceNotification.applyAction]),
 * the pairing ritual's master-switch flip — so any long-lived mirror of these
 * settings must re-read on every change or go stale the moment another surface
 * writes. SharedPreferences only notifies for writes that actually changed a
 * value, so a tick always means the store's answer may differ from the mirror's.
 */
object SyncSettingsChanges {
    /**
     * One tick per changed write; conflated, because collectors re-read the store
     * rather than consume per-key payloads, so only "something changed since you
     * last looked" matters. The listener is registered for exactly as long as the
     * collection lives (the closure also keeps it strongly referenced, which
     * SharedPreferences itself does not).
     */
    fun changes(context: Context): Flow<Unit> =
        callbackFlow {
            val preferences =
                context.applicationContext
                    .getSharedPreferences(SyncSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate()
}
