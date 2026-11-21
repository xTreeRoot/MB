package com.xtreeroot.mb.local

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal data class PortableCheckpoint(val name: String, val manifest: PortableManifest, val skippedUnreadable: Boolean) {
    val stamp get() = name.removePrefix("mb-state-").substringBefore('-').toLong()
}

internal fun latestPortableCheckpoint(sizes: Map<String, Long>, open: (String) -> InputStream): PortableCheckpoint? {
    val snapshots = sizes.keys.filter(PortableManifest.snapshotName::matches).sortedDescending()
    var skippedUnreadable = false
    for (name in snapshots) {
        if (sizes.getValue(name) > PortableManifest.maxManifestBytes) continue
        val bytes = try { open(name).use(::readPortableIndex) } catch (_: IOException) {
            skippedUnreadable = true
            continue
        } ?: continue
        val manifest = runCatching { PortableManifest.decode(bytes) }.getOrNull() ?: continue
        return PortableCheckpoint(name, manifest, skippedUnreadable)
    }
    if (snapshots.isNotEmpty()) throw PortableFailure(PortableProblem.Read)
    return null
}

internal fun portableIndexMatches(input: InputStream, expected: ByteArray): Boolean {
    val bytes = readPortableIndex(input) ?: return false
    return bytes.contentEquals(expected) && runCatching { PortableManifest.decode(bytes) }.isSuccess
}

private fun readPortableIndex(input: InputStream): ByteArray? {
    val buffer = ByteArray(16 * 1024)
    val contents = ByteArrayOutputStream()
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return contents.toByteArray()
        if (contents.size() + count > PortableManifest.maxManifestBytes) return null
        contents.write(buffer, 0, count)
    }
}
