package hudson.plugins.EndpointS3SCM.support;

import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Files;
import java.nio.file.Paths;

public abstract class BaseTest {

    @BeforeAll
    static void ensureTmpDirExists() throws Exception {
        Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir")));
    }
}
