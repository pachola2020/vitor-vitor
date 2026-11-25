package com.duascontas.pokeidle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusSyncSettingsTest {
    @Test
    fun acceptsOnlyTheVerifiedStagingOrigin() {
        assertTrue(NexusSyncSettings.isAllowedBaseUrl(
            "https://pokeidle-nexus-staging-pachola2020.vercel.app"
        ))
        assertTrue(NexusSyncSettings.isAllowedBaseUrl(
            "https://pokeidle-nexus-staging-pachola2020.vercel.app/"
        ))
        assertFalse(NexusSyncSettings.isAllowedBaseUrl(
            "http://pokeidle-nexus-staging-pachola2020.vercel.app"
        ))
        assertFalse(NexusSyncSettings.isAllowedBaseUrl(
            "https://example.com"
        ))
        assertFalse(NexusSyncSettings.isAllowedBaseUrl(
            "https://pokeidle-nexus-staging-pachola2020.vercel.app:8443"
        ))
        assertFalse(NexusSyncSettings.isAllowedBaseUrl(
            "https://user@pokeidle-nexus-staging-pachola2020.vercel.app"
        ))
        assertFalse(NexusSyncSettings.isAllowedBaseUrl(
            "https://pokeidle-nexus-staging-pachola2020.vercel.app/other"
        ))
        assertFalse(NexusSyncSettings.isAllowedBaseUrl(
            "https://pokeidle-nexus-staging-pachola2020.vercel.app?redirect=example.com"
        ))
    }
}
