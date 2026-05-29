package hudson.plugins.EndpointS3SCM.checkout;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.config.CheckoutConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.LOG_PREFIX;

/**
 * Handles workspace preparation, ZIP validation, archive extraction and cleanup.
 *
 * <p>This class isolates workspace-related behavior from the SCM orchestration code.
 * It supports optional extraction into a target subdirectory inside the Jenkins workspace.</p>
 */
public class WorkspaceExtractor {

    private final ZipService zipService;

    /**
     * Creates workspace extractor.
     *
     * @param zipService ZIP validation and extraction service
     */
    public WorkspaceExtractor(ZipService zipService) {
        this.zipService = zipService;
    }

    /**
     * Validates a downloaded ZIP archive and extracts it into the workspace.
     *
     * @param tempFile        downloaded ZIP file
     * @param workspace       Jenkins workspace
     * @param targetDirectory optional subdirectory inside workspace
     * @param listener        Jenkins task listener
     * @param config          resolved checkout configuration
     * @throws IOException          if validation or extraction fails
     * @throws InterruptedException if workspace operation is interrupted
     */
    public void extract(@NonNull Path tempFile,
                        @NonNull FilePath workspace,
                        @Nullable String targetDirectory,
                        @NonNull TaskListener listener,
                        @NonNull CheckoutConfig config)
            throws IOException, InterruptedException {

        zipService.validateZipFile(tempFile, listener);

        FilePath extractionTarget = resolveTarget(workspace, targetDirectory);

        extractionTarget.mkdirs();
        extractionTarget.deleteContents();

        listener.getLogger().println(LOG_PREFIX + "Extracting ZIP archive into workspace...");
        zipService.extractZipSecurely(tempFile, extractionTarget, listener, config.stripTopLevelDir());
    }

    /**
     * Cleans temporary file and workspace after checkout.
     *
     * @param tempFile        temporary ZIP file
     * @param workspace       Jenkins workspace
     * @param targetDirectory optional target subdirectory
     * @param success         whether checkout succeeded
     * @param log             build logger
     */
    public void cleanup(@Nullable Path tempFile,
                        @Nullable FilePath workspace,
                        @Nullable String targetDirectory,
                        boolean success,
                        @NonNull PrintStream log) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.printf(LOG_PREFIX + "Warning: cannot delete temp file: %s%n", e.getMessage());
            }
        }

        if (success || workspace == null) {
            return;
        }

        try {
            FilePath cleanupTarget = resolveTarget(workspace, targetDirectory);

            if (cleanupTarget.exists()) {
                cleanupTarget.deleteContents();
                log.println(LOG_PREFIX + "Workspace cleaned up due to failed checkout.");
            }
        } catch (Exception e) {
            log.printf(LOG_PREFIX + "Warning: cleanup failed: %s%n", e.getMessage());
        }
    }

    /**
     * Resolves the effective extraction target.
     *
     * @param workspace       workspace root
     * @param targetDirectory optional target subdirectory
     * @return workspace root or child directory
     */
    private FilePath resolveTarget(@NonNull FilePath workspace, @Nullable String targetDirectory) {
        return targetDirectory != null && !targetDirectory.isBlank()
                ? workspace.child(targetDirectory)
                : workspace;
    }
}
