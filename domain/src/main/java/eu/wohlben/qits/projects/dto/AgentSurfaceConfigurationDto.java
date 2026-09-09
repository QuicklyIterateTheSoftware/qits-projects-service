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
 * @param surface the surface key this configures
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
    boolean shipped) {}
