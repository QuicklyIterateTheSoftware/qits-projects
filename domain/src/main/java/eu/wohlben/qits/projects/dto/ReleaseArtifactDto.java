package eu.wohlben.qits.projects.dto;

/**
 * One thing a release published, named in the platform's own vocabulary.
 *
 * <p><b>{@code type} is the recipe's word, forwarded rather than translated.</b> It is what the
 * repository's {@code .config/qits/ci-event-release.yml} says — {@code docker}, {@code maven},
 * {@code npm}, {@code docs}, {@code daemon} today — plus {@code userflows}, which is the one entry
 * this service derives rather than reads. A word nothing here recognises still travels: the caller
 * shows what it can address and names the rest, which is the only way an artifact kind can be added
 * to the platform without a release of this service.
 *
 * <p>{@code name} is the coordinate in whatever the type's namespace is — an image repository, a
 * maven {@code groupId:artifactId}, an npm package, a docs site. {@code version} is what to ask for
 * it by, and it is <b>not always the calver</b>: the userflows bundle is published at the commit,
 * because its pipeline runs per push and stamps {@code $QITS_CI_SHA}.
 */
public record ReleaseArtifactDto(String type, String name, String version) {}
