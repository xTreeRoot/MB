package com.xtreeroot.mb.plugin

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginInstallerTest {
    @Test fun starsImportSkipsOnlyTheExactTemplateAndLeavesAnExistingInstallationAlone() = fixture { root ->
        val installer = PluginInstaller(root)
        val template = "xTreeRoot/mb-plugin-template"
        val existing = installer.install(installer.prepareArchive(template) { zip(it, files("existing-template")) })
        val downloaded = mutableListOf<String>()
        val batch = GitHubPluginImport(installer, { repository, archive ->
            downloaded += repository
            zip(archive, files(if (repository.startsWith("xTreeRoot/")) "similar" else "other-owner"))
        }, "bilibili")
        val sameName = "other/mb-plugin-template"
        val similarName = "xTreeRoot/mb-plugin-template-player"
        val mixedCaseTemplate = "XtReErOoT/MB-PLUGIN-TEMPLATE"
        val address = checkNotNull(GitHubStarsList.address("https://github.com/stars/example/lists/plugins"))
        val rows = listOf(mixedCaseTemplate, sameName, similarName).joinToString("") { repo ->
            """<h2 class="h3"><a href="/$repo">Same display name</a></h2>"""
        }
        val group = GitHubStarsList.read("""<div id="user-list-repositories">$rows</div>""", address)
        val report = batch.run(group.repositories) { candidates ->
            assertEquals(listOf(sameName, similarName), candidates.map { it.repository })
            true
        }
        assertEquals(listOf(mixedCaseTemplate), report.skipped)
        assertEquals(listOf(sameName, similarName), downloaded)
        assertEquals(2, report.installed.size)
        assertTrue(report.failures.isEmpty())
        assertEquals(existing.revision, installer.installed().single { it.id == existing.id }.revision)
    }

    @Test fun githubImportConfirmsOnceAndOverwritesOnlyValidatedPluginCode() = fixture { root ->
        val installer = PluginInstaller(root)
        val old = installer.install(installer.prepareArchive("old/project") { zip(it, files("same")) })
        installer.setEnabled(old, false)
        installer.saveSettings(old, mapOf("quality" to "lossless"))
        val session = old.dataDirectory.resolve("storage.json")
        session.writeText("synthetic-account")
        val downloaded = mutableListOf<String>()
        val batch = GitHubPluginImport(installer, { repository, archive ->
            downloaded += repository
            when (repository) {
                "example/bad" -> zip(archive, listOf("README.md" to "Not a plugin"))
                "example/overwrite", "example/duplicate" -> zip(archive, files("same", "1.0.0"))
                "example/reserved" -> zip(archive, files("bilibili"))
                else -> zip(archive, files("new"))
            }
        }, "bilibili")
        val repositories = listOf("xtreeroot/mb", "example/overwrite", "example/bad", "example/reserved", "example/duplicate", "example/last")
        var reviews = 0
        val report = batch.run(repositories) { candidates ->
            reviews++
            assertEquals(listOf("same", "new"), candidates.map { it.manifest.id })
            assertEquals(old.revision, installer.installed().single().revision)
            true
        }
        assertEquals(1, reviews)
        assertEquals(repositories.drop(1), downloaded)
        assertEquals(listOf("xtreeroot/mb"), report.skipped)
        assertEquals(listOf("example/bad", "example/reserved", "example/duplicate"), report.failures.map { it.pluginName })
        assertEquals(2, report.installed.size)
        val installed = installer.installed().associateBy { it.id }
        assertEquals(setOf("same", "new"), installed.keys)
        val overwritten = installed.getValue("same")
        assertFalse(old.revision == overwritten.revision)
        assertEquals("example/overwrite", overwritten.repository)
        assertFalse(overwritten.enabled)
        assertEquals(mapOf("quality" to "lossless"), installer.settings(overwritten))
        assertEquals("synthetic-account", session.readText())
        val cancelled = batch.run(listOf("example/overwrite")) { false }
        assertEquals(listOf("example/overwrite"), cancelled.skipped)
        assertEquals(overwritten.revision, installer.installed().single { it.id == old.id }.revision)
        assertTrue(File(root, "pending").list().orEmpty().isEmpty())
    }

    @Test fun batchUpdatesSkipZipSourcesAndIsolateIndividualFailuresAndPermissionReview() = fixture { root ->
        val installer = PluginInstaller(root)
        val ids = listOf("old", "check-fails", "no-release", "zip-only", "disabled", "skip", "download-fails", "approve", "stale", "last")
        for (id in ids) installer.install(installer.prepareArchive(if (id == "zip-only") null else "fixture/$id") {
            zip(it, files(id))
        })
        val disabled = installer.installed().single { it.id == "disabled" }
        installer.setEnabled(disabled, false)
        installer.saveSettings(disabled, mapOf("quality" to "lossless"))
        val session = disabled.dataDirectory.resolve("storage.json")
        session.writeText("fixture-session")
        val checked = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        val updates = PluginUpdates(installer, { repo ->
            val id = repo.substringAfter('/')
            checked += id
            if (id == "check-fails") throw PluginException("Cannot check release")
            if (id == "no-release") null else GitHubPluginRelease(if (id == "old") "v1.0.0" else "v2.0.0",
                URI("https://github.com/fixture/$id"))
        }, { release, file ->
            val id = release.archive.path.substringAfterLast('/')
            downloaded += id
            if (id == "download-fails") throw PluginException("Cannot download release")
            val version = if (id == "stale") "0.9.0" else "2.0.0"
            val entries = files(id, version).map { (name, content) ->
                if (name != "plugin.json" || id !in setOf("skip", "approve")) name to content
                else name to JSONObject(content).put("permissions", JSONObject().put("network", listOf("fixture.test"))).toString()
            }
            zip(file, entries)
        })
        val report = updates.check(installer.installed().sortedBy { ids.indexOf(it.id) })
        assertEquals(ids - "zip-only", checked)
        assertTrue(downloaded.isEmpty())
        assertEquals(listOf("disabled", "skip", "download-fails", "approve", "stale", "last"), report.available.map { it.plugin.id })
        assertEquals(listOf("Cannot check release"), report.failures.map { it.detail })
        val reviewed = mutableListOf<String>()
        val result = updates.apply(report) { candidate ->
            val id = candidate.manifest.id
            reviewed += id
            assertEquals("1.0.0", installer.installed().single { it.id == id }.manifest.version)
            id == "approve"
        }
        assertEquals(listOf("skip", "approve"), reviewed)
        assertEquals(report.available.map { it.plugin.id }, downloaded)
        assertEquals(3, result.installed.size)
        assertEquals(1, result.skipped.size)
        assertEquals(listOf("Cannot check release", "Cannot download release", "Downloaded plugin does not have a newer manifest version"),
            result.failures.map { it.detail })
        val installed = installer.installed().associateBy(InstalledPlugin::id)
        assertEquals(setOf("disabled", "approve", "last"), installed.values.filter { it.manifest.version == "2.0.0" }.map { it.id }.toSet())
        val replaced = installed.getValue("disabled")
        assertFalse(replaced.enabled)
        assertEquals("fixture/disabled", replaced.repository)
        assertEquals(mapOf("quality" to "lossless"), installer.settings(replaced))
        assertEquals("fixture-session", session.readText())
        assertTrue(root.resolve("pending").list().orEmpty().isEmpty())
    }

    @Test fun installUpdateAndRemovalPreserveCodeAndSettingsInSeparateDirectories() = fixture { root ->
        val installer = PluginInstaller(root)
        val first = installer.prepareArchive(download = { zip(it, files(version = "1.0.0")) })
        val installed = installer.install(first)
        assertEquals("1.0.0", installed.manifest.version)
        assertFalse(installed.directory.toPath().startsWith(installed.dataDirectory.toPath()))
        installer.saveSettings(installed, mapOf("quality" to "lossless", "ignored" to "not persisted"))
        installer.setEnabled(installed, false)

        val update = installer.prepareArchive("octocat/player-plugin", installed) { zip(it, files(version = "2.0.0")) }
        val replaced = installer.install(update)
        assertEquals("2.0.0", replaced.manifest.version)
        assertEquals(installed.dataDirectory, replaced.dataDirectory)
        assertEquals(mapOf("quality" to "lossless"), installer.settings(replaced))
        assertFalse(replaced.enabled)
        assertFalse(installed.directory.exists())
        assertEquals(listOf("fixture"), installer.installed().map(InstalledPlugin::id))

        installer.remove(replaced)
        assertTrue(installer.installed().isEmpty())
        assertTrue(replaced.dataDirectory.resolve("settings.json").isFile)
    }

    @Test fun pendingReviewDetectsTamperingAndReplacementRaces() = fixture { root ->
        val installer = PluginInstaller(root)
        val original = installer.install(installer.prepareArchive(download = { zip(it, files(version = "1.0.0")) }))
        val changed = installer.prepareArchive("octocat/player-plugin", original) { zip(it, files(version = "2.0.0")) }
        File(changed.directory, "plugin.json").writeText(manifest("3.0.0"))
        rejected { installer.install(changed) }
        assertEquals("1.0.0", installer.installed().single().manifest.version)

        val stale = installer.prepareArchive("octocat/player-plugin", original) { zip(it, files(version = "2.0.0")) }
        val current = installer.prepareArchive("octocat/player-plugin", original) { zip(it, files(version = "2.1.0")) }
        installer.install(current)
        rejected { installer.install(stale) }
        assertEquals("2.1.0", installer.installed().single().manifest.version)
    }

    @Test fun archiveRejectsTraversalDuplicateEntriesAndUnmanifestedFilesStayUninstalled() = fixture { root ->
        val installer = PluginInstaller(root)
        rejected { installer.prepareArchive(download = { zip(it, listOf("../outside.js" to "outside")) }) }
        assertFalse(File(root.parentFile, "outside.js").exists())
        rejected { installer.prepareArchive(download = { zip(it, listOf("index.js" to "one", "index.js" to "two")) }) }
        assertTrue(installer.installed().isEmpty())
    }

    @Test fun reservedIdsAndMalformedDirectoryEntriesAreRejected() = fixture { root ->
        val installer = PluginInstaller(root)
        rejected { installer.prepareArchive(download = { zip(it, files(id = "local")) }) }
        rejected { installer.prepareArchive(download = { zip(it, files() + ("extra/../escape.js" to "escape")) }) }
        assertTrue(installer.installed().isEmpty())
    }

    @Test fun backupRestoresCodeDisabledWithoutOverwritingExistingPluginsOrExposingPrivateSettings() = fixture { root ->
        val original = PluginInstaller(File(root, "original"))
        val archive = File(root, "portable.zip")
        val records = files().map { (name, contents) ->
            if (name != "plugin.json") name to contents else name to """{"id":"fixture","settings":[
              {"key":"enabledFeature","label":"Feature","type":"boolean"},
              {"key":"quality","label":"Quality","type":"select","options":[{"value":"lossless","label":"Lossless"}]},
              {"key":"token","label":"Token","type":"password"},
              {"key":"server","label":"Server"}]}"""
        }
        val installed = original.install(original.prepareArchive("owner/repository") { zip(it, records) })
        original.saveSettings(installed, mapOf("enabledFeature" to "true", "quality" to "lossless", "token" to "private", "server" to "https://private/"))
        installed.dataDirectory.resolve("storage.json").writeText("""{"session":"private-storage"}""")
        original.archiveForBackup(installed, archive)
        val settings = installed.manifest.backupSettings(original.settings(installed))
        assertEquals(mapOf("enabledFeature" to "true", "quality" to "lossless"), settings)
        assertTrue(File(root, "original").deleteRecursively())

        val fresh = PluginInstaller(File(root, "reinstalled"))
        rejected { fresh.restoreArchive("wrong-id", installed.repository, archive, settings) }
        fresh.restoreArchive("fixture", installed.repository, archive, settings + ("token" to "forged"))
        val restored = fresh.installed().single()
        assertFalse(restored.enabled)
        assertEquals("owner/repository", restored.repository)
        assertEquals(settings, fresh.settings(restored))
        assertEquals(records.toMap().getValue("helper.js"), restored.directory.resolve("helper.js").readText())
        assertFalse(restored.dataDirectory.resolve("storage.json").exists())
        assertFalse(restored.directory.resolve("storage.json").exists())

        fresh.setEnabled(restored, true)
        fresh.saveSettings(restored, mapOf("quality" to "lossless", "token" to "new session"))
        fresh.restoreArchive("fixture", installed.repository, archive, settings)
        assertTrue(fresh.installed().single().enabled)
        assertEquals(mapOf("quality" to "lossless", "token" to "new session"), fresh.settings(restored))
    }

    private fun fixture(run: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("mb-plugin-installer-").toFile()
        try { run(root) } finally { root.deleteRecursively() }
    }

    private suspend fun rejected(action: suspend () -> Any?) {
        try { action(); fail("Invalid plugin package should be rejected") }
        catch (error: PluginException) { assertTrue(error.message.orEmpty().isNotBlank()) }
    }

    private fun files(id: String = "fixture", version: String = "1.0.0") = listOf(
        "plugin.json" to manifest(version, id),
        "index.js" to "export function search(){return []}",
        "helper.js" to "export const release = '$version'",
    )

    private fun manifest(version: String, id: String = "fixture") = JSONObject()
        .put("id", id).put("name", "Fixture").put("version", version)
        .put("settings", listOf(JSONObject().put("key", "quality").put("label", "Quality").put("type", "select")))
        .toString()

    private fun zip(file: File, entries: List<Pair<String, String>>) {
        ZipArchiveOutputStream(file).use { output ->
            output.setLevel(Deflater.BEST_COMPRESSION)
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                val entry = ZipArchiveEntry(name).apply { size = bytes.size.toLong() }
                output.putArchiveEntry(entry)
                output.write(bytes)
                output.closeArchiveEntry()
            }
            output.finish()
        }
    }
}
