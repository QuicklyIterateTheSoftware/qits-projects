package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The host-side PTY session behind the sign-in terminal, against plain shell stand-ins (no git, no
 * docker): banner+scrollback replay on attach, stdin round-trip through the PTY, the end-listener
 * on natural exit (and immediately for a late attach), and {@code terminate()} killing a lingering
 * process.
 *
 * <p>The stand-in is spawned exactly the way {@link RemoteLoginSessions} spawns git — a {@link
 * ForeignPty} plus a {@code setsid --ctty} child on its slave device — so what this test drives is
 * the real machinery and not a pipe pair standing in for it. Linux-only, because {@link ForeignPty}
 * is.
 */
@EnabledOnOs(OS.LINUX)
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

  /** A terminal plus a shell running {@code script} on it, as its session and controlling tty. */
  private record Terminal(ForeignPty pty, Process process) {}

  private static Terminal terminal(String script) throws IOException {
    ForeignPty pty = ForeignPty.open(80, 24);
    File slave = new File(pty.slavePath());
    Process process =
        new ProcessBuilder("setsid", "--ctty", "sh", "-c", script)
            .redirectInput(slave)
            .redirectOutput(slave)
            .redirectError(slave)
            .start();
    return new Terminal(pty, process);
  }

  private static RemoteLoginSession session(String repoId, String script, IntConsumer onFinished)
      throws IOException {
    Terminal terminal = terminal(script);
    return new RemoteLoginSession(repoId, terminal.process(), terminal.pty(), onFinished);
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
        session(
            "repo-1",
            "echo intro; read line; echo \"got:$line\"",
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
  void aPromptOnDevTtyRoundTripsTheWayGitCredentialsDo() throws Exception {
    // git does not read credentials from stdin, it opens /dev/tty — which only resolves for a
    // process that has a CONTROLLING terminal. This is the property pty4j used to supply and that
    // ForeignPty + `setsid --ctty` now has to: a prompt written to /dev/tty reaches the attached
    // client, and what the client types is what the read on /dev/tty returns.
    CountDownLatch ended = new CountDownLatch(1);
    RemoteLoginSession session =
        session(
            "repo-3",
            "printf 'Username: ' > /dev/tty; read -r user < /dev/tty; echo \"signed-in:$user\"",
            code -> ended.countDown());

    CapturingSink sink = new CapturingSink();
    session.attach(sink, code -> {});
    session.startReader();

    awaitContains(sink, "Username: ");
    session.input("octocat\n".getBytes(StandardCharsets.UTF_8));
    awaitContains(sink, "signed-in:octocat");
    assertTrue(ended.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS), "end listener fired");
  }

  @Test
  void terminateKillsALingeringProcess() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    AtomicInteger exitCode = new AtomicInteger(0);
    RemoteLoginSession session =
        session(
            "repo-2",
            "sleep 30",
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
