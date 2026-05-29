package hudson.plugins.EndpointS3SCM.checkout;

import hudson.AbortException;
import hudson.plugins.EndpointS3SCM.support.BaseTest;
import hudson.plugins.EndpointS3SCM.support.FakeS3Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.Assert.*;

@DisplayName("ChecksumVerifier")
public class ChecksumVerifierTest extends BaseTest {

    private final ChecksumVerifier verifier = new ChecksumVerifier();
    private final PrintStream log = System.out;

    @TempDir
    Path tempDir;

    private static String computeHash(Path file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] data = Files.readAllBytes(file);
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("parseHash: raw 64-char hex string is returned unchanged")
    public void parseHash_rawHex() throws AbortException {
        String hash = "a".repeat(64);
        assertEquals(hash, verifier.parseHash(hash));
    }

    @Test
    @DisplayName("parseHash: GNU format 'hash  filename' — only the hash token is returned")
    public void parseHash_gnuFormat() throws AbortException {
        String hash = "b".repeat(128);
        assertEquals(hash, verifier.parseHash(hash + "  archive.zip"));
    }

    @Test
    @DisplayName("parseHash: BSD format 'ALGO (file) = hash' — value after '=' is extracted")
    public void parseHash_bsdFormat() throws AbortException {
        String hash = "c".repeat(64);
        assertEquals(hash, verifier.parseHash("SHA256 (archive.zip) = " + hash));
    }

    @Test
    @DisplayName("parseHash: BSD format with trailing newline — result is trimmed correctly")
    public void parseHash_bsdFormatWithNewline() throws AbortException {
        String hash = "d".repeat(128);
        assertEquals(hash, verifier.parseHash("SHA512 (archive.zip) = " + hash + "\n"));
    }

    @Test
    @DisplayName("parseHash: GNU format with Windows CRLF line ending is handled")
    public void parseHash_gnuFormatWithWindowsNewline() throws AbortException {
        String hash = "e".repeat(64);
        assertEquals(hash, verifier.parseHash(hash + "  archive.zip\r\n"));
    }

    @Test
    @DisplayName("parseHash: blank / whitespace-only content throws AbortException")
    public void parseHash_emptyContent_throwsAbortException() {
        try {
            verifier.parseHash("   ");
            fail("Expected AbortException for blank content");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    @DisplayName("parseHash: null content throws AbortException")
    public void parseHash_nullContent_throwsAbortException() {
        try {
            verifier.parseHash(null);
            fail("Expected AbortException for null content");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    @DisplayName("verify: SHA-256 companion file with raw hex hash passes verification")
    public void verify_sha256_rawHash_passes() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("app.zip"));
        Files.writeString(zipFile, "hello world");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/app.zip.sha256", computeHash(zipFile, "SHA-256"));

        verifier.verify(null, s3, "bucket", "releases/app.zip", zipFile, log);
    }

    @Test
    @DisplayName("verify: SHA-512 companion file with raw hex hash passes verification")
    public void verify_sha512_rawHash_passes() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("app512.zip"));
        Files.writeString(zipFile, "hello world");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/app512.zip.sha512", computeHash(zipFile, "SHA-512"));

        verifier.verify(null, s3, "bucket", "releases/app512.zip", zipFile, log);
    }

    @Test
    @DisplayName("verify: SHA-512 is tried before SHA-256 when both companion files exist")
    public void verify_sha512_preferredOverSha256() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("preferred.zip"));
        Files.writeString(zipFile, "data");

        String wrongSha256 = "0".repeat(64); // wrong — would fail if used

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/preferred.zip.sha512", computeHash(zipFile, "SHA-512"));
        s3.putContent("bucket", "releases/preferred.zip.sha256", wrongSha256);

        // must succeed because sha512 is tried first and is correct
        verifier.verify(null, s3, "bucket", "releases/preferred.zip", zipFile, log);
    }

    @Test
    @DisplayName("verify: GNU-format checksum file 'hash  filename' is accepted")
    public void verify_gnuFormat_passes() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("gnu.zip"));
        Files.writeString(zipFile, "gnu content");
        String hash = computeHash(zipFile, "SHA-256");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/gnu.zip.sha256", hash + "  gnu.zip");

        verifier.verify(null, s3, "bucket", "releases/gnu.zip", zipFile, log);
    }

    @Test
    @DisplayName("verify: BSD-format checksum file 'ALGO (file) = hash' is accepted")
    public void verify_bsdFormat_passes() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("bsd.zip"));
        Files.writeString(zipFile, "bsd content");
        String hash = computeHash(zipFile, "SHA-256");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/bsd.zip.sha256", "SHA256 (bsd.zip) = " + hash);

        verifier.verify(null, s3, "bucket", "releases/bsd.zip", zipFile, log);
    }

    @Test
    @DisplayName("verify: hash mismatch throws AbortException with 'FAILED' in message")
    public void verify_hashMismatch_throwsAbortException() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("mismatch.zip"));
        Files.writeString(zipFile, "real content");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/mismatch.zip.sha256", "0".repeat(64)); // wrong hash

        try {
            verifier.verify(null, s3, "bucket", "releases/mismatch.zip", zipFile, log);
            fail("Expected AbortException for hash mismatch");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Checksum verification FAILED"));
        }
    }

    @Test
    @DisplayName("verify: neither .sha512 nor .sha256 companion file exists — throws AbortException")
    public void verify_noChecksumFile_throwsAbortException() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("nochecksum.zip"));
        Files.writeString(zipFile, "content");

        FakeS3Service s3 = new FakeS3Service(); // no putContent → both keys return null

        try {
            verifier.verify(null, s3, "bucket", "releases/nochecksum.zip", zipFile, log);
            fail("Expected AbortException when no checksum file exists");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("No checksum file found"));
        }
    }

    @Test
    @DisplayName("verify: companion file exists but contains only whitespace — throws AbortException")
    public void verify_emptyChecksumFile_throwsAbortException() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("empty-hash.zip"));
        Files.writeString(zipFile, "content");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/empty-hash.zip.sha256", "   ");

        try {
            verifier.verify(null, s3, "bucket", "releases/empty-hash.zip", zipFile, log);
            fail("Expected AbortException for empty checksum file");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    @DisplayName("verify: hash with wrong character count (expected 64 for SHA-256) throws AbortException")
    public void verify_wrongLengthHash_throwsAbortException() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("badlen.zip"));
        Files.writeString(zipFile, "content");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/badlen.zip.sha256", "a".repeat(40));

        try {
            verifier.verify(null, s3, "bucket", "releases/badlen.zip", zipFile, log);
            fail("Expected AbortException for wrong-length hash");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Invalid checksum"));
        }
    }

    @Test
    @DisplayName("verify: companion file content with non-hex characters throws AbortException")
    public void verify_nonHexContent_throwsAbortException() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("nonhex.zip"));
        Files.writeString(zipFile, "content");

        FakeS3Service s3 = new FakeS3Service();
        s3.putContent("bucket", "releases/nonhex.zip.sha256",
                "not-a-hex-value!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

        try {
            verifier.verify(null, s3, "bucket", "releases/nonhex.zip", zipFile, log);
            fail("Expected AbortException for non-hex hash");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Invalid checksum"));
        }
    }
}
