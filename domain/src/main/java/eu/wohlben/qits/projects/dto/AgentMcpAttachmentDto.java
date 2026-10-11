package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * The platform's own MCP server as a surface attaches it: which server, how narrow its url is,
 * whether it is read-only marked, and the tools pre-approved on it.
 *
 * <p>The three narrowing flags are part of the configuration document's wire shape. {@code qits},
 * the one built-in server since qits-1174, is scoped by the caller's bearer and ships with all
 * three off.
 *
 * @param server {@code qits}
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
