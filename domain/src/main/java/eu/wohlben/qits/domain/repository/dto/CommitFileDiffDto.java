package eu.wohlben.qits.domain.repository.dto;

/**
 * The unified diff of a single file in a commit (relative to the diff base). {@code diff} is the
 * raw {@code git} patch text, rendered client-side; it is empty when the file has no textual change
 * (e.g. binary or a pure rename).
 */
public record CommitFileDiffDto(String path, String changeType, String diff) {}
