package eu.wohlben.qits.domain.repository.dto;

import java.util.List;

/**
 * The commit log for a branch, scoped to the commits unique to it.
 *
 * @param branch the branch the log was requested for
 * @param parent the branch the range was computed against (a worktree's parent, else the
 *     repository's main branch); {@code null} when no parent could be resolved and the full history
 *     was returned instead
 * @param commits the commits in {@code parent..branch}, newest first
 */
public record CommitLogDto(String branch, String parent, List<CommitDto> commits) {}
