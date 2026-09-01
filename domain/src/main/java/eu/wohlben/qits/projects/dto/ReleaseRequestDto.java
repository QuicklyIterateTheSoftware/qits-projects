package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One release request as the API answers it. {@code state} is the stored word — {@code PENDING},
 * {@code READY}, {@code RELEASED}, {@code REJECTED}, {@code FAILED} or {@code WITHDRAWN} today, and
 * the vocabulary may grow. {@code detail} is the sentence explaining a request that is not simply
 * pending or released; {@code version} is the calver the release door answered with, once it did.
 * {@code retryable} says, of a FAILED request, whether the sweep keeps retrying the execution or
 * the refusal stands until a push re-arms it.
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
    String branch,
    String commitSha,
    String state,
    String summary,
    String requester,
    String detail,
    String version,
    boolean retryable,
    Instant createdAt,
    Instant updatedAt) {}
