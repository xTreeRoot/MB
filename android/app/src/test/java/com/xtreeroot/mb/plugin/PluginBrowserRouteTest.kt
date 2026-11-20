package com.xtreeroot.mb.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginBrowserRouteTest {
    private val present = Result.success(true)
    private val absent = Result.success(false)
    private val failed = Result.failure<Boolean>(IllegalStateException())

    @Test fun modernAndroidLetsTheSeparateProcessTryEvenWhenProviderDetectionFails() {
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(true, absent, null))
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(true, failed, null))
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(true, present, absent))
        assertEquals(PluginBrowserRoute.DisposableProcess, pluginBrowserRoute(true, present, failed))
        assertEquals(PluginBrowserRoute.Profile, pluginBrowserRoute(true, present, present))
    }

    @Test fun olderAndroidDoesNotFallBackToAnUnisolatedWebView() {
        assertEquals(PluginBrowserRoute.Profile, pluginBrowserRoute(false, present, present))
        assertEquals(PluginBrowserIssue.MissingProvider, pluginBrowserRoute(false, absent, null).issue)
        assertEquals(PluginBrowserIssue.UnsupportedProvider, pluginBrowserRoute(false, present, absent).issue)
        assertEquals(PluginBrowserIssue.Startup, pluginBrowserRoute(false, failed, null).issue)
        assertEquals(PluginBrowserIssue.Startup, pluginBrowserRoute(false, present, failed).issue)
    }
}
