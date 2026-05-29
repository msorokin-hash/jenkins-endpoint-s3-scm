package hudson.plugins.EndpointS3SCM.config;

import hudson.model.Job;
import hudson.model.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("EndpointS3SCMFolderProperty — defaults, clamping, normalization and ancestor lookup null-guards")
class EndpointS3SCMFolderPropertyTest {

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private static void invokeReadResolve(Object obj) throws Exception {
        Method method = obj.getClass().getDeclaredMethod("readResolve");
        method.setAccessible(true);
        method.invoke(obj);
    }

    @Test
    @DisplayName("defaults: maxRetries is 3 after construction")
    void defaults_maxRetries() {
        assertEquals(3, new EndpointS3SCMFolderProperty().getMaxRetries());
    }

    @Test
    @DisplayName("defaults: retryDelayMs is 1000 after construction")
    void defaults_retryDelayMs() {
        assertEquals(1000, new EndpointS3SCMFolderProperty().getRetryDelayMs());
    }

    @Test
    @DisplayName("defaults: stripTopLevelDir is true after construction")
    void defaults_stripTopLevelDir() {
        assertTrue(new EndpointS3SCMFolderProperty().isStripTopLevelDir());
    }

    @Test
    @DisplayName("defaults: skipChecksumVerification is false after construction")
    void defaults_skipChecksumVerification() {
        assertFalse(new EndpointS3SCMFolderProperty().isSkipChecksumVerification());
    }

    @Test
    @DisplayName("defaults: locations list is empty after construction")
    void defaults_locations_empty() {
        assertTrue(new EndpointS3SCMFolderProperty().getLocations().isEmpty());
    }

    @Test
    @DisplayName("setMaxRetries: value below minimum (0) is clamped to 1")
    void setMaxRetries_belowMin_clampedTo1() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setMaxRetries(0);
        assertEquals(1, prop.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: negative value is clamped to 1")
    void setMaxRetries_negative_clampedTo1() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setMaxRetries(-5);
        assertEquals(1, prop.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: value above maximum (11) is clamped to 10")
    void setMaxRetries_aboveMax_clampedTo10() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setMaxRetries(11);
        assertEquals(10, prop.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: boundary value 1 is accepted unchanged")
    void setMaxRetries_minBoundary_accepted() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setMaxRetries(1);
        assertEquals(1, prop.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: boundary value 10 is accepted unchanged")
    void setMaxRetries_maxBoundary_accepted() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setMaxRetries(10);
        assertEquals(10, prop.getMaxRetries());
    }

    @Test
    @DisplayName("setMaxRetries: value within range (5) is stored unchanged")
    void setMaxRetries_inRange_stored() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setMaxRetries(5);
        assertEquals(5, prop.getMaxRetries());
    }

    @Test
    @DisplayName("setRetryDelayMs: value below minimum (100) is clamped to 500")
    void setRetryDelayMs_belowMin_clampedTo500() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setRetryDelayMs(100);
        assertEquals(500, prop.getRetryDelayMs());
    }

    @Test
    @DisplayName("setRetryDelayMs: value above maximum (100000) is clamped to 30000")
    void setRetryDelayMs_aboveMax_clampedTo30000() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setRetryDelayMs(100_000);
        assertEquals(30_000, prop.getRetryDelayMs());
    }

