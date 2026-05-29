package hudson.plugins.EndpointS3SCM.checkout;

import hudson.AbortException;
import hudson.plugins.EndpointS3SCM.s3.S3Service;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static hudson.plugins.EndpointS3SCM.EndpointS3SCMConstants.LOG_PREFIX;

/**
 * Verifies the integrity of a downloaded S3 archive using a companion checksum file.
 *
 * <p>For a given S3 key (e.g. {@code releases/archive.zip}) the verifier looks for:
 * <ol>
 *   <li>{@code releases/archive.zip.sha512}</li>
 *   <li>{@code releases/archive.zip.sha256}</li>
 * </ol>
 * SHA-512 is preferred over SHA-256 when both are present. At least one must exist;
 * if neither is found an {@link AbortException} is thrown.</p>
 *
 * <p>The checksum file content is parsed flexibly:
 * <ul>
 *   <li>Raw hex hash: {@code abc123...}</li>
 *   <li>GNU format: {@code abc123...  filename.zip}</li>
 *   <li>BSD format: {@code SHA256 (filename.zip) = abc123...}</li>
 * </ul>
 * </p>
 */
public class ChecksumVerifier {

    private static final Algorithm[] ALGORITHMS = {
            new Algorithm("sha512", "SHA-512"),
            new Algorithm("sha256", "SHA-256")
    };

    /**
     * Verifies the downloaded archive against its companion checksum file in S3.
     *
     * @param s3        live S3 client (caller owns the lifecycle)
     * @param s3Service S3 service used to fetch the checksum file
     * @param bucket    S3 bucket
     * @param zipKey    S3 key of the downloaded archive
     * @param zipFile   path to the locally downloaded archive
     * @param log       build logger
     * @throws AbortException if no checksum file is found, content is invalid, or hash mismatches
     * @throws IOException    if reading the local file fails
     */
    public void verify(S3Client s3,
                       S3Service s3Service,
                       String bucket,
                       String zipKey,
                       Path zipFile,
                       PrintStream log) throws AbortException, IOException {

        log.printf(LOG_PREFIX + "Verifying checksum for '%s'...%n", zipKey);

        for (Algorithm algo : ALGORITHMS) {
            String checksumKey = zipKey + "." + algo.extension();

            // Returns null only on HTTP 404 (NoSuchKeyException); other S3 errors propagate.
            String content = s3Service.tryGetObjectContent(s3, bucket, checksumKey);

            if (content == null) {
                continue; // file not found, try next algorithm
            }

            log.printf(LOG_PREFIX + "Found checksum file: %s%n", checksumKey);

            String expected = parseHash(content); // throws AbortException if blank or unparseable

            if (!isHex(expected) || expected.length() != expectedHashLength(algo.javaName())) {
                throw new AbortException(String.format(
                        LOG_PREFIX + "Invalid checksum in '%s': expected %d hex chars for %s, got '%s'",
                        checksumKey, expectedHashLength(algo.javaName()), algo.extension().toUpperCase(),
                        expected.length() > 20 ? expected.substring(0, 20) + "…" : expected));
            }

            String actual = computeHash(zipFile, algo.javaName());

            if (!expected.equalsIgnoreCase(actual)) {
                throw new AbortException(String.format(
                        LOG_PREFIX + "Checksum verification FAILED for '%s'!%n"
                                + "  Algorithm : %s%n"
                                + "  Expected  : %s%n"
                                + "  Actual    : %s",
                        zipKey, algo.extension().toUpperCase(), expected, actual));
            }

            log.printf(LOG_PREFIX + "Checksum OK (%s): %s%n", algo.extension().toUpperCase(), actual);
            return;
        }

        throw new AbortException(String.format(
                LOG_PREFIX + "No checksum file found for '%s'. Tried: .sha512, .sha256", zipKey));
    }

    /**
     * Extracts the hex hash string from a checksum file's content.
     *
     * <p>Handles raw hash, GNU ({@code hash  file}), and BSD ({@code ALGO (file) = hash}) formats.
     * Throws {@link AbortException} if the content is blank.</p>
     *
     * @throws AbortException if {@code content} is null or blank
     */
    String parseHash(String content) throws AbortException {
        if (content == null || content.isBlank()) {
            throw new AbortException(LOG_PREFIX + "Checksum file is empty or contains only whitespace");
        }

        String line = content.trim().split("\\r?\\n")[0].trim();

        // BSD format: "SHA256 (filename) = abc123"
        int eqIdx = line.lastIndexOf('=');
        if (eqIdx >= 0 && eqIdx < line.length() - 1) {
            String candidate = line.substring(eqIdx + 1).trim();
            if (isHex(candidate)) {
                return candidate;
            }
        }

        // GNU format or raw hash: take first whitespace-separated token
        return line.split("\\s+")[0].trim();
    }

    private int expectedHashLength(String algorithm) {
        return switch (algorithm) {
            case "SHA-512" -> 128;
            case "SHA-256" -> 64;
            default -> throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        };
    }

    private boolean isHex(String s) {
        return s != null && !s.isEmpty() && s.matches("[0-9a-fA-F]+");
    }

    private String computeHash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536];
            try (InputStream is = Files.newInputStream(file)) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Hash algorithm not available: " + algorithm, e);
        }
    }

    private record Algorithm(String extension, String javaName) {
    }
}
