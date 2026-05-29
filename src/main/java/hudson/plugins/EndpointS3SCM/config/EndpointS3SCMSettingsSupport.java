package hudson.plugins.EndpointS3SCM.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Utility methods shared by Endpoint S3 SCM configuration classes.
 *
 * <p>Contains normalization and validation helpers used by both
 * {@link EndpointS3SCMGlobalConfig} and {@link EndpointS3SCMFolderProperty}.</p>
 */
public final class EndpointS3SCMSettingsSupport {

    private EndpointS3SCMSettingsSupport() {
    }

    /**
     * Removes null locations and sorts locations by priority.
     */
    public static List<S3Location> normalizeLocations(List<S3Location> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        return input.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(S3Location::getPriority))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Restricts an integer value to the provided inclusive range.
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
