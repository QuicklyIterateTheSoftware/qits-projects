package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;

/**
 * A repository as clients see it.
 *
 * @param name the project-scoped addressable name — what {@code /git/<projectId>/<name>} serves and
 *     what a committed {@code ../<name>.git} resolves to. This is the repository's identity to a
 *     person; {@code id} is an opaque key. Null only for a row that somehow owns no alias.
 * @param backupUrl where this repository is <b>backed up to</b> — the forge twin every accepted push
 *     is mirrored onto. It carried the name {@code url} until the release before this one, which
 *     read as a clone source and never was one — the platform clones through its own git host,
 *     always. Derived from the project's wrapper and the component's name, so it is the
 *     same value a relative {@code ../<name>.git} resolves to at the forge. Null when the
 *     repository has no twin yet, which is a normal state and not an error.
 * @param archetype what kind of component this is — the closed taxonomy the archetype-keyed child
 *     apps are still selected by
 * @param component the technical component this repository is part of, read from its wrapper path
 *     ({@code components/<component>/<name>}). An <b>open set</b>, and null for an entry still
 *     mounted under an archetype directory — the chrome groups by it and falls back to the
 *     archetype grouping for a null.
 * @param lastBackup how the last backup onto that twin went, or null when there has never been one
 */
public record RepositoryDto(
    String id,
    String name,
    String backupUrl,
    String mainBranch,
    RepositoryArchetype archetype,
    String component,
    String projectId,
    LastBackupDto lastBackup) {}
