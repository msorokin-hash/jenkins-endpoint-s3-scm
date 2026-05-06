package hudson.plugins.EndpointS3SCM;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogParser;
import hudson.scm.NullChangeLogParser;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static hudson.plugins.EndpointS3SCM.PluginUtils.isEmpty;
import static hudson.plugins.EndpointS3SCM.PluginUtils.maskUrl;

/**
 * Jenkins SCM implementation that retrieves the latest ZIP archive from S3-compatible storage.
 *
 * <p>This SCM is intended for workflows where source code or build artifacts are published
 * as ZIP archives into S3-compatible storage, for example AWS S3, MinIO, Ceph or another
 * compatible endpoint.</p>
 *
 * <p>The plugin supports multiple configured S3 locations. During checkout it sorts them
 * by priority and tries each source until one succeeds. For every source it searches the
 * latest non-empty {@code .zip} object inside the prefix configured for that specific
 * location, downloads it, validates the archive and extracts it into the Jenkins workspace.</p>
 *
 * <p><b>Backward compatibility:</b> older job configurations may still contain a global
 * {@code prefix} field. The current UI stores prefix inside each {@link S3Location}.
 * The global prefix is kept only as a fallback for old saved configurations.</p>
 */
public class EndpointS3SCM extends SCM {

    public static final long LARGE_OBJECT_WARNING_SIZE = 1_000_000_000L; // 1G

    private static final String LOG_PREFIX = "[EndpointS3SCM] ";

    /**
     * Legacy global prefix kept for backward compatibility with older job configurations.
     */
    private final String prefix;

    private final S3Service s3Service = new S3Service();
    private final ZipService zipService = new ZipService();
    private final EndpointS3SCMConfigValidator configValidator = new EndpointS3SCMConfigValidator();

    private List<S3Location> locations = new ArrayList<>();
    private int maxRetries = 3;
    private int retryDelayMs = 1000;
    private boolean stripTopLevelDir = true;

    /**
     * Creates SCM configuration.
     *
     * @param prefix legacy global S3 prefix, may be null
     */
    @DataBoundConstructor
    public EndpointS3SCM(String prefix) {
        this.prefix = Util.fixEmptyAndTrim(prefix);
    }

    /**
     * Returns legacy global S3 prefix.
     *
     * @return legacy global prefix, or null
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns configured S3 source locations.
     *
     * @return unmodifiable list of S3 locations
     */
    @NonNull
    public List<S3Location> getLocations() {
        return locations != null
                ? Collections.unmodifiableList(locations)
                : Collections.emptyList();
    }

