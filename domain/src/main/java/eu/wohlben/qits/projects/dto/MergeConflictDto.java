package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * Why a CONFLICTED request could not be folded — qits-githost's own answer, forwarded rather than
 * reworded.
 *
 * <p>Null on every request that is not CONFLICTED, and cleared by the first fold that succeeds. It
 * is on the read because a conflict is the one state a person has to <b>act</b> on: the paths say
 * what to resolve and {@code head} says which participant introduced it, which is git's own answer
 * to "who broke it" and the only one a caller can do anything with.
 */
public record MergeConflictDto(String target, List<ConflictedPath> conflicts) {

  /**
   * One conflicting path. {@code head} is the source <b>as it was spelled to the git host</b>
   * ({@code refs/heads/feature/x}, {@code refs/tags/2026.903.1}), {@code headSha} what it pointed at,
   * and {@code reason} the git host's word for the kind of conflict ({@code content} and the
   * merger's own failure reasons). Named after the git host's own record on purpose — a nested
   * {@code Path} would land in the generated OpenAPI document under that name and mean nothing.
   */
  public record ConflictedPath(String path, String head, String headSha, String reason) {}
}
