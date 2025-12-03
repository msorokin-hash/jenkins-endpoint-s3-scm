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
}