package com.xtreeroot.mb

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.xtreeroot.mb.plugin.PluginRepository
import com.xtreeroot.mb.ui.MBRoot
import com.xtreeroot.mb.ui.PluginManagerModel

internal class MainActivity : ComponentActivity() {
    private val plugins by viewModels<PluginManagerModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readPluginLink(intent)
        setContent { MBRoot(plugins = plugins) }
    }

    override fun onStart() {
        super.onStart()
        plugins.refreshUpdateNotices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPluginLink(intent)
    }

    private fun readPluginLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val repo = intent.dataString?.let(PluginRepository::fromInstallLink) ?: return
        plugins.openRepository(repo)
    }
}
