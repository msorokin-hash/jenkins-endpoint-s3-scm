package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.*;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfig;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfigResolver;
import hudson.plugins.EndpointS3SCM.config.EndpointS3SCMConfigValidator;
import hudson.plugins.EndpointS3SCM.config.S3Location;
import hudson.plugins.EndpointS3SCM.s3.S3ArchiveDownloader;
import hudson.plugins.EndpointS3SCM.s3.S3ObjectCandidate;
import hudson.plugins.EndpointS3SCM.s3.S3PollingService;
import hudson.plugins.EndpointS3SCM.s3.S3Service;
import hudson.scm.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;
import static hudson.plugins.EndpointS3SCM.util.PluginUtils.maskUrl;
import static hudson.plugins.EndpointS3SCM.util.PluginUtils.resolveRegionId;

/**
 * Jenkins SCM implementation that retrieves ZIP archives from S3-compatible storage.
 *
 * <p>The {@code prefix} field supports Jenkins environment variable expansion and
 * two operating modes:</p>
 * <ul>
 *   <li><b>Prefix mode</b> — value does not end with {@code .zip}; the plugin lists
 *       objects and selects the latest ZIP by {@code lastModified}.</li>
 *   <li><b>Exact ZIP mode</b> — value ends with {@code .zip}; the plugin downloads
 *       that exact S3 object key.</li>
 * </ul>
 *
 * <p>S3 source locations are resolved from the nearest ancestor folder and then
 * from global configuration as cascading fallback locations.</p>
 */
public class EndpointS3SCM extends SCM {

    private final String prefix;
    private String targetDirectory;

    // Transient service objects — stateless, never serialized.
    // Recreated in readResolve() after XStream deserialization.
    private transient EndpointS3SCMConfigValidator configValidator;
    private transient CheckoutConfigResolver configResolver;
    private transient PrefixResolver prefixResolver;
    private transient S3Service s3Service;
    private transient ZipService zipService;
    private transient ChecksumVerifier checksumVerifier;
    private transient S3ArchiveDownloader archiveDownloader;
    private transient WorkspaceExtractor workspaceExtractor;
    private transient S3PollingService pollingService;

    private transient List<S3Location> locationsOverride;

    private transient BiFunction<Run<?, ?>, String, StandardUsernamePasswordCredentials> credentialLookup;

    /**
     * Creates SCM configuration.
     *
     * @param prefix job-level Directory / Prefix value
     */
    @DataBoundConstructor
    public EndpointS3SCM(String prefix) {
        this.prefix = Util.fixEmptyAndTrim(prefix);
        initTransientFields();
    }

    /**
     * Reinitializes transient fields after XStream deserializes this object.
     * XStream bypasses constructors, so inline initializers do not run —
     * every transient service must be recreated here.
     *
     * @return this SCM instance
     */
    protected Object readResolve() {
        initTransientFields();
        return this;
    }

    private void initTransientFields() {
        if (credentialLookup == null) credentialLookup = CredentialsHelper::lookupCredentialsForRun;
        if (configValidator == null) configValidator = new EndpointS3SCMConfigValidator();
        if (configResolver == null) configResolver = new CheckoutConfigResolver();
        if (prefixResolver == null) prefixResolver = new PrefixResolver();
        if (s3Service == null) s3Service = new S3Service();
        if (zipService == null) zipService = new ZipService();
        if (checksumVerifier == null) checksumVerifier = new ChecksumVerifier();
        if (archiveDownloader == null)
            archiveDownloader = new S3ArchiveDownloader(s3Service, configValidator, checksumVerifier);
        if (workspaceExtractor == null)
            workspaceExtractor = new WorkspaceExtractor(zipService);
        if (pollingService == null)
            pollingService = new S3PollingService(s3Service, configResolver, prefixResolver);
    }

