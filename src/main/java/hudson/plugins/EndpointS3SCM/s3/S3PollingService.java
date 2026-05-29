package hudson.plugins.EndpointS3SCM.s3;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.PrefixResolver;
import hudson.plugins.EndpointS3SCM.checkout.PrefixResolver.ResolvedPrefix;
import hudson.plugins.EndpointS3SCM.checkout.S3SCMRevisionState;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfig;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfigResolver;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.scm.PollingResult;
import hudson.scm.SCMRevisionState;
import hudson.util.Secret;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.function.BiFunction;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.LOG_PREFIX;

/**
 * Performs S3 change detection for EndpointS3SCM.
 *
 * <p>The service compares the previously recorded {@link S3SCMRevisionState}
 * with the current S3 object state. In prefix mode, the current revision is the
 * latest ZIP under the resolved prefix. In exact ZIP mode, the current revision
 * is the specific object addressed by the resolved key.</p>
 */
public class S3PollingService {

    private final S3Service s3Service;
    private final CheckoutConfigResolver configResolver;
    private final PrefixResolver prefixResolver;

    /**
     * Creates polling service.
     *
     * @param s3Service      S3 operation service
     * @param configResolver checkout configuration resolver
     * @param prefixResolver prefix and mode resolver
     */
    public S3PollingService(S3Service s3Service,
                            CheckoutConfigResolver configResolver,
                            PrefixResolver prefixResolver) {
        this.s3Service = s3Service;
        this.configResolver = configResolver;
        this.prefixResolver = prefixResolver;
    }

    /**
     * Compares current remote S3 revision with the baseline revision.
     *
     * @param project           Jenkins job
     * @param baseline          previous revision state
     * @param listener          Jenkins task listener
     * @param prefix            raw Directory / Prefix value
     * @param locationsOverride test-only locations override
     * @param credentialLookup  credentials lookup strategy
     * @return polling result
     */
    public PollingResult compare(@NonNull Job<?, ?> project,
                                 @NonNull SCMRevisionState baseline,
                                 @NonNull TaskListener listener,
                                 @NonNull String prefix,
                                 @Nullable List<S3Location> locationsOverride,
                                 @NonNull BiFunction<Run<?, ?>, String, StandardUsernamePasswordCredentials> credentialLookup) {

        Run<?, ?> lastBuild = project.getLastBuild();

        if (lastBuild == null) {
            listener.getLogger().println(LOG_PREFIX + "No previous build, triggering first build.");
            return PollingResult.BUILD_NOW;
        }

        return compare(lastBuild, baseline, listener, prefix, locationsOverride, credentialLookup);
    }

    /**
     * Core polling logic extracted for unit tests.
     *
     * @param lastBuild         last completed build
     * @param baseline          previous revision state
     * @param listener          Jenkins task listener
     * @param prefix            raw Directory / Prefix value
     * @param locationsOverride test-only locations override
     * @param credentialLookup  credentials lookup strategy
     * @return polling result
     */
    public PollingResult compare(@Nullable Run<?, ?> lastBuild,
                                 @NonNull SCMRevisionState baseline,
                                 @NonNull TaskListener listener,
                                 @NonNull String prefix,
                                 @Nullable List<S3Location> locationsOverride,
                                 @NonNull BiFunction<Run<?, ?>, String, StandardUsernamePasswordCredentials> credentialLookup) {

        if (!(baseline instanceof S3SCMRevisionState baselineState)) {
            listener.getLogger().println(LOG_PREFIX + "No baseline revision, triggering build.");
            return PollingResult.BUILD_NOW;
        }

        CheckoutConfig config = configResolver.resolve(lastBuild, locationsOverride);
        PrintStream log = listener.getLogger();

        if (config.locations().isEmpty()) {
            log.println(LOG_PREFIX + "No S3 locations configured, skipping poll.");
            return PollingResult.NO_CHANGES;
        }

        ResolvedPrefix resolvedPrefix = prefixResolver.resolve(lastBuild, prefix, listener);
        log.printf(LOG_PREFIX + "Polling for changes (prefix='%s')%n", resolvedPrefix.expanded());

        for (S3Location location : config.locations()) {
            try {
                S3SCMRevisionState currentState =
                        fetchRevisionState(lastBuild, location, resolvedPrefix, credentialLookup);

                if (currentState == null) {
                    log.printf(LOG_PREFIX + "No object found at source '%s', skipping.%n",
                            safeLocationName(location));
                    continue;
                }

                if (!currentState.matches(baselineState)) {
                    log.printf(LOG_PREFIX + "Change detected at source '%s': %s → %s%n",
                            safeLocationName(location), baselineState, currentState);
                    return PollingResult.SIGNIFICANT;
                }

                log.printf(LOG_PREFIX + "No changes at source '%s'.%n", safeLocationName(location));
                return PollingResult.NO_CHANGES;

            } catch (Exception e) {
                log.printf(LOG_PREFIX + "Polling warning for source '%s': %s%n",
                        safeLocationName(location), e.getMessage());
            }
        }

        log.println(LOG_PREFIX + "All sources unavailable, assuming no changes.");
        return PollingResult.NO_CHANGES;
    }

    /**
     * Fetches current S3 revision state from one source location.
     *
     * @param build            build context for credentials lookup
     * @param location         S3 source location
     * @param resolvedPrefix   expanded prefix and mode
     * @param credentialLookup credentials lookup strategy
     * @return current revision state, or {@code null} if not found
     * @throws IOException if S3 request fails
     */
    @Nullable
    private S3SCMRevisionState fetchRevisionState(
            @Nullable Run<?, ?> build,
            @NonNull S3Location location,
            @NonNull ResolvedPrefix resolvedPrefix,
            @NonNull BiFunction<Run<?, ?>, String, StandardUsernamePasswordCredentials> credentialLookup)
            throws IOException {

        StandardUsernamePasswordCredentials credentials =
                credentialLookup.apply(build, location.getCredentialsId());

        if (credentials == null) {
            return null;
        }

        try (S3Client s3 = s3Service.createClient(
                location.getEndpoint(),
                location.getRegion(),
                credentials.getUsername(),
                Secret.toString(credentials.getPassword())
        )) {
            S3ObjectCandidate candidate = resolvedPrefix.isExact()
                    ? s3Service.headObject(s3, location.getBucket(), resolvedPrefix.expanded())
                    : s3Service.findLatestZipObject(s3, location.getBucket(), resolvedPrefix.expanded());

            if (candidate == null) {
                return null;
            }

            return new S3SCMRevisionState(
                    location.getBucket(),
                    candidate.key(),
                    candidate.lastModified() != null ? candidate.lastModified().toEpochMilli() : -1L
            );

        } catch (SdkException e) {
            throw new IOException("S3 polling failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns human-readable source name for logs.
     *
     * @param location S3 source location
     * @return configured location name or bucket name
     */
    private String safeLocationName(S3Location location) {
        if (location == null) {
            return "unknown";
        }

        return location.getName() != null && !location.getName().isBlank()
                ? location.getName()
                : location.getBucket();
    }
}
