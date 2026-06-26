package eu.wohlben.qits.domain.repository.dto;

/**
 * A single commit in a branch's log.
 *
 * @param hash the full commit SHA
 * @param shortHash the abbreviated commit SHA (git's default short form)
 * @param author the author's name
 * @param email the author's email
 * @param date the committer date in strict ISO-8601 form (git {@code %cI})
 * @param message the commit subject (first line)
 */
public record CommitDto(
    String hash, String shortHash, String author, String email, String date, String message) {}
