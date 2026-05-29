package hudson.plugins.EndpointS3SCM.config;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;

/**
 * Resolves effective checkout configuration for a Jenkins build.
 *
 * <p>Resolution priority:</p>
 * <ol>
 *   <li>Test override locations, when provided.</li>
 *   <li>Nearest ancestor {@link EndpointS3SCMFolderProperty} locations.</li>
 *   <li>{@link EndpointS3SCMGlobalConfig} locations as cascading fallback.</li>
 *   <li>Empty defaults when no configuration exists.</li>
 * </ol>
 *
 * <p>Folder locations are tried before global locations. Global locations are appended
 * as fallback sources and are used only if all folder-level locations fail.</p>
 */
public class CheckoutConfigResolver {

    /**
     * Resolves checkout configuration for a build.
     *
     * @param build             current build; may be {@code null} in tests
     * @param locationsOverride test-only locations override
     * @return resolved checkout configuration, never {@code null}
     */
    public CheckoutConfig resolve(@Nullable Run<?, ?> build,
                                  @Nullable List<S3Location> locationsOverride) {
        if (locationsOverride != null) {
            return new CheckoutConfig(
                    Collections.unmodifiableList(locationsOverride),
                    DEFAULT_MAX_RETRIES,
                    DEFAULT_RETRY_DELAY_MS,
                    DEFAULT_STRIP_TOP_LEVEL_DIR,
                    DEFAULT_SKIP_CHECKSUM_VERIFICATION
            );
        }

        EndpointS3SCMFolderProperty folder = EndpointS3SCMFolderProperty.findNearest(build);
        EndpointS3SCMGlobalConfig global = EndpointS3SCMGlobalConfig.get();

        List<S3Location> merged = new ArrayList<>();

        if (folder != null) {
            merged.addAll(folder.getLocations());
        }

        if (global != null) {
            merged.addAll(global.getLocations());
        }

        EndpointS3SCMSettings primary = resolvePrimarySettings(folder, global);

        if (primary == null) {
            return new CheckoutConfig(
                    Collections.emptyList(),
                    DEFAULT_MAX_RETRIES,
                    DEFAULT_RETRY_DELAY_MS,
                    DEFAULT_STRIP_TOP_LEVEL_DIR,
                    DEFAULT_SKIP_CHECKSUM_VERIFICATION
            );
        }

        return new CheckoutConfig(
                Collections.unmodifiableList(merged),
                primary.getMaxRetries(),
                primary.getRetryDelayMs(),
                primary.isStripTopLevelDir(),
                primary.isSkipChecksumVerification()
        );
    }

    /**
     * Selects settings source for retry/checksum/extraction options.
     *
     * <p>Folder settings win when the folder has at least one location configured.
     * Otherwise global settings are used.</p>
     *
     * @param folder folder-level settings
     * @param global global fallback settings
     * @return primary settings source, or {@code null} if no configured locations exist
     */
    @Nullable
    private EndpointS3SCMSettings resolvePrimarySettings(@Nullable EndpointS3SCMFolderProperty folder,
                                                         @Nullable EndpointS3SCMGlobalConfig global) {
        if (folder != null && !folder.getLocations().isEmpty()) {
            return folder;
        }

        if (global != null && !global.getLocations().isEmpty()) {
            return global;
        }

        return null;
    }
}
