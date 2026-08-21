package eu.wohlben.qits.projects.dto;

/**
 * One repository's public coordinates — the flat catalogue entry a machine enumerates the platform
 * with.
 *
 * <p>It is deliberately smaller than {@link RepositoryDto}: a caller listing every repository is
 * choosing which ones to act on, not drawing one, so backup status and archetype are noise it would
 * have to skip past. What it needs is the pair that addresses a repository publicly and the branch
 * to read.
 *
 * @param id the opaque row id — the storage key this service and qits-githost share. Never shown to
 *     a person and never an address.
 * @param projectId the project the repository belongs to; the first half of the public identity
 * @param name the addressable name from the alias table — the second half. <b>Null for a row that
 *     owns no alias</b>, and such a row is still listed: it is a fact about this service's state,
 *     and a caller that cannot address it skips it. Omitting it would hide the row instead.
 * @param mainBranch the branch a caller reads by default
 */
public record RepositoryCoordinatesDto(
    String id, String projectId, String name, String mainBranch) {}
