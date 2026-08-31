package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseRequests;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The safety net under the event-driven release requests: re-evaluates every open request on a
 * schedule, which is what turns "qits-ci could not be asked", "the settle window was still open"
 * and a FAILED execution into delays instead of stalls.
 *
 * <p>quarkus-scheduler rides in with the eventstream jar, so this costs the deployable nothing new.
 * The interval is {@code qits.projects.release-requests.sweep-every}; the suite turns it {@code
 * off} in {@code %test} (the same darkness idiom as the bus), because a tick landing mid-test would
 * execute a request a test was still staging.
 */
@ApplicationScoped
public class ReleaseRequestSweep {

  @Inject ReleaseRequests releaseRequests;

  @Scheduled(
      every = "{qits.projects.release-requests.sweep-every}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweep() {
    releaseRequests.sweep();
  }
}
