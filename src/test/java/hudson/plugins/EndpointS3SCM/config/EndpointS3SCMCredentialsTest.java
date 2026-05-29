package hudson.plugins.EndpointS3SCM.config;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.AbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@DisplayName("EndpointS3SCMConfigValidator — credentials")
public class EndpointS3SCMCredentialsTest {

    private final EndpointS3SCMConfigValidator validator = new EndpointS3SCMConfigValidator();

    private UsernamePasswordCredentialsImpl creds(String username, String password) {
        return new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM,
                "id",
                "desc",
                username,
                password
        );
    }

    @Test
    @DisplayName("validateCredentials: non-blank username and password passes without exception")
    public void validateCredentials_ok() throws Exception {
        validator.validateCredentials(creds("accessKey", "secretKey"));
    }

    @Test
    @DisplayName("validateCredentials: empty username throws AbortException with 'empty username'")
    public void validateCredentials_emptyUsername() {
        assertAbort(creds("", "secretKey"), "empty username");
    }

    @Test
    @DisplayName("validateCredentials: empty password throws AbortException with 'empty password'")
    public void validateCredentials_emptyPassword() {
        assertAbort(creds("accessKey", ""), "empty password");
    }

    private void assertAbort(UsernamePasswordCredentialsImpl creds, String expectedContent) {
        try {
            validator.validateCredentials(creds);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains(expectedContent));
        }
    }
}
