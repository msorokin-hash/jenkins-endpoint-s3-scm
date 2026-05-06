package hudson.plugins.EndpointS3SCM;

import org.junit.Test;

import static org.junit.Assert.*;

public class PluginUtilsTest {

    @Test
    public void testIsEmpty() {
        assertTrue(PluginUtils.isEmpty(null));
        assertTrue(PluginUtils.isEmpty(""));
        assertTrue(PluginUtils.isEmpty("   "));
        assertFalse(PluginUtils.isEmpty("text"));
    }

    @Test
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
    public void testMaskUrl_nullSafety() {
        assertEquals("", PluginUtils.maskUrl(null));
    }

    @Test
    public void testResolveRegion_valid() {
        assertEquals("us-west-1",
                PluginUtils.resolveRegion("us-west-1").id());
    }

    @Test
    public void testResolveRegion_customRegionIsAccepted() {
        assertEquals("invalid-region",
                PluginUtils.resolveRegion("invalid-region").id());
    }

    @Test
    public void testResolveRegion_emptyFallsBackToDefault() {
        assertEquals(
                PluginUtils.DEFAULT_REGION.id(),
                PluginUtils.resolveRegion(null).id()
        );

        assertEquals(
                PluginUtils.DEFAULT_REGION.id(),
                PluginUtils.resolveRegion(" ").id()
        );
    }

    @Test
    public void testResolveRegionId() {
        assertEquals("us-east-1",
                PluginUtils.resolveRegionId(null));

        assertEquals("custom-region-1",
                PluginUtils.resolveRegionId("custom-region-1"));

        assertEquals("eu-central-1",
                PluginUtils.resolveRegionId("eu-central-1"));
    }
}