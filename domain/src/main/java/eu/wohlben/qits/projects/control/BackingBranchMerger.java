package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * Folds a release request's sources into its backing branch — qits-githost's octopus-merge
 * primitive, seen from this side of the boundary.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported. A deployment with no
 * git-host address leaves every request standing with a detail that says why, which is a visible
 * stall the sweep re-tries rather than a silent one. <b>An implementation must not throw</b>: every
 * answer is an {@link Outcome}, and {@link Result#UNREACHABLE} carries the reason.
 *
 * <p><b>It speaks refs and nothing else.</b> The git host owns no vocabulary about releases and this
 * port keeps it that way: no version, no request id, no notion of what {@code release/<id>} is for.
 * What travels is a target ref, source refs in the order they should become parents, and a message.
 */
public interface BackingBranchMerger {

  /**
   * Fold {@code sources} into {@code target}.
   *
   * @param repoId the repository's storage id — the git host's own key
   * @param target the full backing-branch ref, {@code refs/heads/release/<id>}
   * @param sources fully qualified refs ({@code refs/heads/main}, {@code refs/tags/2026.903.1}), in
   *     the order they should become parents of the fold
   * @param message the merge commit's message, for a person reading the log
   */
  Outcome merge(String repoId, String target, List<String> sources, String message);

  /**
   * What the fold did. The three success words are the git host's own — {@code merged} (a new commit
   * and the ref moved), {@code fast-forward} (the ref was created at or moved onto an existing
   * commit) and {@code unchanged} (every head was already contained; same sha, no new commit) — and
   * the difference between them is load-bearing here: <b>{@code UNCHANGED} is not a change</b>, so
   * it re-arms nothing and announces nothing, which is what makes a trigger that fires on an event
   * with no content behind it (a pending tag leaving the set, a duplicate delivery) free.
   *
   * <p>{@link Result#CONFLICT} is the git host's 409: no ref moved, and {@link Outcome#conflicts}
   * names the paths and the head that introduced each. {@link Result#UNREACHABLE} is everything
   * else — an unconfigured address, a timeout, a 5xx, a refusal nobody predicted — which is a fact
   * about the moment and not about the request, so the sweep asks again.
   */
  record Outcome(
      Result result, String sha, List<String> parents, List<Conflict> conflicts, String detail) {

    public static Outcome merged(String sha, List<String> parents) {
      return new Outcome(Result.MERGED, sha, List.copyOf(parents), List.of(), null);
    }

    public static Outcome fastForward(String sha, List<String> parents) {
      return new Outcome(Result.FAST_FORWARD, sha, List.copyOf(parents), List.of(), null);
    }

    public static Outcome unchanged(String sha) {
      return new Outcome(Result.UNCHANGED, sha, List.of(), List.of(), null);
    }

    public static Outcome conflict(String target, List<Conflict> conflicts) {
      return new Outcome(Result.CONFLICT, null, List.of(), List.copyOf(conflicts), target);
    }

    public static Outcome unreachable(String detail) {
      return new Outcome(Result.UNREACHABLE, null, List.of(), List.of(), detail);
    }

    /** Whether the target ref now names {@link #sha} — true for all three success words. */
    public boolean folded() {
      return result == Result.MERGED
          || result == Result.FAST_FORWARD
          || result == Result.UNCHANGED;
    }
  }

  enum Result {
    MERGED,
    FAST_FORWARD,
    UNCHANGED,
    CONFLICT,
    UNREACHABLE
  }

  /** One conflicting path, forwarded from the git host unchanged. */
  record Conflict(String path, String head, String headSha, String reason) {}
}
