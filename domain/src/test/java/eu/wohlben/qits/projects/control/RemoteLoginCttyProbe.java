package eu.wohlben.qits.projects.control;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone reproduction of the production incident, run <b>as a session leader</b>.
 *
 * <p>It exists as a {@code main} rather than a test method because the bug it guards only bites a
 * session leader, and the JVM running the suite is not one — a test method here would pass whether
 * or not the bug was present. {@link RemoteLoginCttyTest} launches this class under {@code setsid},
 * which is the cheapest honest way to be PID-1-like without a container.
 *
 * <p>It allocates a real {@link ForeignPty}, builds the child exactly as {@link
 * RemoteLoginSessions#terminalProcess} does, and reports two things: whether <em>this</em> process
 * ended up owning the terminal, and how many SIGHUPs it was sent when the master was closed. Both
 * must be zero.
 */
public final class RemoteLoginCttyProbe {

  private RemoteLoginCttyProbe() {}

  /** Field 7 of {@code /proc/self/stat} — the controlling terminal, 0 for none. */
  private static int controllingTerminal() throws Exception {
    return Integer.parseInt(Files.readString(Path.of("/proc/self/stat")).split(" ")[6]);
  }

  public static void main(String[] args) throws Exception {
    AtomicInteger hangups = new AtomicInteger();
    sun.misc.Signal.handle(new sun.misc.Signal("HUP"), signal -> hangups.incrementAndGet());

    long pid = ProcessHandle.current().pid();
    long sid = Long.parseLong(Files.readString(Path.of("/proc/self/stat")).split(" ")[5]);
    System.out.println("sessionLeader=" + (pid == sid));

    ForeignPty pty = ForeignPty.open(80, 24);
    // `sleep` stands in for git: what matters is which process opens the slave, not what runs on it.
    Process child =
        RemoteLoginSessions.terminalProcess(pty.slavePath(), new String[] {"sleep", "30"}).start();
    Thread.sleep(500);

    System.out.println("serviceControllingTerminal=" + controllingTerminal());
    System.out.println("childControllingTerminal=" + childControllingTerminal(child));

    // The teardown that fired the incident: closing the master hangs the terminal up.
    pty.close();
    child.destroy();
    child.waitFor();
    Thread.sleep(500);
    System.out.println("hangupsDelivered=" + hangups.get());
  }

  /**
   * The terminal the child ended up on. It must be non-zero: git only prompts for credentials when
   * it has a <em>controlling</em> terminal, so a fix that merely stopped the parent taking one and
   * left the child without it would break the whole feature while passing the incident's test.
   */
  private static String childControllingTerminal(Process child) {
    // sh and setsid both exec, so the pid this returns is the one running on the terminal.
    try {
      return Files.readString(Path.of("/proc/" + child.pid() + "/stat")).split(" ")[6];
    } catch (Exception e) {
      return "unknown";
    }
  }
}
