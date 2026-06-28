package eu.wohlben.qits.domain.repository.dto;

/**
 * A branch in a repository.
 *
 * @param name the branch name
 * @param canCleanup whether the branch can be removed with no data loss: it is not the main branch,
 *     has no unmerged commits against its parent ({@code ahead == 0}), a clean working tree when it
 *     is worktree-backed, and no other worktree forks from it. The cleanup endpoint enforces
 *     exactly this, so the UI can offer cleanup without a confirmation.
 */
public record BranchDto(String name, boolean canCleanup) {}
