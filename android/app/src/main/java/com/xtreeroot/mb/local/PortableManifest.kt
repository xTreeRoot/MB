package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import com.xtreeroot.mb.playback.QueueSnapshot
import com.xtreeroot.mb.plugin.PluginRepository
import com.xtreeroot.mb.plugin.pluginZipLimit
import com.xtreeroot.mb.preferences.AppPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class PortableAsset(val name: String, val bytes: Long, val digest: String) {
    fun encode() = JSONObject().put("file", name).put("bytes", bytes).put("sha256", digest)

    companion object {
        val hash = Regex("[a-f0-9]{64}")
        val importedName = Regex("mb-import-[a-f0-9]{64}\\.audio")
        val downloadedName = Regex("mb-saved-[a-f0-9-]{36}\\.[a-z0-9]{2,5}")
        const val maximumBytes = 8L * 1024 * 1024 * 1024

        fun decode(data: JSONObject): PortableAsset = PortableAsset(data.getString("file"), data.getLong("bytes"),
            data.getString("sha256")).also { asset ->
            require(asset.bytes in 1..maximumBytes && hash.matches(asset.digest))
        }
    }
}

internal data class PortableImport(val id: String, val title: String, val asset: PortableAsset, val lyrics: String,
                                   val favorite: Boolean, val lastPlayed: Long, val folderId: String? = null)
internal data class PortableDownload(val track: Track, val asset: PortableAsset)
internal data class PortablePlugin(val id: String, val repository: String?, val asset: PortableAsset,
                                   val settings: Map<String, String>) {
    companion object {
        val fileName = Regex("mb-plugin-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip")
    }
}

internal data class PortableManifest(val imports: List<PortableImport>, val downloads: List<PortableDownload>,
                                     val playlists: List<LocalPlaylist>, val limitMb: Int,
                                     val history: List<String> = emptyList(), val queue: QueueSnapshot? = null,
                                     val preferences: AppPreferences? = null,
                                     val plugins: List<PortablePlugin> = emptyList(),
                                     val activity: List<LocalTrackActivity> = emptyList()) {
    val assets get() = imports.map(PortableImport::asset) + downloads.map(PortableDownload::asset) + plugins.map(PortablePlugin::asset)

    fun encode(): ByteArray = JSONObject().put("version", 1).put("limitMb", limitMb)
        .put("imports", JSONArray(imports.map { item ->
            JSONObject().put("id", item.id).put("title", item.title).put("asset", item.asset.encode())
                .put("lyrics", item.lyrics).put("favorite", item.favorite).put("played", item.lastPlayed).put("folderId", item.folderId)
        })).put("downloads", JSONArray(downloads.map { item ->
            JSONObject().put("track", item.track.toMetadataJson()).put("asset", item.asset.encode())
        })).put("playlists", JSONArray(playlists.map { item ->
            JSONObject().put("id", item.id).put("name", item.name).put("tracks", JSONArray(item.trackIds))
        })).put("history", JSONArray(history)).apply {
            queue?.let { put("queue", JSONObject(it.forPortableStorage().encode())) }
            preferences?.let { put("preferences", it.toJson()) }
            if (plugins.isNotEmpty()) put("plugins", JSONArray(plugins.map { plugin ->
                JSONObject().put("id", plugin.id).put("repository", plugin.repository)
                    .put("asset", plugin.asset.encode()).put("settings", JSONObject(plugin.settings))
            }))
            if (activity.isNotEmpty()) put("activity", JSONArray(activity.sortedBy(LocalTrackActivity::trackId).map { entry ->
                JSONObject().put("id", entry.trackId).put("liked", entry.likedAt).put("played", entry.playedAt)
            }))
        }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val maxManifestBytes = 32 * 1024 * 1024

        fun decode(bytes: ByteArray): PortableManifest {
            require(bytes.size <= maxManifestBytes)
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("version") == 1) { "此备份使用了尚不支持的格式" }
            val imports = root.objects("imports").map { item ->
                val asset = PortableAsset.decode(item.getJSONObject("asset"))
                val id = item.getString("id")
                require(PortableAsset.importedName.matches(asset.name) && asset.name == "mb-import-$id.audio" && asset.digest == id)
                val lyrics = item.getString("lyrics")
                require(lyrics.length <= maximumLyricBytes)
                val folderId = (item.opt("folderId") as? String)?.takeIf(PortableAsset.hash::matches)
                PortableImport(id, item.getString("title"), asset, lyrics, item.getBoolean("favorite"),
                    item.getLong("played").coerceAtLeast(0), folderId)
            }
            val downloads = root.objects("downloads").map { item ->
                val metadata = item.getJSONObject("track").toTrackMetadata()
                val track = metadata.copy(artworkUrl = metadata.artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) })
                require(track.sourceId != localSourceId && track.sourceId.isNotBlank() && track.sourceTrackId.isNotBlank())
                val asset = PortableAsset.decode(item.getJSONObject("asset"))
                require(PortableAsset.downloadedName.matches(asset.name))
                PortableDownload(track, asset)
            }
            val playlists = root.objects("playlists").map { playlist ->
                val ids = playlist.getJSONArray("tracks")
                LocalPlaylist(playlist.getString("id"), playlist.getString("name"), (0 until ids.length()).map(ids::getString).distinct())
            }
            val history = root.optJSONArray("history")?.let { values ->
                (0 until minOf(30, values.length())).map(values::getString).distinct()
            }.orEmpty()
            val queue = root.optJSONObject("queue")?.let { QueueSnapshot.decode(it.toString())?.forPortableStorage() }
            val preferences = root.optJSONObject("preferences")?.let(AppPreferences::parse)
            val plugins = if (root.has("plugins")) root.objects("plugins").map { item ->
                val id = item.getString("id")
                val asset = PortableAsset.decode(item.getJSONObject("asset"))
                require(id.isNotBlank() && PortablePlugin.fileName.matches(asset.name) && asset.bytes <= pluginZipLimit)
                val repository = (item.opt("repository") as? String)?.let(PluginRepository::parse)
                val settings = item.getJSONObject("settings")
                PortablePlugin(id, repository, asset, settings.keys().asSequence().associateWith(settings::getString))
            } else emptyList()
            val activity = if (root.has("activity")) root.objects("activity").map { entry ->
                val id = entry.getString("id")
                require(id.substringBefore(':').isNotBlank() && id.substringAfter(':', "").isNotBlank())
                LocalTrackActivity(id, entry.getLong("liked").coerceAtLeast(0), entry.getLong("played").coerceAtLeast(0))
            } else imports.filter { it.favorite || it.lastPlayed > 0 }.map { item ->
                LocalTrackActivity("$localSourceId:${item.id}", if (item.favorite) 1 else 0, item.lastPlayed)
            }
            val result = PortableManifest(imports, downloads, playlists, root.getInt("limitMb"), history, queue, preferences, plugins, activity)
            require(result.limitMb in OfflineMediaStore.limitsMb)
            requireUnique(imports.map(PortableImport::id))
            requireUnique(downloads.map { it.track.id })
            requireUnique(playlists.map(LocalPlaylist::id))
            requireUnique(plugins.map(PortablePlugin::id))
            requireUnique(activity.map(LocalTrackActivity::trackId))
            requireUnique(result.assets.map(PortableAsset::name))
            return result
        }

        private fun JSONObject.objects(key: String): List<JSONObject> = getJSONArray(key).let { array ->
            (0 until array.length()).map(array::getJSONObject)
        }

        private fun requireUnique(ids: List<String>) { require(ids.size == ids.toSet().size) }
    }
}
