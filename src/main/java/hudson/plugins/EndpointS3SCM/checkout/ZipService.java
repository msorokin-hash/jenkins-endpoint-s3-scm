package hudson.plugins.EndpointS3SCM.checkout;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.*;

public class ZipService {

    /**
     * Validates that the specified file is a valid ZIP archive.
     * Checks if the file can be opened as a ZIP and contains at least one entry.
     *
     * @param file     Path to the ZIP file to validate
     * @param listener Jenkins task listener for logging
     * @throws IOException    If an I/O error occurs
     * @throws AbortException If the file is not a valid ZIP or is empty
     */
    public void validateZipFile(Path file, TaskListener listener) throws IOException {
        listener.getLogger().println(LOG_PREFIX + "Validating ZIP archive...");

        try (InputStream in = Files.newInputStream(file);
             ZipInputStream zis = new ZipInputStream(in)) {

            if (zis.getNextEntry() == null) {
                throw new AbortException("Downloaded file is not a valid ZIP archive or is empty");
            }
        } catch (AbortException e) {
            throw e;
        } catch (Exception e) {
            throw new AbortException("Invalid ZIP archive: " + e.getMessage());
        }

        listener.getLogger().println(LOG_PREFIX + "ZIP archive validation passed");
    }

    /**
     * Extracts a ZIP file to the workspace with security protections.
     * Implements multiple security checks to prevent Zip Bomb, Zip Slip, and other attacks.
     *
     * @param zipFile          Path to the ZIP file to extract
     * @param workspace        Jenkins workspace directory where files will be extracted
     * @param listener         Jenkins task listener for logging
     * @param stripTopLevelDir If true, attempts to remove a common top-level directory
     * @throws IOException          If an I/O error occurs
     * @throws InterruptedException If the operation is interrupted
     * @throws AbortException       If security limits are exceeded or extraction fails
     */
    public void extractZipSecurely(Path zipFile,
                                   FilePath workspace,
                                   TaskListener listener,
                                   boolean stripTopLevelDir) throws IOException, InterruptedException {

        PrintStream log = listener.getLogger();

        String topLevelToStrip = null;
        if (stripTopLevelDir) {
            topLevelToStrip = detectCommonTopLevelDir(zipFile, log);
            if (topLevelToStrip == null) {
                log.println(LOG_PREFIX + "stripTopLevelDir=true, but no common top-level directory detected. Extracting as-is.");
            } else {
                log.printf(LOG_PREFIX + "Common top-level directory detected: '%s'. It will be stripped.%n", topLevelToStrip);
            }
        }

        long totalExtracted = 0;
        int fileCount = 0;

        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {

            byte[] buffer = new byte[ZIP_BUFFER_SIZE];
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (++fileCount > MAX_EXTRACTED_FILES) {
                    throw new AbortException("Too many files in archive (> " + MAX_EXTRACTED_FILES + "). Possible Zip Bomb.");
                }

                String rawName = entry.getName();
                if (rawName.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                String safeName = sanitizeAndNormalize(rawName);
                if (safeName == null) {
                    log.printf(LOG_PREFIX + "SECURITY: Blocked unsafe ZIP entry: %s%n", rawName);
                    zis.closeEntry();
                    continue;
                }

                String finalPath = applyTopLevelStrip(safeName, topLevelToStrip, log);
                if (finalPath.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                FilePath target = workspace.child(finalPath);

                if (!isDescendant(workspace, target)) {
                    log.printf(LOG_PREFIX + "SECURITY: Blocked Zip Slip attempt: '%s' → '%s'%n", rawName, finalPath);
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    FilePath parent = target.getParent();
                    if (parent != null) {
                        parent.mkdirs();
                    }

                    long fileSize = 0;
                    try (OutputStream os = target.write()) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                            fileSize += len;
                            totalExtracted += len;

                            if (fileSize > MAX_EXTRACTED_FILE_SIZE_BYTES) {
                                throw new AbortException("Single file too large (> " +
                                        (MAX_EXTRACTED_FILE_SIZE_BYTES / 1024 / 1024) + " MB): " + finalPath);
                            }

                            if (totalExtracted > MAX_EXTRACTED_SIZE_BYTES) {
                                throw new AbortException("Total extracted data exceeds limit (" +
                                        (MAX_EXTRACTED_SIZE_BYTES / 1024 / 1024) + " MB). Possible Zip Bomb.");
                            }
                        }
                    }
                }

                zis.closeEntry();
            }
        }

