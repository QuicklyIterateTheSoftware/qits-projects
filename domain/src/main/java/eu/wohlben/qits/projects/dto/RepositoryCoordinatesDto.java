package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;

/**
 * One repository's public coordinates — the flat catalogue entry a machine enumerates the platform
 * with.
 *
 * <p>It is still smaller than {@link RepositoryDto}: a caller listing every repository is choosing
 * which ones to act on, not drawing one, so the backup status and the wrapper-derived component
 * stay out. What it carries is the pair that addresses a repository publicly, the branch to read —
 * and the archetype.
 *
 * <p><b>The archetype is here because a consumer is keyed on it.</b> This DTO argued the opposite
 * until the release trains landed: archetype was called noise a lister would have to skip past. That
 * reasoning predates qits-maintenance's release-train reader, which decides what a train does with a
 * repository from its kind — a library is bumped into its consumers, a frontend follows its shared
 * library, a service is released on its own — so it needs every repository's archetype before it
 * picks any of them. Deriving it from the name's role suffix at the far end would be this service's
 * {@link RepositoryArchetype#fromRepositoryName} reimplemented in another repository, free to drift
 * from the stored column; asking {@code GET /repositories/{repoId}} once per row would be a request
 * per repository to learn one enum. The MCP surface reached the same conclusion first — {@code
 * mcp/RepositoryMcpTools.RepositorySummary} has carried the archetype in its listing all along —
 * so REST is catching up rather than breaking new ground.
 *
 * @param id the opaque row id — the storage key this service and qits-githost share. Never shown to
 *     a person and never an address.
 * @param projectId the project the repository belongs to; the first half of the public identity
 * @param name the addressable name from the alias table — the second half. <b>Null for a row that
 *     owns no alias</b>, and such a row is still listed: it is a fact about this service's state,
 *     and a caller that cannot address it skips it. Omitting it would hide the row instead.
 * @param mainBranch the branch a caller reads by default
 * @param archetype what kind of component this is, serialized <b>by name</b> ({@code SERVICE},
 *     {@code LIBRARY}, …). <b>Null, and the key may be absent</b>, for a row whose name declares no
 *     role suffix — the reconcile stores null rather than guessing, and a guessed kind is the one
 *     thing nothing downstream could correct. A consumer parses it leniently and treats an absent
 *     key exactly as a null one; it must never key a lookup on it without an answer for the null.
 */
public record RepositoryCoordinatesDto(
    String id, String projectId, String name, String mainBranch, RepositoryArchetype archetype) {}
