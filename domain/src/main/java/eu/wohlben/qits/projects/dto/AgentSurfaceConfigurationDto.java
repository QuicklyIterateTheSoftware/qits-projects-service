package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import java.util.List;

/**
 * One session surface's configuration, as the editor reads it and as a container is born with it.
 *
 * <p><b>One record for both doors, deliberately.</b> The editor shows what a session will run as and
 * the container's document says what it will run as; two records would be two chances for those
 * answers to differ, which is the exact failure this epic exists to remove. The editor's <em>write</em>
 * body is a different record, because it takes strings it has to validate and does not take the
 * shipped tool lists at all.
 *
 * <p>{@code model}, {@code effort}, {@code systemPrompt} and {@code initialPrompt} are never null:
 * empty is a first-class value here — {@code project.epics} steers with an empty system prompt on
 * purpose, and an empty model is the harness's own default.
 *
 * <p><b>{@code externalMcpServers} carries references and never a credential's value.</b> A surface
 * names the catalog entries it attaches; each entry travels with its url, its header name and the
 * qits-configuration key the header's value lives behind. This record is serialized into revision
 * snapshots, into the editor's answers and into the OpenAPI document, so the value must not be here
 * — it is resolved once, when a container's document is built, into {@code AgentResolvedMcpServerDto}
 * inside {@code AgentDocumentSurfaceDto}, and nowhere else.
 *
 * @param surface the surface key this configures
 * @param mcpServers the platform's own servers this surface attaches, with their narrowing
 * @param externalMcpServers the catalog entries this surface attaches, by reference
 * @param shipped true when no row exists and this is the shipped default being answered
 */
public record AgentSurfaceConfigurationDto(
    String surface,
    AgentHarness harness,
    String model,
    String effort,
    boolean remoteControl,
    AgentPermissionMode permissionMode,
    boolean activityTracking,
    String systemPrompt,
    String initialPrompt,
    List<AgentMcpAttachmentDto> mcpServers,
    List<AgentMcpCatalogEntryDto> externalMcpServers,
    boolean shipped) {}
