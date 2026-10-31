package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * The set of files a commit changed relative to a diff base. {@code parent} is the base the changes
 * are computed against. On the endpoint ({@code CommitService#listCommitChanges}) that is the
 * explicit {@code parent} query param as given, or the commit's own first parent resolved to a sha
 * when omitted; it is {@code null} only for a root commit diffed against the empty tree. An internal
 * caller of {@code CommitService#listChanges} gets back the base it passed, {@code null} included.
 */
public record CommitChangesDto(String commit, String parent, List<CommitFileChangeDto> files) {}
