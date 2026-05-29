package hudson.plugins.EndpointS3SCM.support;

import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.EndpointS3SCM.checkout.ZipService;

import java.nio.file.Path;

public class FakeZipService extends ZipService {

    public int validateCount;
    public int extractCount;
    public boolean lastStripTopLevelDir;
    public FilePath lastExtractWorkspace;

    @Override
    public void validateZipFile(Path file, TaskListener listener) {
        validateCount++;
    }

    @Override
    public void extractZipSecurely(Path zipFile, FilePath workspace,
                                   TaskListener listener, boolean stripTopLevelDir) {
        extractCount++;
        lastStripTopLevelDir = stripTopLevelDir;
        lastExtractWorkspace = workspace;
    }
}