    @Override
    public EndpointS3SCMDescriptor getDescriptor() {
        try {
            return (EndpointS3SCMDescriptor) super.getDescriptor();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    public String getPrefix() {
        return prefix;
    }

    public String getTargetDirectory() {
        return targetDirectory;
    }

    /**
     * Sets optional target directory inside workspace.
     *
     * @param targetDirectory target subdirectory, or blank for workspace root
     */
    @DataBoundSetter
    public void setTargetDirectory(String targetDirectory) {
        this.targetDirectory = Util.fixEmptyAndTrim(targetDirectory);
    }

    @NonNull
    public List<S3Location> getLocations() {
        return locationsOverride != null
                ? Collections.unmodifiableList(locationsOverride)
                : Collections.emptyList();
    }

    void setLocations(List<S3Location> locations) {
        this.locationsOverride = locations == null ? null
                : locations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(S3Location::getPriority))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    void setCredentialLookup(BiFunction<Run<?, ?>, String, StandardUsernamePasswordCredentials> lookup) {
        this.credentialLookup = lookup;
    }

    void setS3Service(S3Service s3Service) {
        this.s3Service = s3Service;
        rebuildServices();
    }

    void setZipService(ZipService zipService) {
        this.zipService = zipService;
        rebuildServices();
    }

    void setChecksumVerifier(ChecksumVerifier checksumVerifier) {
        this.checksumVerifier = checksumVerifier;
        rebuildServices();
    }

    /**
     * Rebuilds composed services after tests replace dependencies.
     */
    private void rebuildServices() {
        this.archiveDownloader = new S3ArchiveDownloader(s3Service, configValidator, checksumVerifier);
        this.workspaceExtractor = new WorkspaceExtractor(zipService);
        this.pollingService = new S3PollingService(s3Service, configResolver, prefixResolver);
    }

    @Override
    public void checkout(@NonNull Run<?, ?> build,
                         @NonNull Launcher launcher,
                         @NonNull FilePath workspace,
                         @NonNull TaskListener listener,
                         File changelogFile,
                         SCMRevisionState baseline) throws IOException {

        CheckoutConfig config = resolveAndValidateConfig(build);

        // Suppress verbose logging and build actions for Jenkins-internal checkouts
        // (@script = reading Jenkinsfile, @libs = loading shared library).
        // Errors still surface via exceptions which Jenkins reports independently.
        boolean silent = isSilentCheckout(workspace);
        TaskListener effectiveListener = silent ? TaskListener.NULL : listener;
        PrintStream log = effectiveListener.getLogger();

        PrefixResolver.ResolvedPrefix resolvedPrefix =
                prefixResolver.resolve(build, prefix, effectiveListener);

        if (!resolvedPrefix.expanded().equals(resolvedPrefix.original())) {
            log.printf(LOG_PREFIX + "Expanded prefix: '%s' → '%s'%n",
                    resolvedPrefix.original(), resolvedPrefix.expanded());
        }

        logCheckoutStart(config, log, resolvedPrefix);

        Path tempFile = null;
        boolean success = false;
        S3CheckoutAction checkoutAction = null;

        try {
            tempFile = createTempFile();
            checkoutAction = checkoutFromAnyLocation(
                    build, workspace, effectiveListener, config, tempFile, resolvedPrefix);
            success = true;
        } catch (AbortException e) {
            throw e;
        } catch (Exception e) {
            effectiveListener.error("Checkout failed: %s", cleanErrorMessage(e));
            throw new IOException("Failed to checkout from S3-compatible endpoint", e);
        } finally {
            workspaceExtractor.cleanup(tempFile, workspace, targetDirectory, success, log);
        }

        // Do not attach S3 Artifact action for internal (Jenkinsfile/library) checkouts —
        // they would show up as spurious entries in the build sidebar.
        if (build != null && checkoutAction != null && !silent) {
            build.addAction(checkoutAction);
        }
    }

    /**
     * Returns {@code true} when the checkout is a Jenkins-internal operation —
     * reading the Jenkinsfile ({@code @script}) or loading a shared library
     * ({@code @libs}) — rather than an actual build-artifact checkout.
     *
     * <p>Silent checkouts suppress all progress logging and do not attach an
     * {@link S3CheckoutAction} to the build. Errors still propagate via exceptions
     * and are displayed by Jenkins independently.</p>
     */
    private static boolean isSilentCheckout(@NonNull FilePath workspace) {
        String path = workspace.getRemote();
        return path.contains("@script") || path.contains("@libs");
    }

    @Override
    public boolean supportsPolling() {
        return true;
    }

    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @NonNull Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            @NonNull TaskListener listener) {

        S3CheckoutAction action = build.getAction(S3CheckoutAction.class);

        if (action == null) {
            listener.getLogger().println(
                    LOG_PREFIX + "No S3 checkout action found in build, cannot determine revision.");
            return SCMRevisionState.NONE;
        }

        return new S3SCMRevisionState(action.getBucket(), action.getKey(), action.getLastModifiedMs());
    }

    @Override
    public PollingResult compareRemoteRevisionWith(
            @NonNull Job<?, ?> project,
            @Nullable Launcher launcher,
            @Nullable FilePath workspace,
            @NonNull TaskListener listener,
            @NonNull SCMRevisionState baseline) throws IOException {

        return pollingService.compare(
                project,
                baseline,
                listener,
                prefix,
                locationsOverride,
                credentialLookup
        );
    }

    @Override
    public void buildEnvironment(@NonNull Run<?, ?> build, @NonNull Map<String, String> env) {
        env.put("ENDPOINT_S3_PREFIX", Objects.requireNonNullElse(prefix, ""));

        List<S3Location> locs = resolveConfig(build).locations();
        env.put("ENDPOINT_S3_LOCATIONS_COUNT", String.valueOf(locs.size()));

        IntStream.range(0, locs.size()).forEach(i -> {
            S3Location location = locs.get(i);
            String envPrefix = "ENDPOINT_S3_LOCATION_" + i + "_";

            env.put(envPrefix + "NAME", Objects.requireNonNullElse(location.getName(), ""));
            env.put(envPrefix + "ENDPOINT", Objects.requireNonNullElse(location.getEndpoint(), ""));
            env.put(envPrefix + "BUCKET", Objects.requireNonNullElse(location.getBucket(), ""));
            env.put(envPrefix + "PREFIX", Objects.requireNonNullElse(prefix, ""));
            env.put(envPrefix + "REGION", Objects.requireNonNullElse(location.getRegion(), ""));
            env.put(envPrefix + "PRIORITY", String.valueOf(location.getPriority()));
        });
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new NullChangeLogParser();
    }

    private CheckoutConfig resolveAndValidateConfig(@NonNull Run<?, ?> build) throws AbortException {
        CheckoutConfig config = resolveConfig(build);
        configValidator.validate(prefix, config.locations());
        return config;
    }

    private CheckoutConfig resolveConfig(@Nullable Run<?, ?> build) {
        return configResolver.resolve(build, locationsOverride);
    }

    private Path createTempFile() throws IOException {
        return Files.createTempFile(TEMP_FILE_PREFIX, ZIP_FILE_SUFFIX);
    }

    private void logCheckoutStart(@NonNull CheckoutConfig config,
                                  @NonNull PrintStream log,
                                  @NonNull PrefixResolver.ResolvedPrefix resolvedPrefix) {
        log.println(LOG_PREFIX + "Checkout from S3-compatible endpoint(s)");
        log.printf(LOG_PREFIX + "prefix: %s%n",
                resolvedPrefix.expanded().equals(resolvedPrefix.original())
                        ? resolvedPrefix.original()
                        : resolvedPrefix.original() + " → " + resolvedPrefix.expanded());
        log.printf(LOG_PREFIX + "mode: %s%n",
                resolvedPrefix.isExact() ? "EXACT ZIP" : "PREFIX (latest)");
        log.printf(LOG_PREFIX + "maxRetries: %d, retryDelayMs: %dms%n",
                config.maxRetries(), config.retryDelayMs());
        log.printf(LOG_PREFIX + "stripTopLevelDir: %s%n", config.stripTopLevelDir());

        if (targetDirectory != null) {
            log.printf(LOG_PREFIX + "targetDirectory: %s%n", targetDirectory);
        }
    }

    private S3CheckoutAction checkoutFromAnyLocation(@NonNull Run<?, ?> build,
                                                     @NonNull FilePath workspace,
                                                     @NonNull TaskListener listener,
                                                     @NonNull CheckoutConfig config,
                                                     @NonNull Path tempFile,
                                                     @NonNull PrefixResolver.ResolvedPrefix resolvedPrefix)
            throws IOException {

        PrintStream log = listener.getLogger();
        List<String> errors = new ArrayList<>();

        for (S3Location location : config.locations()) {
            try {
                return checkoutFromLocation(
                        build, workspace, listener, config, tempFile, location, resolvedPrefix);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Checkout interrupted", e);
            } catch (Exception e) {
                String message = String.format(
                        "Source '%s' failed: %s",
                        safeLocationName(location),
                        cleanErrorMessage(e)
                );
                errors.add(message);
                log.println(LOG_PREFIX + "ERROR: " + message);
            }
        }

        throw new AbortException(
                "All S3 sources failed:\n  - " + String.join("\n  - ", errors)
        );
    }

    private S3CheckoutAction checkoutFromLocation(@NonNull Run<?, ?> build,
                                                  @NonNull FilePath workspace,
                                                  @NonNull TaskListener listener,
                                                  @NonNull CheckoutConfig config,
                                                  @NonNull Path tempFile,
                                                  @NonNull S3Location location,
                                                  @NonNull PrefixResolver.ResolvedPrefix resolvedPrefix)
            throws IOException, InterruptedException {

        PrintStream log = listener.getLogger();
        logLocationAttempt(location, log, resolvedPrefix.expanded());

        S3ObjectCandidate downloadedObject =
                archiveDownloader.downloadLatest(
                        build,
                        location,
                        resolvedPrefix.expanded(),
                        tempFile,
                        listener,
                        config,
                        credentialLookup
                );

        log.printf(
                LOG_PREFIX + "Download complete. Object='%s', size=%d bytes%n",
                downloadedObject.key(),
                Files.size(tempFile)
        );

        workspaceExtractor.extract(tempFile, workspace, targetDirectory, listener, config);

        log.printf(
                LOG_PREFIX + "Checkout finished successfully from source '%s'.%n",
                safeLocationName(location)
        );

        return new S3CheckoutAction(
                downloadedObject.key(),
                location.getBucket(),
                safeLocationName(location),
                downloadedObject.lastModified(),
                downloadedObject.size(),
                resolvedPrefix.mode()
        );
    }

    private void logLocationAttempt(@NonNull S3Location location,
                                    @NonNull PrintStream log,
                                    @NonNull String expandedPrefix) {
        log.printf(
                LOG_PREFIX + "Trying source '%s': endpoint=%s, bucket=%s, prefix=%s, region=%s, priority=%d%n",
                safeLocationName(location),
                maskUrl(location.getEndpoint()),
                location.getBucket(),
                expandedPrefix,
                resolveRegionId(location.getRegion()),
                location.getPriority()
        );
    }

    private String safeLocationName(S3Location location) {
        if (location == null) {
            return "unknown";
        }

        return location.getName() != null && !location.getName().isBlank()
                ? location.getName()
                : location.getBucket();
    }

    private String cleanErrorMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return message.replace(LOG_PREFIX, "").trim();
    }
}