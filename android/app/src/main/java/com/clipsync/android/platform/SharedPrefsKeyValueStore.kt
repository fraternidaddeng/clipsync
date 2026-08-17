package com.clipsync.android.platform

import android.content.Context
import com.clipsync.android.pairing.KeyValueStore

/** SharedPreferences-backed store; `commit()` keeps the multi-key write atomic on disk. */
class SharedPrefsKeyValueStore(context: Context, name: String = "clipsync.pairing") : KeyValueStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(values: Map<String, String?>) {
        val editor = preferences.edit()
        for ((key, value) in values) {
            if (value == null) {
                editor.remove(key)
            } else {
                editor.putString(key, value)
            }
        }
        editor.commit()
    }
}
