package hudson.plugins.EndpointS3SCM;

import hudson.AbortException;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EndpointS3SCMValidateConfigTest {

    private void invokeValidateConfig(EndpointS3SCM scm) throws Exception {
        Method m = EndpointS3SCM.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        try {
            m.invoke(scm);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    @Test
    public void validateConfig_ok() throws Exception {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key");
        scm.setCredentialsId("creds");
        invokeValidateConfig(scm);
    }

    @Test
    public void validateConfig_emptyEndpoint() {
        EndpointS3SCM scm = new EndpointS3SCM("   ", "bucket", "key");
        scm.setCredentialsId("creds");

        try {
            invokeValidateConfig(scm);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Endpoint is required"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void validateConfig_invalidEndpoint() {
        EndpointS3SCM scm = new EndpointS3SCM("not a url", "bucket", "key");
        scm.setCredentialsId("creds");

        try {
            invokeValidateConfig(scm);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Invalid endpoint URL"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void validateConfig_emptyBucket() {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", " ", "key");
        scm.setCredentialsId("creds");

        try {
            invokeValidateConfig(scm);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Bucket is required"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void validateConfig_emptyKey() {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", " ");
        scm.setCredentialsId("creds");

        try {
            invokeValidateConfig(scm);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Key is required"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }

    @Test
    public void validateConfig_emptyCredentialsId() {
        EndpointS3SCM scm = new EndpointS3SCM("http://example.com", "bucket", "key");
        try {
            invokeValidateConfig(scm);
            fail("Expected AbortException");
        } catch (AbortException e) {
            assertTrue(e.getMessage().contains("Credentials are required"));
        } catch (Exception e) {
            fail("Unexpected exception type: " + e);
        }
    }
}