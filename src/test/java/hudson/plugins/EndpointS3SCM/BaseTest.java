package hudson.plugins.EndpointS3SCM;

import org.junit.BeforeClass;

import java.nio.file.Files;
import java.nio.file.Paths;

public abstract class BaseTest {

    @BeforeClass
    public static void ensureTmpDirExists() throws Exception {
        Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir")));
    }
}
