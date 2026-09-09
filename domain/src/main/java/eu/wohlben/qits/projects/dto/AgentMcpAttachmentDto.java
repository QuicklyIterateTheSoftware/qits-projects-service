package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * One of the platform's own MCP servers as a surface attaches it: which server, how narrow its url
 * is, whether it is read-only marked, and the tools pre-approved on it.
 *
 * <p>The three narrowing flags render in one canonical order — {@code projectId}, {@code
 * repositoryId}, {@code workspaceId} — because that is the order both daemons build their urls in
 * today and the rendered command line is asserted as a literal on both harnesses.
 *
 * @param server {@code repository}, {@code observability} or {@code actions}
 * @param narrowProject append {@code projectId=<the project>}
 * @param narrowRepository append {@code repositoryId=<the repository>}
 * @param narrowWorkspace append {@code workspaceId=<the workspace>}
 * @param readOnly append {@code agentReadOnly=true}, the autonomous fence
 * @param allowedTools the pre-approved tool ids, in render order — shipped, not operator-editable
 */
public record AgentMcpAttachmentDto(
    String server,
    boolean narrowProject,
    boolean narrowRepository,
    boolean narrowWorkspace,
    boolean readOnly,
    List<String> allowedTools) {}
