package eu.wohlben.qits.projects.startup;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;
import sun.misc.Signal;

/**
 * Makes SIGHUP a log line instead of a shutdown.
 *
 * <p>Quarkus registers one handler for {@code INT}, {@code TERM} <b>and {@code HUP}</b>, all three
 * meaning "stop gracefully". For most services that is a reasonable reading — a hangup traditionally
 * means the terminal that owned you went away. This service is not most services in two ways that
 * turn it into a hazard:
 *
 * <ul>
 *   <li>It runs as <b>PID 1</b> in its container, with an exec-form entrypoint and no init. A
 *       process with no parent shell has no terminal to lose, so a SIGHUP arriving here is never
 *       somebody's session ending. (Nothing else sends it: an orchestrator stopping a container
 *       sends TERM, then KILL.)
 *   <li>Its business is <b>host-side pseudo-terminals</b>. Allocating and tearing down PTYs is
 *       exactly the activity that makes a stray SIGHUP structurally possible — the kernel sends one
 *       whenever a terminal hangs up, and getting the ownership of those terminals subtly wrong
 *       aims it at this process. That is not hypothetical: it happened, it took the container down
 *       three times, and {@code RemoteLoginSessions.terminalProcess} carries the story.
 * </ul>
 *
 * <p>So the honest handling is to refuse it. Between "a signal nobody legitimately sends us" and "a
 * graceful shutdown of the whole platform's project service", ignoring is the safe default and the
 * loud log line is what makes the underlying mistake findable rather than silent. {@code TERM} and
 * {@code INT} keep Quarkus' handler untouched, so every real stop path is unchanged.
 *
 * <p>Registered on {@link StartupEvent}, which fires after {@code ApplicationLifecycleManager} has
 * installed its own hooks — so this one replaces it rather than being replaced by it. {@code
 * sun.misc.Signal} is the same mechanism Quarkus itself uses, which is what makes it work in the
 * native image: the class is already reachable and Substrate already supports the runtime
 * registration, so no reachability metadata of our own is needed.
 */
@ApplicationScoped
public class HangupImmunity {

  private static final Logger LOG = Logger.getLogger(HangupImmunity.class);

  /** How many hangups have been refused — the fact that makes a recurrence visible in a test. */
  private volatile int refused;

  void onStart(@Observes StartupEvent event) {
    install();
  }

  /** Package-private so a test can install the handler without booting an application. */
  void install() {
    try {
      Signal.handle(new Signal("HUP"), signal -> refuse());
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      // A platform that will not let us take SIGHUP (or has none) is not a reason to fail a boot:
      // the service is no worse off than before this class existed.
      LOG.warnf("Could not take over SIGHUP (%s); the default handling stands.", e.getMessage());
    }
  }

  private void refuse() {
    refused++;
    LOG.warnf(
        "Ignoring SIGHUP (#%d). Nothing legitimately hangs this service up — it is PID 1 in its"
            + " container and has no terminal to lose — so this almost certainly means a"
            + " pseudo-terminal was hung up while this process still owned it. Look for a pts"
            + " opened by the service rather than by the child that runs on it.",
        refused);
  }

  /** See {@link #refused}. */
  int refusedCount() {
    return refused;
  }
}
