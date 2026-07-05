package eu.wohlben.qits.domain.repository.dto;

/**
 * A branch in a repository.
 *
 * @param name the branch name
 * @param canCleanup whether the branch can be removed with no data loss: it is not the main branch,
 *     has no unmerged commits against its parent ({@code ahead == 0}), a clean working tree when it
 *     is workspace-backed, and no other workspace forks from it. The cleanup endpoint enforces
 *     exactly this, so the UI can offer cleanup without a confirmation.
 * @param parent the branch this one is compared against — its workspace's fork point when
 *     workspace-backed, otherwise the repository's main branch; {@code null} for the main branch
 *     itself (which has no parent)
 * @param ahead commits this branch has that {@code parent} does not ({@code null} when git can't
 *     compare, e.g. no resolvable parent)
 * @param behind commits {@code parent} has that this branch does not
 */
public record BranchDto(
    String name, boolean canCleanup, String parent, Integer ahead, Integer behind) {}