    /**
     * Sets S3 source locations.
     *
     * <p>Locations are normalized by removing null entries and sorting by priority.</p>
     *
     * @param locations S3 locations
     */
    @DataBoundSetter
    public void setLocations(List<S3Location> locations) {
        this.locations = locations == null
                ? new ArrayList<>()
                : locations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(S3Location::getPriority))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Sets maximum number of download retry attempts.
     *
     * @param maxRetries maximum retry attempts
     */
    @DataBoundSetter
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(1, Math.min(maxRetries, 10));
    }

    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    /**
     * Sets retry delay in milliseconds.
     *
     * @param retryDelayMs retry delay in milliseconds
     */
    @DataBoundSetter
    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = Math.max(500, Math.min(retryDelayMs, 30000));
    }

    public boolean isStripTopLevelDir() {
        return stripTopLevelDir;
    }

    /**
     * Controls whether common top-level directory in ZIP archive should be stripped.
     *
     * @param stripTopLevelDir true to strip common top-level directory
     */
    @DataBoundSetter
    public void setStripTopLevelDir(boolean stripTopLevelDir) {
        this.stripTopLevelDir = stripTopLevelDir;
    }

    /**
     * Performs Jenkins SCM checkout.
     *
     * @param build         current Jenkins build
     * @param launcher      Jenkins launcher
     * @param workspace     Jenkins workspace
     * @param listener      build listener
     * @param changelogFile changelog file, unused
     * @param baseline      SCM revision baseline, unused
     * @throws IOException if checkout fails
     */
    @Override
    public void checkout(@NonNull Run<?, ?> build,
                         @NonNull Launcher launcher,
                         @NonNull FilePath workspace,
                         @NonNull TaskListener listener,
                         File changelogFile,
                         SCMRevisionState baseline) throws IOException {

        configValidator.validate(this);

        PrintStream log = listener.getLogger();

        log.println(LOG_PREFIX + "Checkout from S3-compatible endpoint(s)");
        log.println(LOG_PREFIX + "prefix is configured per S3 location");
        log.printf(LOG_PREFIX + "maxRetries: %d, retryDelayMs: %dms%n", maxRetries, retryDelayMs);
        log.printf(LOG_PREFIX + "stripTopLevelDir: %s%n", stripTopLevelDir);

        Path tempFile = null;
        boolean success = false;
        List<String> errors = new ArrayList<>();

        try {
            tempFile = Files.createTempFile("endpoint-s3scm-", ".zip");

            for (S3Location location : getLocations()) {
                String effectivePrefix = effectivePrefix(location);

                try {
                    log.printf(
                            LOG_PREFIX + "Trying source '%s': endpoint=%s, bucket=%s, prefix=%s, region=%s, priority=%d%n",
                            safeLocationName(location),
                            maskUrl(location.getEndpoint()),
                            location.getBucket(),
                            effectivePrefix,
                            PluginUtils.resolveRegionId(location.getRegion()),
                            location.getPriority()
                    );

                    S3ObjectCandidate downloadedObject =
                            downloadLatestFromLocation(build, location, effectivePrefix, tempFile, listener);

                    log.printf(
                            LOG_PREFIX + "Download complete. Object='%s', size=%d bytes%n",
                            downloadedObject.getKey(),
                            Files.size(tempFile)
                    );

                    zipService.validateZipFile(tempFile, listener);

                    workspace.mkdirs();
                    workspace.deleteContents();

                    log.println(LOG_PREFIX + "Extracting ZIP archive into workspace...");
                    zipService.extractZipSecurely(tempFile, workspace, listener, stripTopLevelDir);

                    log.printf(
                            LOG_PREFIX + "Checkout finished successfully from source '%s'.%n",
                            safeLocationName(location)
                    );

                    success = true;
                    break;

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

            if (!success) {
                throw new AbortException(
                        "All S3 sources failed:\n  - " + String.join("\n  - ", errors)
                );
            }

        } catch (AbortException e) {
            throw e;
        } catch (Exception e) {
            listener.error(LOG_PREFIX + "Checkout failed: " + cleanErrorMessage(e));
            throw new IOException("Failed to checkout from S3-compatible endpoint", e);
        } finally {
            cleanup(tempFile, workspace, success, log);
        }
    }

    /**
     * Downloads latest ZIP archive from one S3 location.
     *
     * @param build    Jenkins build context
     * @param location S3 source location
     * @param prefix   prefix where ZIP archives are searched
     * @param tempFile temporary target file
     * @param listener Jenkins listener
     * @return downloaded object metadata
     * @throws AbortException       if checkout cannot continue
     * @throws IOException          if S3 operation fails
     * @throws InterruptedException if retry sleep is interrupted
     */
    private S3ObjectCandidate downloadLatestFromLocation(@NonNull Run<?, ?> build,
                                                         @NonNull S3Location location,
                                                         @NonNull String prefix,
                                                         @NonNull Path tempFile,
                                                         @NonNull TaskListener listener)
            throws IOException, AbortException, InterruptedException {

        StandardUsernamePasswordCredentials credentials =
                CredentialsHelper.lookupCredentialsForRun(build, location.getCredentialsId());

        if (credentials == null) {
            throw new AbortException("Cannot find credentials: " + location.getCredentialsId());
        }

        configValidator.validateCredentials(credentials);

        try (S3Client s3 = s3Service.createClient(
                location.getEndpoint(),
                location.getRegion(),
                credentials.getUsername(),
                Secret.toString(credentials.getPassword())
        )) {
            PrintStream log = listener.getLogger();

            log.printf(
                    LOG_PREFIX + "Searching latest ZIP in bucket='%s', prefix='%s'%n",
                    location.getBucket(),
                    prefix
            );

            S3ObjectCandidate latest = s3Service.findLatestZipObject(s3, location.getBucket(), prefix);

            if (latest == null) {
                throw new AbortException("No ZIP files found in prefix: " + prefix);
            }

            log.printf(
                    LOG_PREFIX + "Latest ZIP found: key='%s', size=%d bytes, lastModified=%s%n",
                    latest.getKey(),
                    latest.getSize(),
                    latest.getLastModified()
            );

            if (latest.getSize() > LARGE_OBJECT_WARNING_SIZE) {
                throw new AbortException(String.format(
                        "Archive too large (%d bytes). Max: %d",
                        latest.getSize(),
                        LARGE_OBJECT_WARNING_SIZE
                ));
            }

            Files.deleteIfExists(tempFile);

            log.println(LOG_PREFIX + "Downloading object...");

            s3Service.getObject(
                    s3,
                    location.getBucket(),
                    latest.getKey(),
                    tempFile,
                    maxRetries,
                    retryDelayMs,
                    listener
            );

            return latest;

        } catch (SdkException e) {
            throw new IOException("S3 operation failed: " + cleanErrorMessage(e), e);
        }
    }

    @Override
    public boolean supportsPolling() {
        return false;
    }

    /**
     * Adds S3 configuration as build environment variables.
     *
     * @param build current build
     * @param env   environment variables map
     */
    @Override
    public void buildEnvironment(@NonNull Run<?, ?> build, @NonNull Map<String, String> env) {
        env.put("ENDPOINT_S3_PREFIX", Objects.requireNonNullElse(prefix, ""));

        List<S3Location> locs = getLocations();
        env.put("ENDPOINT_S3_LOCATIONS_COUNT", String.valueOf(locs.size()));

        IntStream.range(0, locs.size()).forEach(i -> {
            S3Location location = locs.get(i);
            String envPrefix = "ENDPOINT_S3_LOCATION_" + i + "_";

            env.put(envPrefix + "NAME", Objects.requireNonNullElse(location.getName(), ""));
            env.put(envPrefix + "ENDPOINT", Objects.requireNonNullElse(location.getEndpoint(), ""));
            env.put(envPrefix + "BUCKET", Objects.requireNonNullElse(location.getBucket(), ""));
            env.put(envPrefix + "PREFIX", Objects.requireNonNullElse(effectivePrefix(location), ""));
            env.put(envPrefix + "REGION", Objects.requireNonNullElse(location.getRegion(), ""));
            env.put(envPrefix + "CREDENTIALS_ID", Objects.requireNonNullElse(location.getCredentialsId(), ""));
            env.put(envPrefix + "PRIORITY", String.valueOf(location.getPriority()));
        });
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new NullChangeLogParser();
    }

    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @NonNull Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            @NonNull TaskListener listener) {
        return null;
    }

    /**
     * Resolves effective prefix for location.
     *
     * @param location S3 location
     * @return location prefix or legacy global prefix
     */
    private String effectivePrefix(S3Location location) {
        if (location != null && !isEmpty(location.getPrefix())) {
            return location.getPrefix();
        }

        return prefix;
    }

    /**
     * Returns human-readable location name for logs.
     *
     * @param location S3 location
     * @return location name or bucket name
     */
    private String safeLocationName(S3Location location) {
        if (location == null) {
            return "unknown";
        }

        return !isEmpty(location.getName())
                ? location.getName()
                : location.getBucket();
    }

    /**
     * Normalizes exception message for Jenkins build log output.
     *
     * @param e exception
     * @return cleaned error message
     */
    private String cleanErrorMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return message.replace(LOG_PREFIX, "").trim();
    }

    /**
     * Cleans temporary file and workspace after checkout.
     *
     * @param tempFile  temporary downloaded ZIP file
     * @param workspace Jenkins workspace
     * @param success   whether checkout completed successfully
     * @param log       logger
     */
    private void cleanup(Path tempFile, FilePath workspace, boolean success, PrintStream log) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.printf(LOG_PREFIX + "Warning: cannot delete temp file: %s%n", e.getMessage());
            }
        }

        if (!success) {
            try {
                if (workspace != null && workspace.exists()) {
                    workspace.deleteContents();
                    log.println(LOG_PREFIX + "Workspace cleaned up due to failed checkout.");
                }
            } catch (Exception e) {
                log.printf(LOG_PREFIX + "Warning: cleanup failed: %s%n", e.getMessage());
            }
        }
    }
}