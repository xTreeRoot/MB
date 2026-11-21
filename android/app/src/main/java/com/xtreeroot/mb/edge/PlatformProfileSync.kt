package com.xtreeroot.mb.edge

import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

internal data class PlatformProfile(val accountId: String, val displayName: String) {
    init {
        require(accountId.encodeToByteArray().size in 1..1024 && displayName.encodeToByteArray().size in 1..512)
    }

    fun payload(): JSONObject = JSONObject().put("value", JSONObject().put("account_id", accountId).put("display_name", displayName))

    companion object {
        fun parse(payload: JSONObject): PlatformProfile = payload.getJSONObject("value").let {
            PlatformProfile(it.get("account_id") as? String ?: throw JSONException("Invalid account ID"),
                it.get("display_name") as? String ?: throw JSONException("Invalid display name"))
        }
    }
}

internal suspend fun NodeClient.syncProfile(binding: NodeBinding, platform: String,
                                            local: PlatformProfile, publish: Boolean,
                                            stillCurrent: () -> Boolean = { true }): PlatformProfile {
    val key = PlatformDataKey(platform, "profile")
    val codec = PlatformDataCodec(binding.recoveryKey, binding.space)
    val revision = when (val record = readProfile(binding, key)) {
        PlatformRecord.Missing -> null
        is PlatformRecord.Deleted -> throw NodeException(NodeFailure.Deleted, 410)
        is PlatformRecord.Present -> {
            val remote = decodeProfile(codec, key, record.envelope)
            if (remote.accountId != local.accountId) throw NodeException(NodeFailure.OtherAccount)
            if (!publish || remote == local) return remote
            record.tag
        }
    }
    val envelope = codec.seal(key, 1, local.payload())
    currentCoroutineContext().ensureActive()
    if (!stillCurrent()) throw CancellationException()
    try {
        if (revision == null) create(binding, key, envelope) else replace(binding, key, revision, envelope)
    } catch (failure: NodeException) {
        if (failure.failure != NodeFailure.Retryable && failure.failure != NodeFailure.Conflict) throw failure
        val observed = try { readProfile(binding, key) } catch (_: NodeException) { throw failure }
        if (observed !is PlatformRecord.Present || observed.envelope != envelope) throw failure
    }
    return local
}

private suspend fun NodeClient.readProfile(binding: NodeBinding, key: PlatformDataKey): PlatformRecord {
    repeat(2) { attempt ->
        try { return read(binding, key) }
        catch (failure: NodeException) {
            if (failure.failure != NodeFailure.Retryable) throw failure
            delay(250L * (attempt + 1))
        }
    }
    return read(binding, key)
}

private fun decodeProfile(codec: PlatformDataCodec, key: PlatformDataKey, envelope: PlatformEnvelope): PlatformProfile = try {
    PlatformProfile.parse(codec.open(key, 1, envelope))
} catch (_: GeneralSecurityException) {
    throw NodeException(NodeFailure.Protocol)
} catch (_: IllegalArgumentException) {
    throw NodeException(NodeFailure.Protocol)
} catch (_: JSONException) {
    throw NodeException(NodeFailure.Protocol)
} catch (_: java.nio.charset.CharacterCodingException) {
    throw NodeException(NodeFailure.Protocol)
}
