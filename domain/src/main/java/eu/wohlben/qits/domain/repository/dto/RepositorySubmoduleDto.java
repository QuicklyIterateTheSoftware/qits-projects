package eu.wohlben.qits.domain.repository.dto;

/**
 * A submodule edge surfaced to clients: the superproject {@code parentRepoId} mounts the sibling
 * {@code childRepoId} at {@code path} (the {@code .gitmodules} {@code path =}), under the section
 * {@code name}. Both endpoints are repositories under the same project; the pinned commit is not
 * surfaced (it lives in the gitlink).
 */
public record RepositorySubmoduleDto(
    String id, String parentRepoId, String childRepoId, String path, String name) {}
