package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The regression guard for the incident that took the live container down three times: the service
 * adopting the sign-in terminal as its own controlling terminal, and then being hung up by its own
 * teardown.
 *
 * <p>Two tests, because one alone would not have caught it.
 *
 * <p>{@link #theServiceNeverOpensTheSlaveItself} asserts the <b>code path</b>. It is the one that
 * runs everywhere, and it is the honest one: {@code ProcessBuilder}'s file redirects are performed
 * by the calling process, so handing it the slave device <em>is</em> the bug, whatever the symptom
 * looks like on a given host.
 *
 * <p>{@link #aSessionLeaderIsNeverHungUpByItsOwnTerminal} asserts the <b>symptom</b>, and has to
 * launch a second JVM under {@code setsid} to do it — the bug only bites a session leader, and the
 * JVM running this suite is not one. That is exactly why the whole suite stayed green while
 * production died: without the {@code setsid}, this test passes on the broken code too.
 */
@EnabledOnOs(OS.LINUX)
class RemoteLoginCttyTest {

  /** The sign-in push, standing in for whatever git argv the credential helper builds. */
  private static final String[] GIT_ARGV = {
    "git", "-c", "credential.helper=store --file=/data/git-credentials", "push", "https://forge/x.git"
  };

  @Test
  void theServiceNeverOpensTheSlaveItself() {
    String slave = "/dev/pts/7";

    ProcessBuilder builder = RemoteLoginSessions.terminalProcess(slave, GIT_ARGV);

    // The redirects are what the PARENT opens. None of them may be the terminal: opening a pts from
    // a session leader with no controlling terminal silently makes it the controlling terminal, and
    // the service is a session leader (PID 1 in its container).
    for (ProcessBuilder.Redirect redirect :
        List.of(builder.redirectInput(), builder.redirectOutput(), builder.redirectError())) {
      File file = redirect.file();
      assertNotEquals(
          slave,
          file == null ? null : file.getPath(),
          "the parent must never open the terminal — this is the incident, exactly");
      assertTrue(
          file == null || file.getPath().equals("/dev/null"),
          "the parent's stdio may only go somewhere that can never become a terminal, got: " + file);
    }
  }

  @Test
  void theChildOpensTheTerminalAndClaimsItForItsOwnSession() {
    String slave = "/dev/pts/7";

    List<String> argv = RemoteLoginSessions.terminalProcess(slave, GIT_ARGV).command();

    // The open moved into the child, where a non-session-leader doing it is harmless...
    assertEquals(List.of("sh", "-c"), argv.subList(0, 2));
    assertTrue(argv.get(2).contains("exec 0<>\"$0\""), "the child opens the device: " + argv.get(2));
    assertEquals(slave, argv.get(3), "as $0");
    // ...and `setsid --ctty` is what then claims it deliberately, for the child's OWN new session,
    // which is what makes git prompt at all.
    assertEquals(List.of("setsid", "--ctty"), argv.subList(4, 6));
    assertEquals(List.of(GIT_ARGV), argv.subList(6, argv.size()), "and git runs on it, unchanged");
    // `exec` twice over, so no shell lingers: the pid ProcessBuilder returns is still git's, which
    // is what keeps destroy() aimed at the process that is actually holding the terminal.
    assertEquals(2, argv.get(2).split("exec ", -1).length - 1, "exec through: " + argv.get(2));
  }

  /**
   * The incident itself, reproduced and then disproved. Runs the probe under {@code setsid} so it is
   * a session leader, exactly as the binary is when it runs as PID 1 with an exec-form entrypoint.
   */
  @Test
  void aSessionLeaderIsNeverHungUpByItsOwnTerminal() throws Exception {
    Map<String, String> report = runProbeAsSessionLeader();

    assertEquals("true", report.get("sessionLeader"), "the probe must be what production is");
    assertEquals(
        "0",
        report.get("serviceControllingTerminal"),
        "the service adopted the sign-in terminal — closing it will hang the service up");
    assertNotEquals(
        "0",
        report.get("childControllingTerminal"),
        "the child must still own the terminal, or git has nothing to prompt on");
    assertEquals(
        "0",
        report.get("hangupsDelivered"),
        "closing the terminal sent SIGHUP to the service itself, which Quarkus stops on");
  }

  /** Runs {@link RemoteLoginCttyProbe} in its own session and parses its {@code key=value} report. */
  private static Map<String, String> runProbeAsSessionLeader() throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            "setsid",
            System.getProperty("java.home") + "/bin/java",
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            RemoteLoginCttyProbe.class.getName());
    builder.redirectInput(new File("/dev/null")).redirectErrorStream(true);
    Process probe = builder.start();
    String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(probe.waitFor(60, TimeUnit.SECONDS), "the probe did not finish: " + output);
    assertEquals(0, probe.exitValue(), "the probe failed: " + output);
    return output
        .lines()
        .filter(line -> line.contains("="))
        .collect(
            Collectors.toMap(
                line -> line.substring(0, line.indexOf('=')).trim(),
                line -> line.substring(line.indexOf('=') + 1).trim(),
                (a, b) -> b));
  }
}
