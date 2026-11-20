package com.xtreeroot.mb

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.plugin.PluginRepository
import com.xtreeroot.mb.ui.MBRoot
import com.xtreeroot.mb.ui.PluginManagerModel

internal class MainActivity : ComponentActivity() {
    private val plugins by viewModels<PluginManagerModel>()
    private val playback by viewModels<PlaybackModel>()
    private var pendingVoiceSearch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIncomingIntent(intent)
        setContent { MBRoot(player = playback, plugins = plugins) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIncomingIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) playIncomingSearch()
    }

    private fun readIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(PluginRepository::fromInstallLink)?.let(plugins::openRepository)
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                pendingVoiceSearch = intent.getStringExtra(SearchManager.QUERY).orEmpty().take(256)
                if (hasWindowFocus()) playIncomingSearch()
            }
        }
    }

    private fun playIncomingSearch() {
        val query = pendingVoiceSearch ?: return
        pendingVoiceSearch = null
        playback.playFromSearch(query)
    }
}
