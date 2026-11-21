package com.xtreeroot.mb.edge

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.preferences.SettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NodeBindingDeviceTest {
    @Test fun nodeGrantSurvivesSettingsReopenAndIsSeparateFromPortablePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val store = SettingsStore(context)
        val recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val binding = NodeBinding(nodeOrigin("fixture.example"), "device", "space", "dGVzdC10b2tlbg", Long.MAX_VALUE,
            recovery, recovery)
        val before = store.values.value
        try {
            store.setNodeBinding(binding)
            val restored = SettingsStore(context).nodeBinding.value
            assertEquals(binding.origin, restored?.origin)
            assertEquals(binding.token, restored?.token)
            assertEquals(binding.recoveryKey, restored?.recoveryKey)
            assertEquals(before, store.values.value)
            assertFalse(store.values.value.toJson().toString().contains(binding.token))
            assertFalse(store.values.value.toJson().toString().contains(binding.recoveryKey))
            store.setNodeBinding(null)
            assertNull(SettingsStore(context).nodeBinding.value)
        } finally { store.setNodeBinding(null) }
    }

    @Test fun profileCacheIsPrivateAndDropsLateWritesFromAnOldBinding() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val store = SettingsStore(context)
        val storage = context.getSharedPreferences("node-binding", Context.MODE_PRIVATE)
        val recovery = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val origin = nodeOrigin("fixture.example")
        val initial = NodeBinding(origin, "device", "space", "dGVzdC10b2tlbg", Long.MAX_VALUE, recovery, recovery)
        val replaced = NodeBinding(origin, "device", "other-space", "dGVzdC10b2tlbg", Long.MAX_VALUE, recovery, recovery)
        val profile = PlatformProfile("test-account", "Fixture Display")
        try {
            store.setNodeBinding(initial)
            store.cacheNodeProfile(initial, "bilibili", profile)
            val cached = JSONObject(storage.getString("profiles", null)!!).getJSONObject("bilibili")
            assertEquals(profile.accountId, cached.getString("account_id"))
            assertEquals(profile.displayName, cached.getString("display_name"))
            assertFalse(store.values.value.toJson().toString().contains(profile.accountId))
            store.setNodeBinding(replaced)
            store.cacheNodeProfile(initial, "bilibili", profile)
            assertNull(storage.getString("profiles", null))
            store.cacheNodeProfile(replaced, "bilibili", profile)
            store.setNodeBinding(null)
            assertNull(storage.getString("profiles", null))
        } finally { store.setNodeBinding(null) }
    }
}
