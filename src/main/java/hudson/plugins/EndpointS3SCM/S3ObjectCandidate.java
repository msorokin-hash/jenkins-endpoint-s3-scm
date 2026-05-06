package hudson.plugins.EndpointS3SCM;

import java.time.Instant;

/**
 * Represents an S3 object selected as a download candidate.
 * Used when the plugin searches for the latest ZIP archive in a configured S3 prefix.
 */
public final class S3ObjectCandidate {

    private final String key;
    private final long size;
    private final Instant lastModified;

    /**
     * Creates a candidate object descriptor.
     *
     * @param key          S3 object key
     * @param size         Object size in bytes
     * @param lastModified Object last modification timestamp
     */
    public S3ObjectCandidate(String key, long size, Instant lastModified) {
        this.key = key;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getKey() {
        return key;
    }

    public long getSize() {
        return size;
    }

    public Instant getLastModified() {
        return lastModified;
    }
}