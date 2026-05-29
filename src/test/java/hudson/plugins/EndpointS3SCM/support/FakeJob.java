package hudson.plugins.EndpointS3SCM.support;

import hudson.model.Job;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

public class FakeJob extends Job<FakeJob, FakeBuild> {

    public FakeJob() {
        super(null, "fake-job");
    }

    @Override
    public synchronized int assignBuildNumber() {
        return 1;
    }

    @Override
    public File getRootDir() {
        try {
            return Files.createTempDirectory("fake-job-").toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isBuildable() {
        return false;
    }

    @Override
    protected SortedMap<Integer, ? extends FakeBuild> _getRuns() {
        return new TreeMap<>();
    }

    @Override
    protected void removeRun(FakeBuild run) {
        // no-op
    }

    @Override
    public Collection<? extends Job> getAllJobs() {
        return Collections.singletonList(this);
    }
}
