package com.xtreeroot.mb.plugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity

internal class PluginOAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val callback = intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { it.length <= 8192 }
                ?.let { runCatching { uri(it) }.getOrNull() }
            if (callback != null && (application as MBApplication).pluginOAuth.callback(callback)) {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        } finally { finish() }
    }
}
