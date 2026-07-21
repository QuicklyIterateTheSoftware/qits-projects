package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The host-side PTY session behind the sign-in terminal, against plain shell stand-ins (no git, no
 * docker): banner+scrollback replay on attach, stdin round-trip through the PTY, the end-listener
 * on natural exit (and immediately for a late attach), and {@code terminate()} killing a lingering
 * process. pty4j runs the child as a session leader on a real PTY — the same machinery the
 * interactive push uses.
 */
class RemoteLoginSessionTest {

  private static final long AWAIT_MILLIS = 15_000;

  /** Collects everything the session writes; always open. */
  private static final class CapturingSink implements CommandOutputSink {
    final StringBuilder received = new StringBuilder();

    @Override
    public synchronized void write(String data) {
      received.append(data);
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    synchronized String text() {
      return received.toString();
    }
  }

  private static PtyProcess pty(String script) throws Exception {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("TERM", "xterm-256color");
    return new PtyProcessBuilder()
        .setCommand(new String[] {"sh", "-c", script})
        .setEnvironment(env)
        .setInitialColumns(80)
        .setInitialRows(24)
        .start();
  }

  private static void awaitContains(CapturingSink sink, String needle) throws Exception {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!sink.text().contains(needle) && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(sink.text().contains(needle), "expected '" + needle + "' in: " + sink.text());
  }

  @Test
  void attachReplaysTheBannerInputRoundTripsAndExitFiresTheEndListener() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(-99);
    RemoteLoginSession session =
        new RemoteLoginSession(
            "repo-1",
            pty("echo intro; read line; echo \"got:$line\""),
            code -> {
              exitCode.set(code);
              ended.countDown();
            });
    session.seedBanner("Sign in to example\r\n");

    CapturingSink sink = new CapturingSink();
    session.attach(sink, code -> {});
    session.startReader();

    // The banner (seeded pre-spawn) replays first, then the live intro line arrives.
    awaitContains(sink, "Sign in to example");
    awaitContains(sink, "intro");

    // Keystrokes reach the shell's `read` through the PTY; the echo round-trips back.
    session.input("hello\n".getBytes(StandardCharsets.UTF_8));
    awaitContains(sink, "got:hello");

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "end listener fired");
    assertEquals(0, exitCode.get());

    // A late attach replays the full ring and fires its end listener immediately.
    CapturingSink late = new CapturingSink();
    CountDownLatch lateEnded = new CountDownLatch(1);
    session.attach(late, code -> lateEnded.countDown());
    assertTrue(lateEnded.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS));
    assertTrue(late.text().contains("Sign in to example"), late.text());
    assertTrue(late.text().contains("got:hello"), late.text());
  }

  @Test
  void terminateKillsALingeringProcess() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(0);
    RemoteLoginSession session =
        new RemoteLoginSession(
            "repo-2",
            pty("sleep 30"),
            code -> {
              exitCode.set(code);
              ended.countDown();
            });
    session.startReader();
    assertTrue(session.isAlive());

    session.terminate();

    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "terminate ended the session");
    assertNotEquals(0, exitCode.get(), "a killed process exits non-zero");
  }
}
