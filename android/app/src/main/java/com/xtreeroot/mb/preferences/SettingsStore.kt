package com.xtreeroot.mb.preferences

import android.content.Context
import androidx.core.content.edit
import com.xtreeroot.mb.edge.NodeBinding
import com.xtreeroot.mb.edge.PlatformProfile
import com.xtreeroot.mb.edge.nodeOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal class SettingsStore(context: Context) {
    private val storage = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val nodeStorage = context.getSharedPreferences("node-binding", Context.MODE_PRIVATE)
    private val nodeState = MutableStateFlow(nodeStorage.getString("binding", null)?.let { raw ->
        runCatching {
            val value = JSONObject(raw)
            NodeBinding(nodeOrigin(value.getString("origin")), value.getString("device"), value.getString("space"),
                value.getString("token"), value.getLong("expiresAt"), value.getString("recoveryKey"), value.getString("catalogPublicKey"))
        }.getOrNull()
    })
    private val state = MutableStateFlow(storage.getString("preferences", null)
        ?.let { runCatching { AppPreferences.parse(JSONObject(it)) }.getOrNull() } ?: AppPreferences())
    val values = state.asStateFlow()
    val nodeBinding = nodeState.asStateFlow()

    @Synchronized
    fun setNodeBinding(binding: NodeBinding?) {
        val previous = nodeState.value
        val value = binding?.let { JSONObject().put("origin", it.origin.toString()).put("device", it.device).put("space", it.space)
            .put("token", it.token).put("expiresAt", it.expiresAt).put("recoveryKey", it.recoveryKey)
            .put("catalogPublicKey", it.catalogPublicKey).toString() }
        nodeStorage.edit {
            if (value == null) remove("binding") else putString("binding", value)
            if (binding == null || previous?.origin != binding.origin || previous.space != binding.space ||
                previous.catalogPublicKey != binding.catalogPublicKey) remove("profiles")
        }
        nodeState.value = binding
    }

    @Synchronized
    fun cacheNodeProfile(binding: NodeBinding, platform: String, profile: PlatformProfile) {
        if (nodeState.value !== binding) return
        val profiles = nodeStorage.getString("profiles", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        profiles.put(platform, profile.payload().getJSONObject("value"))
        nodeStorage.edit { putString("profiles", profiles.toString()) }
    }

    @Synchronized
    fun update(edit: (AppPreferences) -> AppPreferences) { save(edit(state.value)) }

    @Synchronized
    fun restoreIfUnset(imported: AppPreferences) {
        if (!storage.contains("preferences")) save(imported)
    }

    private fun save(value: AppPreferences) {
        if (state.value == value && storage.contains("preferences")) return
        storage.edit { putString("preferences", value.toJson().toString()) }
        state.value = value
    }
}
