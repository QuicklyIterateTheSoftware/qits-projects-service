package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/**
 * One remark on an entity's thread, of any archetype.
 *
 * @param author the agent id for an agent's comment (qits-1448), otherwise the principal name;
 *     null when unattributed
 * @param authorAgentOf the qualified id ({@code <projectSlug>-<n>}) of the work item the authoring
 *     agent works on, resolved at read time; null for a person, a machine, an unresolved agent or
 *     a work item that no longer exists
 * @param editable whether the caller reading it may edit it: only its author may
 */
public record CommentDto(
    String id,
    String entityId,
    String author,
    String authorAgentOf,
    String body,
    Instant createdAt,
    Instant updatedAt,
    boolean editable) {}
