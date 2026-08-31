package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * How many CI runs are still queued or running for one commit — the half of the build gate the
 * ledger cannot answer, because only terminal runs announce.
 *
 * <p>A port in the house shape: the implementation is {@code service/…/releasehost} (an HTTP read
 * of qits-ci's active-runs listing), resolved via {@code Instance} with absent supported. The
 * gate's reading of every non-answer is the same: <b>{@code Optional.empty()} means "could not
 * ask"</b> — the port unconfigured, the service unreachable, an unreadable answer — and a gate that
 * cannot ask stays pending rather than guessing, with the settle window as the floor under a
 * platform that runs no CI at all. An implementation must not throw and must answer quickly; it is
 * called on gate evaluation, which runs on the bus dispatch and the sweep.
 */
public interface ActiveBuilds {

  /** Active (queued or running) runs for this commit, or empty when it could not be asked. */
  Optional<Integer> activeFor(String repoId, String commitSha);
}
