package hudson.plugins.EndpointS3SCM.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("EndpointS3SCMGlobalConfig — defaults, clamping, normalization and display name")
class EndpointS3SCMGlobalConfigTest {

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private static void invokeReadResolve(Object obj) throws Exception {
        Method method = obj.getClass().getSuperclass().getDeclaredMethod("readResolve");
        method.setAccessible(true);
        method.invoke(obj);
    }

    private EndpointS3SCMGlobalConfig config() {
        return new TestableGlobalConfig();
    }

    @Test
    @DisplayName("getDisplayName: returns 'Endpoint S3 SCM — Global Settings'")
    void getDisplayName_returnsExpectedLabel() {
        assertEquals("Endpoint S3 SCM — Global Settings", config().getDisplayName());
    }

    @Test
    @DisplayName("defaults: maxRetries is 3 after construction")
    void defaults_maxRetries() {
        assertEquals(3, config().getMaxRetries());
    }

    @Test
    @DisplayName("defaults: retryDelayMs is 1000 after construction")
    void defaults_retryDelayMs() {
        assertEquals(1000, config().getRetryDelayMs());
    }

    @Test
    @DisplayName("defaults: stripTopLevelDir is true after construction")
    void defaults_stripTopLevelDir() {
        assertTrue(config().isStripTopLevelDir());
    }

    @Test
    @DisplayName("defaults: skipChecksumVerification is false after construction")
    void defaults_skipChecksumVerification() {
        assertFalse(config().isSkipChecksumVerification());
    }

    @Test
    @DisplayName("defaults: locations list is empty after construction")
    void defaults_locations_empty() {
        assertTrue(config().getLocations().isEmpty());
    }

