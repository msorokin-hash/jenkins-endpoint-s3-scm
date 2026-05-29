package hudson.plugins.EndpointS3SCM.checkout;

import hudson.EnvVars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;
import static org.junit.Assert.*;

@DisplayName("S3CheckoutAction — env vars and display helpers")
public class S3CheckoutActionTest {

    private static final Instant MODIFIED = Instant.parse("2025-06-01T12:00:00Z");

    private S3CheckoutAction action(String key, String bucket, String source,
                                    Instant lastModified, long size, String mode) {
        return new S3CheckoutAction(key, bucket, source, lastModified, size, mode);
    }

    @Test
    @DisplayName("buildEnvironment: all fields populate correct env vars")
    public void buildEnvironment_populatesAllVars() {
        S3CheckoutAction a = action(
                "releases/app-1.0.zip", "my-bucket", "primary", MODIFIED, 4096L, "LATEST");

        EnvVars env = new EnvVars();
        a.buildEnvironment(null, env);

        assertEquals("releases/app-1.0.zip", env.get(ENV_KEY));
        assertEquals("my-bucket", env.get(ENV_BUCKET));
        assertEquals("primary", env.get(ENV_SOURCE));
        assertEquals(MODIFIED.toString(), env.get(ENV_LAST_MODIFIED));
        assertEquals("4096", env.get(ENV_SIZE));
        assertEquals("LATEST", env.get(ENV_MODE));
    }

    @Test
    @DisplayName("buildEnvironment: EXACT mode stored correctly")
    public void buildEnvironment_exactMode() {
        S3CheckoutAction a = action(
                "releases/app-1.0.zip", "bucket", "src", MODIFIED, 512L, "EXACT");

        EnvVars env = new EnvVars();
        a.buildEnvironment(null, env);

        assertEquals("EXACT", env.get(ENV_MODE));
    }

    @Test
    @DisplayName("buildEnvironment: null lastModified — ENDPOINT_S3_LAST_MODIFIED not set")
    public void buildEnvironment_nullLastModified_skipsVar() {
        S3CheckoutAction a = action("k", "b", "s", null, 0L, "LATEST");

        EnvVars env = new EnvVars();
        a.buildEnvironment(null, env);

        assertNull(env.get(ENV_LAST_MODIFIED));
        assertEquals("0", env.get(ENV_SIZE));
    }

    @Test
    @DisplayName("buildEnvVars: delegates to buildEnvironment (AbstractBuild compat)")
    public void buildEnvVars_delegatesToBuildEnvironment() {
        S3CheckoutAction a = action("k", "b", "s", MODIFIED, 100L, "LATEST");

        EnvVars env = new EnvVars();
        a.buildEnvVars(null, env);

        assertEquals("k", env.get(ENV_KEY));
        assertEquals("b", env.get(ENV_BUCKET));
    }

    @Test
    @DisplayName("getLastModified: converts epoch millis back to Instant")
    public void getLastModified_roundTrip() {
        S3CheckoutAction a = action("k", "b", "s", MODIFIED, 0L, "LATEST");
        assertEquals(MODIFIED, a.getLastModified());
    }

    @Test
    @DisplayName("getLastModified: null when lastModified was null")
    public void getLastModified_nullWhenUnknown() {
        S3CheckoutAction a = action("k", "b", "s", null, 0L, "LATEST");
        assertNull(a.getLastModified());
    }

    @Test
    @DisplayName("getLastModifiedForDisplay: ISO-8601 string when timestamp is known")
    public void getLastModifiedForDisplay_knownTimestamp() {
        S3CheckoutAction a = action("k", "b", "s", MODIFIED, 0L, "LATEST");
        assertEquals("2025-06-01T12:00:00Z", a.getLastModifiedForDisplay());
    }

    @Test
    @DisplayName("getLastModifiedForDisplay: dash when timestamp is unknown")
    public void getLastModifiedForDisplay_unknownTimestamp() {
        S3CheckoutAction a = action("k", "b", "s", null, 0L, "LATEST");
        assertEquals("—", a.getLastModifiedForDisplay());
    }

    @Test
    @DisplayName("getFormattedSize: bytes under 1 KB")
    public void getFormattedSize_bytes() {
        assertEquals("512 B", action("k", "b", "s", null, 512L, "LATEST").getFormattedSize());
        assertEquals("1023 B", action("k", "b", "s", null, 1023L, "LATEST").getFormattedSize());
    }

    @Test
    @DisplayName("getFormattedSize: kilobytes in 1 KB–1 MB range")
    public void getFormattedSize_kilobytes() {
        // 1024 bytes = 1.0 KB
        assertEquals("1.0 KB", action("k", "b", "s", null, 1024L, "LATEST").getFormattedSize());
        assertEquals("10.0 KB", action("k", "b", "s", null, 10 * 1024L, "LATEST").getFormattedSize());
    }

    @Test
    @DisplayName("getFormattedSize: megabytes above 1 MB")
    public void getFormattedSize_megabytes() {
        // 1 MB = 1024*1024 = 1048576 bytes
        assertEquals("1.0 MB", action("k", "b", "s", null, 1024L * 1024, "LATEST").getFormattedSize());
        assertEquals("5.0 MB", action("k", "b", "s", null, 5L * 1024 * 1024, "LATEST").getFormattedSize());
    }

    @Test
    @DisplayName("S3SCMRevisionState from action — matches identical state")
    public void revisionState_matchesIdentical() {
        S3CheckoutAction a = action("releases/app.zip", "bucket", "src", MODIFIED, 100L, "EXACT");

        S3SCMRevisionState state = new S3SCMRevisionState(
                a.getBucket(), a.getKey(), a.getLastModifiedMs());

        assertTrue(state.matches(new S3SCMRevisionState(
                "bucket", "releases/app.zip", MODIFIED.toEpochMilli())));
    }

    @Test
    @DisplayName("S3SCMRevisionState — does not match when key differs")
    public void revisionState_noMatchOnDifferentKey() {
        S3SCMRevisionState s1 = new S3SCMRevisionState("bucket", "releases/app-1.zip", 1000L);
        S3SCMRevisionState s2 = new S3SCMRevisionState("bucket", "releases/app-2.zip", 1000L);
        assertFalse(s1.matches(s2));
    }

    @Test
    @DisplayName("S3SCMRevisionState — does not match when lastModified differs")
    public void revisionState_noMatchOnDifferentTimestamp() {
        S3SCMRevisionState s1 = new S3SCMRevisionState("bucket", "releases/app.zip", 1000L);
        S3SCMRevisionState s2 = new S3SCMRevisionState("bucket", "releases/app.zip", 2000L);
        assertFalse(s1.matches(s2));
    }
}