    @Test
    @DisplayName("setRetryDelayMs: boundary value 500 is accepted unchanged")
    void setRetryDelayMs_minBoundary_accepted() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setRetryDelayMs(500);
        assertEquals(500, prop.getRetryDelayMs());
    }

    @Test
    @DisplayName("setRetryDelayMs: value within range (2000) is stored unchanged")
    void setRetryDelayMs_inRange_stored() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setRetryDelayMs(2000);
        assertEquals(2000, prop.getRetryDelayMs());
    }

    @Test
    @DisplayName("setStripTopLevelDir: false is stored and returned correctly")
    void setStripTopLevelDir_false_stored() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setStripTopLevelDir(false);
        assertFalse(prop.isStripTopLevelDir());
    }

    @Test
    @DisplayName("setSkipChecksumVerification: true is stored and returned correctly")
    void setSkipChecksumVerification_true_stored() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setSkipChecksumVerification(true);
        assertTrue(prop.isSkipChecksumVerification());
    }

    @Test
    @DisplayName("setLocations: null input produces empty list")
    void setLocations_null_producesEmptyList() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setLocations(null);
        assertTrue(prop.getLocations().isEmpty());
    }

    @Test
    @DisplayName("setLocations: null entries in input are filtered out")
    void setLocations_withNulls_nullsFiltered() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        List<S3Location> input = new ArrayList<>();
        input.add(location("http://a.com", 10));
        input.add(null);
        input.add(location("http://b.com", 20));
        prop.setLocations(input);
        assertEquals(2, prop.getLocations().size());
    }

    @Test
    @DisplayName("setLocations: locations are sorted ascending by priority")
    void setLocations_sortedByPriority() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setLocations(Arrays.asList(
                location("http://c.com", 30),
                location("http://a.com", 10),
                location("http://b.com", 20)
        ));
        List<S3Location> locs = prop.getLocations();
        assertEquals(10, locs.get(0).getPriority());
        assertEquals(20, locs.get(1).getPriority());
        assertEquals(30, locs.get(2).getPriority());
    }

    @Test
    @DisplayName("getLocations: returned list is unmodifiable")
    void getLocations_listIsUnmodifiable() {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        prop.setLocations(List.of(location("http://a.com", 10)));
        List<S3Location> locations = prop.getLocations();
        assertThrows(UnsupportedOperationException.class, () -> locations.add(location("http://b.com", 20)));
    }

    @Test
    @DisplayName("readResolve: null locations field is replaced with empty list")
    void readResolve_nullLocations_replacedWithEmptyList() throws Exception {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        setField(prop, "locations", null);
        invokeReadResolve(prop);
        assertTrue(prop.getLocations().isEmpty());
    }

    @Test
    @DisplayName("readResolve: maxRetries below minimum is clamped to 1")
    void readResolve_maxRetriesBelowMin_clamped() throws Exception {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        setField(prop, "maxRetries", 0);
        invokeReadResolve(prop);
        assertEquals(1, prop.getMaxRetries());
    }

    @Test
    @DisplayName("readResolve: maxRetries above maximum is clamped to 10")
    void readResolve_maxRetriesAboveMax_clamped() throws Exception {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        setField(prop, "maxRetries", 99);
        invokeReadResolve(prop);
        assertEquals(10, prop.getMaxRetries());
    }

    @Test
    @DisplayName("readResolve: retryDelayMs below minimum is clamped to 500")
    void readResolve_retryDelayMsBelowMin_clamped() throws Exception {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        setField(prop, "retryDelayMs", 0);
        invokeReadResolve(prop);
        assertEquals(500, prop.getRetryDelayMs());
    }

    @Test
    @DisplayName("readResolve: locations are re-sorted by priority after deserialization")
    void readResolve_locationsSortedAfterDeserialization() throws Exception {
        EndpointS3SCMFolderProperty prop = new EndpointS3SCMFolderProperty();
        List<S3Location> unsorted = new ArrayList<>(Arrays.asList(
                location("http://b.com", 20),
                location("http://a.com", 10)
        ));
        setField(prop, "locations", unsorted);
        invokeReadResolve(prop);
        assertEquals(10, prop.getLocations().get(0).getPriority());
        assertEquals(20, prop.getLocations().get(1).getPriority());
    }

    @Test
    @DisplayName("findNearest(Run): null run returns null without traversal")
    void findNearest_nullRun_returnsNull() {
        assertNull(EndpointS3SCMFolderProperty.findNearest((Run<?, ?>) null));
    }

    @Test
    @DisplayName("findNearest(Job): null job returns null without traversal")
    void findNearest_nullJob_returnsNull() {
        assertNull(EndpointS3SCMFolderProperty.findNearest((Job<?, ?>) null));
    }

    private S3Location location(String endpoint, int priority) {
        S3Location loc = new S3Location();
        loc.setEndpoint(endpoint);
        loc.setBucket("bucket");
        loc.setCredentialsId("creds");
        loc.setPriority(priority);
        return loc;
    }
}