        log.printf(LOG_PREFIX + "Extraction completed: %d files, %d bytes total%n",
                fileCount, totalExtracted);
    }

    /**
     * Sanitizes and normalizes a ZIP entry name to prevent path traversal attacks.
     * Removes dangerous path components and normalizes slashes.
     *
     * @param rawName Original ZIP entry name
     * @return Sanitized and normalized path, or null if the name is unsafe
     */
    private String sanitizeAndNormalize(String rawName) {
        if (rawName == null || rawName.isBlank()) return null;

        String name = rawName.replace('\\', '/').trim();

        while (name.startsWith("/")) name = name.substring(1);
        while (name.endsWith("/") && name.length() > 1) name = name.substring(0, name.length() - 1);

        if (name.isEmpty() || name.equals(".")) return null;

        try {
            Path path = Path.of(name).normalize();
            String normalized = path.toString().replace('\\', '/');

            for (Path p : path) {
                String s = p.toString();
                if (s.equals("..") || s.equals(".")) return null;
            }

            if (path.isAbsolute() || normalized.contains("../") || normalized.contains("//") || normalized.contains("/./")) {
                return null;
            }

            return normalized;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if a target file path is within the workspace directory.
     *
     * @param workspace Workspace directory
     * @param target    Target file path to check
     * @return true if target is inside workspace, false otherwise
     */
    private boolean isDescendant(FilePath workspace, FilePath target) {
        String ws = workspace.getRemote().replace('\\', '/');
        String tg = target.getRemote().replace('\\', '/');
        if (!ws.endsWith("/")) ws += "/";
        return tg.startsWith(ws);
    }

    /**
     * Removes the common top-level directory prefix from a path if stripping is enabled.
     *
     * @param normalized      Sanitized and normalized path
     * @param topLevelToStrip Common top-level directory to remove
     * @param log             Logger for warnings
     * @return Path with top-level directory stripped, or original path if not applicable
     */
    private String applyTopLevelStrip(String normalized, String topLevelToStrip, PrintStream log) {
        if (topLevelToStrip == null) return normalized;

        if (normalized.equals(topLevelToStrip) || normalized.equals(topLevelToStrip + "/")) {
            return "";
        }

        String prefix = topLevelToStrip + "/";
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }

        log.printf(LOG_PREFIX + "Entry '%s' is not under common top-level dir '%s' — keeping original path.%n",
                normalized, topLevelToStrip);
        return normalized;
    }

    /**
     * Analyzes the ZIP file to detect if all files are under a common top-level directory.
     *
     * @param zipFile Path to the ZIP file
     * @param log     Logger for progress and diagnostic messages
     * @return Common top-level directory name, or null if multiple top-level items exist
     * @throws IOException If an I/O error occurs while reading the ZIP
     */
    private String detectCommonTopLevelDir(Path zipFile, PrintStream log) throws IOException {
        log.println(LOG_PREFIX + "Detecting common top-level directory...");

        String candidate = null;
        Set<String> rootItems = new HashSet<>();

        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').trim();

                if (name.isEmpty() || name.equals(".") || name.equals("/")) {
                    zis.closeEntry();
                    continue;
                }

                if (name.startsWith("/")) {
                    name = name.substring(1);
                }

                int slashIdx = name.indexOf('/');
                if (slashIdx > 0) {
                    String firstDir = name.substring(0, slashIdx);
                    if (candidate == null) {
                        candidate = firstDir;
                    } else if (!candidate.equals(firstDir)) {
                        log.printf(LOG_PREFIX + "Multiple top-level directories found: '%s' and '%s'%n",
                                candidate, firstDir);
                        return null;
                    }
                } else if (!name.toLowerCase().endsWith(ZIP_FILE_SUFFIX)) {
                    rootItems.add(name);
                }

                zis.closeEntry();
            }
        }

        if (!rootItems.isEmpty()) {
            log.printf(LOG_PREFIX + "Archive contains items in root directory: %s%n", rootItems);
            return null;
        }

        if (candidate != null) {
            log.printf(LOG_PREFIX + "Common top-level directory found: '%s'%n", candidate);
            return candidate;
        }

        log.println(LOG_PREFIX + "No common top-level directory detected");
        return null;
    }
}
