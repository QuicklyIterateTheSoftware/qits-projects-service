package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * One surface inside the document a container is born with: its configuration, plus the external MCP
 * servers <b>fully rendered</b>.
 *
 * <p><b>Why the document's surface is a wrapper rather than the configuration record itself.</b> The
 * previous feature deliberately answered both doors — the editor's and the container's — with one
 * {@link AgentSurfaceConfigurationDto}, so that what an operator sees and what a container runs
 * cannot drift. That reasoning survives and is why {@link #configuration} is that same record,
 * unchanged. What does not survive contact with the MCP catalog is answering both doors with the
 * <em>same bytes</em>: the container needs a resolved header value and the editor must never see
 * one. Wrapping keeps one record for the configuration and puts the credential-bearing half where
 * only the container's door reaches it.
 *
 * <p>{@link #configuration} still lists the attached catalog entries, by key and by reference —
 * {@code AgentSurfaceConfigurationDto.externalMcpServers}. That is not a duplicate of {@link
 * #externalMcpServers}: one says which entries this surface attaches and where their credentials
 * live, which is what a launch record and an editor need, and the other is what the harness renders.
 *
 * @param configuration the surface's configuration, exactly as the editor reads it
 * @param externalMcpServers the attached catalog entries with their credentials resolved, in
 *     attachment order; empty when the surface attaches none
 */
public record AgentDocumentSurfaceDto(
    AgentSurfaceConfigurationDto configuration,
    List<AgentResolvedMcpServerDto> externalMcpServers) {}
