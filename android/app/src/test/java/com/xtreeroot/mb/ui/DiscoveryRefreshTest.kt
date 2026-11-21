package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.DiscoverySection
import com.xtreeroot.mb.music.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryRefreshTest {
    @Test fun refreshKeepsOnlyFailedSectionsAndClearsSuccessfullyEmptySections() {
        val previousChart = DiscoverySection(DiscoveryCategory.Chart, listOf(track("chart")), week = 22)
        val previousFresh = DiscoverySection(DiscoveryCategory.Fresh, listOf(track("old-fresh")))
        val previousTrending = DiscoverySection(DiscoveryCategory.Trending, listOf(track("old-trending")))
        val previous = listOf(previousChart, previousFresh, previousTrending)
        val failedChart = DiscoverySection(DiscoveryCategory.Chart, error = "offline")
        val emptyFresh = DiscoverySection(DiscoveryCategory.Fresh)
        val newTrending = DiscoverySection(DiscoveryCategory.Trending, listOf(track("new-trending")))
        val missingRecommendation = DiscoverySection(DiscoveryCategory.Recommended, error = "first load failed")

        val refreshed = listOf(missingRecommendation, failedChart, newTrending, emptyFresh).keepingFailedSections(previous)

        assertEquals(listOf(missingRecommendation, previousChart.copy(error = "offline"), newTrending, emptyFresh), refreshed)
    }

    private fun track(id: String) = Track("bilibili", id, id, "fixture")
}
