package eu.wohlben.qits.domain.repository.dto;

/**
 * A single file touched by a commit (relative to the diff base). {@code oldPath} is non-null only
 * for renames/copies (the path the file moved from); {@code changeType} is one of {@code
 * ADDED|MODIFIED|DELETED|RENAMED|COPIED|TYPE_CHANGED}.
 */
public record CommitFileChangeDto(String path, String oldPath, String changeType) {}
