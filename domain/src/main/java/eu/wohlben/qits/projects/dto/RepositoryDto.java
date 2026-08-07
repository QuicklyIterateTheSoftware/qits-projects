package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;

/**
 * A repository as clients see it.
 *
 * @param name the project-scoped addressable name — what {@code /git/<projectId>/<name>} serves and
 *     what a committed {@code ../<name>.git} resolves to. This is the repository's identity to a
 *     person; {@code id} is an opaque key. Null only for a row that somehow owns no alias.
 * @param url <b>deprecated — read {@code backupUrl} instead.</b> The same value under its old,
 *     misleading name: it was never the url anything clones from (the platform clones through its
 *     own git host, always), and reading it as one is the mistake the rename exists to stop. Kept
 *     for exactly one release so a client already in flight does not break; it goes in the next.
 * @param backupUrl where this repository is <b>backed up to</b> — the forge twin every accepted push
 *     is mirrored onto. Derived from the project's wrapper and the component's name, so it is the
 *     same value a relative {@code ../<name>.git} resolves to at the forge. Null when the
 *     repository has no twin yet, which is a normal state and not an error.
 * @param lastBackup how the last backup onto that twin went, or null when there has never been one
 */
public record RepositoryDto(
    String id,
    String name,
    @Deprecated String url,
    String backupUrl,
    String mainBranch,
    RepositoryArchetype archetype,
    String projectId,
    LastBackupDto lastBackup) {}
