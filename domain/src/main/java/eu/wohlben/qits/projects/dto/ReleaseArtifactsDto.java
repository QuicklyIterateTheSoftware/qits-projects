package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * What one release put on the platform: whether anything deploys it, and what it published.
 *
 * <p><b>Read out of the released tag's own tree, never out of a build's record.</b> The recipes are
 * files in the repository at the tag, so this answer is a fact about what was released rather than
 * about what a pipeline happened to report — which is what makes it answerable for a release made
 * before this endpoint existed, and for one whose CI never announced anything at all.
 *
 * <p><b>Every failure here is a 200 with a sentence.</b> A request that has not released, a git host
 * that cannot be asked and a recipe that will not parse are all answers about the release rather
 * than faults of the reader, and a caller drawing a panel needs to say which of them it hit. The
 * only 404 is a request id that names nothing.
 *
 * @param version the calver the release landed as, null when it has not released
 * @param releasedSha the commit the tag points at, null on an unreleased request and on a release
 *     made before that column existed
 * @param deployable whether the released tree declares {@code .config/qits/deployments.yml} — the
 *     platform's own statement that something deploys this, and the whole of what tells a service
 *     apart from a library here
 * @param artifacts what the release published, empty where it published nothing this service can
 *     read
 * @param detail why the list is empty or short, or null where it needs no explaining. <b>A
 *     repository with no release recipe gets no detail</b>: publishing nothing is an answer, not a
 *     problem, and every SPA on this platform is in that case.
 */
public record ReleaseArtifactsDto(
    String version,
    String releasedSha,
    boolean deployable,
    List<ReleaseArtifactDto> artifacts,
    String detail) {}
