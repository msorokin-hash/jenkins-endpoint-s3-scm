package hudson.plugins.EndpointS3SCM.checkout;

import hudson.scm.SCMRevisionState;

import java.util.Objects;

/**
 * Snapshot of the S3 object state used for change detection (polling).
 *
 * <p>Stores the bucket, object key, and last-modified timestamp of the archive
 * that was downloaded during the last successful build. Jenkins compares the
 * stored state against the current S3 state to decide whether a new build is
 * needed.</p>
 *
 * <p>Serialized by XStream into {@code build.xml}; all fields use {@link String}
 * and primitive types for safe round-trip serialization.</p>
 */
public class S3SCMRevisionState extends SCMRevisionState {

    private static final long serialVersionUID = 1L;

    private final String bucket;
    private final String key;

    /**
     * Last-modified timestamp as epoch milliseconds. {@code -1} when unknown.
     */
    private final long lastModifiedMs;

    public S3SCMRevisionState(String bucket, String key, long lastModifiedMs) {
        this.bucket = bucket;
        this.key = key;
        this.lastModifiedMs = lastModifiedMs;
    }

    public String getBucket() {
        return bucket;
    }

    public String getKey() {
        return key;
    }

    public long getLastModifiedMs() {
        return lastModifiedMs;
    }

    /**
     * Returns {@code true} when this state represents the same S3 object as {@code other}.
     */
    public boolean matches(S3SCMRevisionState other) {
        return other != null
                && Objects.equals(bucket, other.bucket)
                && Objects.equals(key, other.key)
                && lastModifiedMs == other.lastModifiedMs;
    }

    @Override
    public String toString() {
        return bucket + ":" + key + "@" + lastModifiedMs;
    }
}
