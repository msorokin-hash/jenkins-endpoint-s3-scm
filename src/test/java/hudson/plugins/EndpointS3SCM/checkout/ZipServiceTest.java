package hudson.plugins.EndpointS3SCM.checkout;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.model.TaskListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@DisplayName("ZipService")
public class ZipServiceTest {

    private final ZipService service = new ZipService();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("extractZipSecurely: single-file ZIP is extracted and content is correct")
    public void testBasicExtractionWorks() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("test.zip"));
        try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("Hello, World!".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path workspaceDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(workspaceDir.toFile());

        service.extractZipSecurely(zipFile, workspace, new SimpleListener(), false);

        Path extractedFile = workspaceDir.resolve("hello.txt");
        assertTrue("file should be extracted", extractedFile.toFile().exists());
        assertEquals("Hello, World!", Files.readString(extractedFile));
    }

    @Test
    @DisplayName("extractZipSecurely: nested directories in ZIP entry are created during extraction")
    public void testDirectoriesAreCreated() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("test.zip"));
        try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("folder/subfolder/file.txt"));
            zos.write("Content".getBytes());
            zos.closeEntry();
        }

        Path workspaceDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(workspaceDir.toFile());

        service.extractZipSecurely(zipFile, workspace, new SimpleListener(), false);

        Path folder = workspaceDir.resolve("folder");
        Path subfolder = folder.resolve("subfolder");
        Path file = subfolder.resolve("file.txt");

        assertTrue(folder.toFile().exists());
        assertTrue(folder.toFile().isDirectory());
        assertTrue(subfolder.toFile().exists());
        assertTrue(subfolder.toFile().isDirectory());
        assertTrue(file.toFile().exists());
    }

    @Test
    @DisplayName("extractZipSecurely: all files in a multi-file ZIP are extracted with correct content")
    public void testMultipleFilesExtracted() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("test.zip"));
        try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("file1.txt"));
            zos.write("First".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("file2.txt"));
            zos.write("Second".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("file3.txt"));
            zos.write("Third".getBytes());
            zos.closeEntry();
        }

        Path workspaceDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(workspaceDir.toFile());

        service.extractZipSecurely(zipFile, workspace, new SimpleListener(), false);

        assertTrue(workspaceDir.resolve("file1.txt").toFile().exists());
        assertTrue(workspaceDir.resolve("file2.txt").toFile().exists());
        assertTrue(workspaceDir.resolve("file3.txt").toFile().exists());

        assertEquals("First", Files.readString(workspaceDir.resolve("file1.txt")));
        assertEquals("Second", Files.readString(workspaceDir.resolve("file2.txt")));
        assertEquals("Third", Files.readString(workspaceDir.resolve("file3.txt")));
    }

    @Test
    @DisplayName("extractZipSecurely: stripTopLevelDir=true removes the common root directory from all paths")
    public void testStripTopLevelDir() throws Exception {
        Path zipFile = Files.createFile(tempDir.resolve("test.zip"));
        try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("commonDir/file1.txt"));
            zos.write("File 1".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("commonDir/file2.txt"));
            zos.write("File 2".getBytes());
            zos.closeEntry();
        }

        Path workspaceDir = Files.createDirectory(tempDir.resolve("workspace"));
        FilePath workspace = new FilePath(workspaceDir.toFile());

        service.extractZipSecurely(zipFile, workspace, new SimpleListener(), true);

        assertTrue(workspaceDir.resolve("file1.txt").toFile().exists());
        assertTrue(workspaceDir.resolve("file2.txt").toFile().exists());
        assertFalse(workspaceDir.resolve("commonDir").toFile().exists());
    }

    private static class SimpleListener implements TaskListener {
        @NonNull
        @Override
        public PrintStream getLogger() {
            return System.out;
        }
    }
}
