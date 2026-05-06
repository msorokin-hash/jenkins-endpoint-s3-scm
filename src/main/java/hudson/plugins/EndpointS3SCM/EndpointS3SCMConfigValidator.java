package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.AbortException;
import hudson.util.Secret;

import java.util.List;

import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;
import static java.net.URI.create;

/**
 * Validates {@link EndpointS3SCM} runtime configuration.
 */
public class EndpointS3SCMConfigValidator {

    private static final String LOG_PREFIX = "[EndpointS3SCM] ";

    /**
     * Validates SCM configuration before checkout.
     *
     * @param scm SCM configuration
     * @throws AbortException if configuration is invalid
     */
    public void validate(EndpointS3SCM scm) throws AbortException {
        List<S3Location> locations = scm.getLocations();

        if (locations.isEmpty()) {
            throw new AbortException(LOG_PREFIX + "At least one S3 source is required");
        }

        for (int i = 0; i < locations.size(); i++) {
            validateLocation(locations.get(i), i, scm.getPrefix());
        }
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
     * @param location     S3 location
     * @param index        location index
     * @param legacyPrefix legacy global prefix
     * @throws AbortException if location is invalid
     */
    private void validateLocation(S3Location location, int index, String legacyPrefix) throws AbortException {
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

        if (isEmpty(effectivePrefix(location, legacyPrefix))) {
            throw new AbortException(LOG_PREFIX + "Prefix required for source #" + index);
        }

        if (isEmpty(location.getCredentialsId())) {
            throw new AbortException(LOG_PREFIX + "Credentials required for source #" + index);
        }
    }

    /**
     * Resolves effective prefix for validation.
     *
     * @param location     S3 location
     * @param legacyPrefix legacy global prefix
     * @return effective prefix
     */
    private String effectivePrefix(S3Location location, String legacyPrefix) {
        if (location != null && !isEmpty(location.getPrefix())) {
            return location.getPrefix();
        }

        return legacyPrefix;
    }
}