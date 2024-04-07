package se.kth.spork;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public class TestOutputHandler implements TestExecutionListener {
    private final PrintStream stdout = System.out;
    private final PrintStream stderr = System.err;

    private final ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream capturedStderr = new ByteArrayOutputStream();

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        System.setOut(new PrintStream(capturedStdout));
        System.setErr(new PrintStream(capturedStderr));
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        System.setOut(stdout);
        System.setErr(stderr);
        if (testExecutionResult.getStatus().equals(TestExecutionResult.Status.FAILED)) {
            System.out.printf("### OUTPUT FROM %s (%s) ###", testIdentifier.getLegacyReportingName(), testIdentifier.getDisplayName());
            System.out.println(capturedStdout.toString());
            System.out.println(capturedStderr.toString());
        }

        capturedStdout.reset();
        capturedStderr.reset();
    }
}
