package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * What one release request folded in — the commits its octopus merge brought onto the backing
 * branch, which is the answer to "what is actually in this release".
 *
 * <p><b>The range is the merge's own, not a branch's.</b> It is read as {@code <mergedSha>^1..
 * <mergedSha>}: the first parent of the fold is the repository's default branch as it stood when the
 * fold was made, so what is left is exactly what the other participants brought. That stays true
 * after the release reaches {@code main} — the parents of a commit do not move — which is why the
 * question can still be asked of a request that concluded weeks ago.
 *
 * <p><b>The version bump is deliberately not in it.</b> The release commits the rewritten manifests
 * <em>onto</em> the fold and tags that commit, so {@code releasedSha} is a child of {@code
 * mergedSha} and outside the range. What is listed here is the work; the bump is bookkeeping.
 *
 * @param mergedSha the fold this list is about, null on a request whose first fold has not landed
 * @param commits the commits the fold brought in, newest first — <b>the fold itself leads them</b>,
 *     because a range ending at a commit contains it. That is the honest shape: the merge is the
 *     newest thing in the release and its message is the request's own summary. Empty is a real
 *     answer.
 * @param detail why the list is empty, where the emptiness needs a sentence — nothing folded yet,
 *     the fold pruned out of the repository's history, or a fold that genuinely added nothing. Null
 *     whenever the list stands on its own.
 */
public record ReleaseRequestCommitsDto(String mergedSha, List<CommitDto> commits, String detail) {}
