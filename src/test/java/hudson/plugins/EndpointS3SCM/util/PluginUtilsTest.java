package hudson.plugins.EndpointS3SCM.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.DEFAULT_REGION;
import static org.junit.Assert.*;

@DisplayName("PluginUtils")
public class PluginUtilsTest {

    @Test
    @DisplayName("isEmpty: null, empty string, and whitespace-only return true; non-blank string returns false")
    public void testIsEmpty() {
        assertTrue(PluginUtils.isEmpty(null));
        assertTrue(PluginUtils.isEmpty(""));
        assertTrue(PluginUtils.isEmpty("   "));
        assertFalse(PluginUtils.isEmpty("text"));
    }

    @Test
    @DisplayName("maskUrl: credentials embedded in URL are replaced with '****'")
    public void testMaskUrl() {
        assertEquals("https://example.com",
                PluginUtils.maskUrl("https://example.com"));

        assertEquals("https://****@example.com",
                PluginUtils.maskUrl("https://user:pass@example.com"));

        assertEquals("https://****@example.com",
                PluginUtils.maskUrl("https://user@example.com"));

        assertEquals("bad url",
                PluginUtils.maskUrl("bad url"));
    }

    @Test
    @DisplayName("maskUrl: null input returns empty string")
    public void testMaskUrl_nullSafety() {
        assertEquals("", PluginUtils.maskUrl(null));
    }

    @Test
    @DisplayName("resolveRegion: valid AWS region ID is resolved to the correct Region object")
    public void testResolveRegion_valid() {
        assertEquals("us-west-1",
                PluginUtils.resolveRegion("us-west-1").id());
    }

    @Test
    @DisplayName("resolveRegion: custom or unknown region ID is accepted and returned as-is")
    public void testResolveRegion_customRegionIsAccepted() {
        assertEquals("invalid-region",
                PluginUtils.resolveRegion("invalid-region").id());
    }

    @Test
    @DisplayName("resolveRegion: null or blank input falls back to the default region (us-east-1)")
    public void testResolveRegion_emptyFallsBackToDefault() {
        assertEquals(
                DEFAULT_REGION.id(),
                PluginUtils.resolveRegion(null).id()
        );

        assertEquals(
                DEFAULT_REGION.id(),
                PluginUtils.resolveRegion(" ").id()
        );
    }

    @Test
    @DisplayName("resolveRegionId: returns the region string unchanged, or 'us-east-1' for null/blank input")
    public void testResolveRegionId() {
        assertEquals("us-east-1",
                PluginUtils.resolveRegionId(null));

        assertEquals("custom-region-1",
                PluginUtils.resolveRegionId("custom-region-1"));

        assertEquals("eu-central-1",
                PluginUtils.resolveRegionId("eu-central-1"));
    }
}
