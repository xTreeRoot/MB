package com.xtreeroot.mb.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginBrowserRouteTest {
    private val present = Result.success(true)
    private val absent = Result.success(false)
    private val failed = Result.failure<Boolean>(IllegalStateException())

    @Test fun aSupportedDataDirectoryLetsTheSeparateProcessTryEvenWhenProviderDetectionFails() {
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(present, absent, null))
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(present, failed, null))
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(present, present, absent))
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(present, present, failed))
        assertEquals(PluginBrowserRoute.Profile, pluginBrowserRoute(present, present, present))
    }

    @Test fun withoutAnIsolatedDataDirectoryNeverFallsBackToAnUnisolatedWebView() {
        assertEquals(PluginBrowserRoute.Profile, pluginBrowserRoute(absent, present, present))
        assertEquals(PluginBrowserIssue.MissingProvider, pluginBrowserRoute(absent, absent, null).issue)
        assertEquals(PluginBrowserIssue.UnsupportedProvider, pluginBrowserRoute(absent, present, absent).issue)
        assertEquals(PluginBrowserIssue.Startup, pluginBrowserRoute(absent, failed, null).issue)
        assertEquals(PluginBrowserIssue.Startup, pluginBrowserRoute(absent, present, failed).issue)
        assertEquals(PluginBrowserIssue.Startup, pluginBrowserRoute(failed, present, absent).issue)
    }
}
