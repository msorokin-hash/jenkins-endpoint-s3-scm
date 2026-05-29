package hudson.plugins.EndpointS3SCM.config;

import java.util.List;

/**
 * Common read-only contract for Endpoint S3 SCM configuration.
 *
 * <p>Implemented by both global and folder-level settings so checkout code can
 * work with a single configuration type regardless of where the settings were
 * resolved from.</p>
 */
public interface EndpointS3SCMSettings {

    List<S3Location> getLocations();

    int getMaxRetries();

    int getRetryDelayMs();

    boolean isStripTopLevelDir();

    boolean isSkipChecksumVerification();
}
