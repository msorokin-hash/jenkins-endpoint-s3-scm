package hudson.plugins.EndpointS3SCM;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.model.TaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ZipServiceTest {

    private final ZipService service = new ZipService();
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testBasicExtractionWorks() throws Exception {
        File zipFile = tempFolder.newFile("test.zip");
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("Hello, World!".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        File workspaceDir = tempFolder.newFolder("workspace");
        FilePath workspace = new FilePath(workspaceDir);

        service.extractZipSecurely(zipFile.toPath(), workspace, new SimpleListener(), false);

        File extractedFile = new File(workspaceDir, "hello.txt");
        assertTrue("file should be extracted", extractedFile.exists());

        String content = Files.readString(extractedFile.toPath());
        assertEquals("Hello, World!", content);
    }

    @Test
    public void testDirectoriesAreCreated() throws Exception {
        File zipFile = tempFolder.newFile("test.zip");
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("folder/subfolder/file.txt"));
            zos.write("Content".getBytes());
            zos.closeEntry();
        }

        File workspaceDir = tempFolder.newFolder("workspace");
        FilePath workspace = new FilePath(workspaceDir);

        service.extractZipSecurely(zipFile.toPath(), workspace, new SimpleListener(), false);
        File folder = new File(workspaceDir, "folder");
        File subfolder = new File(folder, "subfolder");
        File file = new File(subfolder, "file.txt");

        assertTrue(folder.exists());
        assertTrue(folder.isDirectory());
        assertTrue(subfolder.exists());
        assertTrue(subfolder.isDirectory());
        assertTrue(file.exists());
    }

    @Test
    public void testMultipleFilesExtracted() throws Exception {
        File zipFile = tempFolder.newFile("test.zip");
        try (FileOutputStream fos = new FileOutputStream(zipFile);
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

        File workspaceDir = tempFolder.newFolder("workspace");
        FilePath workspace = new FilePath(workspaceDir);

        service.extractZipSecurely(zipFile.toPath(), workspace, new SimpleListener(), false);
        assertTrue(new File(workspaceDir, "file1.txt").exists());
        assertTrue(new File(workspaceDir, "file2.txt").exists());
        assertTrue(new File(workspaceDir, "file3.txt").exists());

        assertEquals("First", Files.readString(new File(workspaceDir, "file1.txt").toPath()));
        assertEquals("Second", Files.readString(new File(workspaceDir, "file2.txt").toPath()));
        assertEquals("Third", Files.readString(new File(workspaceDir, "file3.txt").toPath()));
    }

    @Test
    public void testStripTopLevelDir() throws Exception {
        File zipFile = tempFolder.newFile("test.zip");
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            zos.putNextEntry(new ZipEntry("commonDir/file1.txt"));
            zos.write("File 1".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("commonDir/file2.txt"));
            zos.write("File 2".getBytes());
            zos.closeEntry();
        }

        File workspaceDir = tempFolder.newFolder("workspace");
        FilePath workspace = new FilePath(workspaceDir);

        service.extractZipSecurely(zipFile.toPath(), workspace, new SimpleListener(), true);

        assertTrue(new File(workspaceDir, "file1.txt").exists());
        assertTrue(new File(workspaceDir, "file2.txt").exists());
        assertFalse(new File(workspaceDir, "commonDir").exists());
    }

    private static class SimpleListener implements TaskListener {
        @NonNull
        @Override
        public PrintStream getLogger() {
            return System.out;
        }
    }
}