package hudson.plugins.EndpointS3SCM.support;

import hudson.EnvVars;
import hudson.model.Action;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FakeBuild extends Run<FakeJob, FakeBuild> {

    private final List<Action> actions = new ArrayList<>();

    public FakeBuild() throws IOException {
        super(new FakeJob());
    }

    @Override
    public void addAction(Action a) {
        actions.add(a);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Action> T getAction(Class<T> type) {
        return actions.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    @Override
    public EnvVars getEnvironment(TaskListener listener) {
        return new EnvVars();
    }
}
