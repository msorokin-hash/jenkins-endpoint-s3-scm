package hudson.plugins.EndpointS3SCM.config;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.AbortException;
import hudson.util.Secret;

import java.util.List;

import static hudson.plugins.EndpointS3SCM.util.PluginUtils.isEmpty;
import static java.net.URI.create;

/**
 * Validates Endpoint S3 SCM runtime configuration.
 */
public class EndpointS3SCMConfigValidator {

    private static final String LOG_PREFIX = "[EndpointS3SCM] ";

    /**
     * Validates prefix and locations directly (useful for unit tests without a Jenkins context).
     *
     * @param prefix    job-level directory/prefix
     * @param locations global S3 locations
     * @throws AbortException if configuration is invalid
     */
    public void validate(String prefix, List<S3Location> locations) throws AbortException {
        if (isEmpty(prefix)) {
            throw new AbortException(LOG_PREFIX + "Directory/prefix is required");
        }

        if (locations == null || locations.isEmpty()) {
            throw new AbortException(
                    LOG_PREFIX + "At least one S3 source is required. "
                            + "Configure Endpoint S3 SCM Settings in a parent folder, "
                            + "or add a global fallback in Manage Jenkins → System.");
        }

        for (int i = 0; i < locations.size(); i++) validateLocation(locations.get(i), i);
    }

    /**
     * Validates resolved Jenkins credentials.
     *
     * @param credentials Jenkins username/password credentials
     * @throws AbortException if username or password is empty
     */
    public void validateCredentials(StandardUsernamePasswordCredentials credentials) throws AbortException {
        if (isEmpty(credentials.getUsername())) {
            throw new AbortException(LOG_PREFIX + "Credentials: empty username");
        }

        if (credentials.getPassword() == null || isEmpty(Secret.toString(credentials.getPassword()))) {
            throw new AbortException(LOG_PREFIX + "Credentials: empty password");
        }
    }

    /**
     * Validates one S3 location.
     *
     * @param location S3 location
     * @param index    location index
     * @throws AbortException if location is invalid
     */
    private void validateLocation(S3Location location, int index) throws AbortException {
        if (isEmpty(location.getEndpoint())) {
            throw new AbortException(LOG_PREFIX + "Endpoint required for source #" + index);
        }

        try {
            create(location.getEndpoint());
        } catch (Exception e) {
            throw new AbortException(LOG_PREFIX + "Invalid URL for source #" + index + ": " + location.getEndpoint());
        }

        if (isEmpty(location.getBucket())) {
            throw new AbortException(LOG_PREFIX + "Bucket required for source #" + index);
        }

        if (isEmpty(location.getCredentialsId())) {
            throw new AbortException(LOG_PREFIX + "Credentials required for source #" + index);
        }
    }
}
