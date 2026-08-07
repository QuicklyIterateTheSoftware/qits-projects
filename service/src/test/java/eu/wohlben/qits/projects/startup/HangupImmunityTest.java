package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The second half of the incident's fix: even if something does hang this process up, it must not
 * be a shutdown.
 *
 * <p>The first half — not adopting the sign-in terminal in the first place — is
 * {@code RemoteLoginCttyTest}. This one is the belt to that pair of braces, and it is worth having
 * separately: a service whose whole business is allocating host-side pseudo-terminals will always be
 * one ownership mistake away from a stray SIGHUP, and the cost of that mistake should be a log line
 * rather than the platform's project service stopping.
 *
 * <p>A real signal, delivered to this real JVM. Anything less would be asserting that a lambda was
 * registered, which is not the fact in question — the fact is that the process is still running
 * afterwards, and only {@code kill -HUP} can establish it.
 */
@EnabledOnOs(OS.LINUX)
class HangupImmunityTest {

  @Test
  void aHangupIsLoggedAndSurvivedRatherThanObeyed() throws Exception {
    HangupImmunity immunity = new HangupImmunity();
    immunity.install();
    assertEquals(0, immunity.refusedCount());

    hangUpThisProcess();

    // Signal delivery is asynchronous, so wait for the handler rather than assuming it has run.
    long deadline = System.currentTimeMillis() + 10_000;
    while (immunity.refusedCount() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }

    // Reaching this line at all is the assertion: on the shipped Quarkus handler this JVM would
    // have begun a graceful shutdown instead.
    assertEquals(1, immunity.refusedCount(), "the hangup reached the handler and was refused");
  }

  /** {@code kill -HUP} against our own pid — the signal the container kept dying on. */
  private static void hangUpThisProcess() throws Exception {
    Process kill =
        new ProcessBuilder("kill", "-HUP", String.valueOf(ProcessHandle.current().pid()))
            .redirectErrorStream(true)
            .start();
    assertTrue(kill.waitFor(10, TimeUnit.SECONDS), "kill did not finish");
    assertEquals(0, kill.exitValue(), "could not signal this process");
  }
}
