package eu.wohlben.qits.projects.dto;

import java.time.Instant;
import java.util.List;

/**
 * One release request as the API answers it. {@code state} is the stored word — {@code PENDING},
 * {@code READY}, {@code RELEASED}, {@code REJECTED}, {@code FAILED}, {@code CONFLICTED} or {@code
 * WITHDRAWN} today, and the vocabulary may grow. {@code detail} is the sentence explaining a request
 * that is not simply pending or released; {@code version} is the calver the release answered with,
 * once it did. {@code retryable} says, of a FAILED request, whether the sweep keeps retrying the
 * execution or the refusal stands until something re-arms it.
 *
 * <p><b>A request is a merge of {@code sources}, not a branch head.</b> {@code backingBranch} is
 * {@code release/<id>} — the ref the git host folds them into — and {@code mergedSha} is the tip of
 * that fold: what the gates evaluate and what an execution is pinned to. {@code mergedSha} is
 * <b>null until the first fold lands</b>, and null on a CONFLICTED request whose first fold never
 * did; a caller reads that as "nothing is gated yet", never as "nothing to release".
 *
 * <p>{@code sources} carries both kinds — the named branches somebody put on the request and the
 * implicit released tags the repository has in flight — with {@code implicit} telling them apart.
 * Only the named ones are caller-managed.
 *
 * <p>{@code conflict} is populated on a CONFLICTED request and null on every other, so a caller
 * never has to ask a second question to find out what to resolve.
 *
 * <p>{@code repoName} is the repository's public name — null where it has none. It rides along
 * because a request read outside its repository's own page (the project-wide list) has nothing else
 * to name the repository with, and an opaque id is not a thing to show a person. The project list
 * resolves it live from the alias table so a rename is reflected; every other read answers the name
 * recorded when the request was made.
 */
public record ReleaseRequestDto(
    String id,
    String repoId,
    String repoName,
    String backingBranch,
    List<ReleaseRequestSourceDto> sources,
    String mergedSha,
    String state,
    String summary,
    String requester,
    String detail,
    MergeConflictDto conflict,
    String version,
    boolean retryable,
    Instant createdAt,
    Instant updatedAt) {}
