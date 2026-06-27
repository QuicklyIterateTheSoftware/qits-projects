package eu.wohlben.qits.domain.repository.dto;

import java.util.List;

/**
 * The set of files a commit changed relative to a diff base. {@code parent} is the resolved base
 * the changes are computed against (the explicit {@code parent} query param, or the commit's own
 * first parent when omitted); it is {@code null} for a root commit diffed against the empty tree.
 */
public record CommitChangesDto(String commit, String parent, List<CommitFileChangeDto> files) {}