    @Test
    @DisplayName("setMaxRetries: value below minimum (0) is clamped to 1")
    void setMaxRetries_belowMin_clampedTo1() {
        EndpointS3SCMGlobalConfig c = config();
        c.setMaxRetries(0);
        assertEquals(1, c.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: negative value is clamped to 1")
    void setMaxRetries_negative_clampedTo1() {
        EndpointS3SCMGlobalConfig c = config();
        c.setMaxRetries(-10);
        assertEquals(1, c.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: value above maximum (11) is clamped to 10")
    void setMaxRetries_aboveMax_clampedTo10() {
        EndpointS3SCMGlobalConfig c = config();
        c.setMaxRetries(11);
        assertEquals(10, c.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: boundary value 1 is accepted unchanged")
    void setMaxRetries_minBoundary_accepted() {
        EndpointS3SCMGlobalConfig c = config();
        c.setMaxRetries(1);
        assertEquals(1, c.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: boundary value 10 is accepted unchanged")
    void setMaxRetries_maxBoundary_accepted() {
        EndpointS3SCMGlobalConfig c = config();
        c.setMaxRetries(10);
        assertEquals(10, c.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: value within range (5) is stored unchanged")
    void setMaxRetries_inRange_stored() {
        EndpointS3SCMGlobalConfig c = config();
        c.setMaxRetries(5);
        assertEquals(5, c.getMaxRetries());
    }

    @Test
    @DisplayName("setRetryDelayMs: value below minimum (100) is clamped to 500")
    void setRetryDelayMs_belowMin_clampedTo500() {
        EndpointS3SCMGlobalConfig c = config();
        c.setRetryDelayMs(100);
        assertEquals(500, c.getRetryDelayMs());
    }

    @Test
    @DisplayName("setRetryDelayMs: value above maximum (100000) is clamped to 30000")
    void setRetryDelayMs_aboveMax_clampedTo30000() {
        EndpointS3SCMGlobalConfig c = config();
        c.setRetryDelayMs(100_000);
        assertEquals(30_000, c.getRetryDelayMs());
    }

    @Test
    @DisplayName("setRetryDelayMs: boundary value 500 is accepted unchanged")
    void setRetryDelayMs_minBoundary_accepted() {
        EndpointS3SCMGlobalConfig c = config();
        c.setRetryDelayMs(500);
        assertEquals(500, c.getRetryDelayMs());
    }

    @Test
    @DisplayName("setRetryDelayMs: value within range (2000) is stored unchanged")
    void setRetryDelayMs_inRange_stored() {
        EndpointS3SCMGlobalConfig c = config();
        c.setRetryDelayMs(2000);
        assertEquals(2000, c.getRetryDelayMs());
    }

    @Test
    @DisplayName("setLocations: null input produces empty list")
    void setLocations_null_producesEmptyList() {
        EndpointS3SCMGlobalConfig c = config();
        c.setLocations(null);
        assertTrue(c.getLocations().isEmpty());
    }

    @Test
    @DisplayName("setLocations: null entries in input are filtered out")
    void setLocations_withNulls_nullsFiltered() {
        EndpointS3SCMGlobalConfig c = config();
        List<S3Location> input = new ArrayList<>();
        input.add(location("http://a.com", 10));
        input.add(null);
        input.add(location("http://b.com", 20));
        c.setLocations(input);
        assertEquals(2, c.getLocations().size());
    }

    @Test
    @DisplayName("setLocations: locations are sorted ascending by priority")
    void setLocations_sortedByPriority() {
        EndpointS3SCMGlobalConfig c = config();
        c.setLocations(Arrays.asList(
                location("http://c.com", 30),
                location("http://a.com", 10),
                location("http://b.com", 20)
        ));
        List<S3Location> locs = c.getLocations();
        assertEquals(10, locs.get(0).getPriority());
        assertEquals(20, locs.get(1).getPriority());
        assertEquals(30, locs.get(2).getPriority());
    }

    @Test
    @DisplayName("getLocations: returned list is unmodifiable")
    void getLocations_listIsUnmodifiable() {
        EndpointS3SCMGlobalConfig c = config();
        c.setLocations(List.of(location("http://a.com", 10)));
        List<S3Location> locations = c.getLocations();
        assertThrows(UnsupportedOperationException.class, () -> locations.add(location("http://b.com", 20)));
    }

    @Test
    @DisplayName("setStripTopLevelDir: false is stored and returned correctly")
    void setStripTopLevelDir_false_stored() {
        EndpointS3SCMGlobalConfig c = config();
        c.setStripTopLevelDir(false);
        assertFalse(c.isStripTopLevelDir());
    }

    @Test
    @DisplayName("setSkipChecksumVerification: true is stored and returned correctly")
    void setSkipChecksumVerification_true_stored() {
        EndpointS3SCMGlobalConfig c = config();
        c.setSkipChecksumVerification(true);
        assertTrue(c.isSkipChecksumVerification());
    }

    @Test
    @DisplayName("readResolve: null locations field is replaced with empty list")
    void readResolve_nullLocations_replacedWithEmptyList() throws Exception {
        EndpointS3SCMGlobalConfig c = config();
        setField(c, "locations", null);
        invokeReadResolve(c);
        assertTrue(c.getLocations().isEmpty());
    }

    @Test
    @DisplayName("readResolve: maxRetries below minimum is clamped to 1")
    void readResolve_maxRetriesBelowMin_clamped() throws Exception {
        EndpointS3SCMGlobalConfig c = config();
        setField(c, "maxRetries", 0);
        invokeReadResolve(c);
        assertEquals(1, c.getMaxRetries());
    }

    @Test
    @DisplayName("readResolve: maxRetries above maximum is clamped to 10")
    void readResolve_maxRetriesAboveMax_clamped() throws Exception {
        EndpointS3SCMGlobalConfig c = config();
        setField(c, "maxRetries", 99);
        invokeReadResolve(c);
        assertEquals(10, c.getMaxRetries());
    }

    @Test
    @DisplayName("readResolve: retryDelayMs below minimum is clamped to 500")
    void readResolve_retryDelayMsBelowMin_clamped() throws Exception {
        EndpointS3SCMGlobalConfig c = config();
        setField(c, "retryDelayMs", 0);
        invokeReadResolve(c);
        assertEquals(500, c.getRetryDelayMs());
    }

    @Test
    @DisplayName("readResolve: locations are re-sorted by priority after deserialization")
    void readResolve_locationsSortedAfterDeserialization() throws Exception {
        EndpointS3SCMGlobalConfig c = config();
        List<S3Location> unsorted = new ArrayList<>(Arrays.asList(
                location("http://b.com", 20),
                location("http://a.com", 10)
        ));
        setField(c, "locations", unsorted);
        invokeReadResolve(c);
        assertEquals(10, c.getLocations().get(0).getPriority());
        assertEquals(20, c.getLocations().get(1).getPriority());
    }

    private S3Location location(String endpoint, int priority) {
        S3Location loc = new S3Location();
        loc.setEndpoint(endpoint);
        loc.setBucket("bucket");
        loc.setCredentialsId("creds");
        loc.setPriority(priority);
        return loc;
    }

    private static class TestableGlobalConfig extends EndpointS3SCMGlobalConfig {
        @Override
        public synchronized void load() {
            // no-op: skip Jenkins-root file loading in unit tests
        }

        @Override
        public synchronized void save() {
            // no-op: skip Jenkins-root file writing in unit tests
        }
    }
}
