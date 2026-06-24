package eu.wohlben.qits.domain.repository.dto;

/**
 * Sync state of a repository's main branch against its remote, measured with a read-only {@code git
 * ls-remote} (no objects fetched).
 *
 * @param branch the main branch being tracked
 * @param remoteReachable whether the remote could be queried (false when the network/URL fails)
 * @param remoteExists whether the branch exists on the remote
 * @param ahead commits the local branch has that the remote does not; {@code null} when it cannot
 *     be counted locally (remote commits not present without a fetch)
 * @param behind commits the remote has that the local branch does not; {@code null} when not
 *     locally countable
 */
public record SyncStatusDto(
    String branch, boolean remoteReachable, boolean remoteExists, Integer ahead, Integer behind) {}
