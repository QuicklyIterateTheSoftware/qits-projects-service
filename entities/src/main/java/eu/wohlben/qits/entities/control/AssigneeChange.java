package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import java.time.Instant;

/**
 * <b>A committed change of a row's assignee</b> (qits-2518), as {@link AssigneeAnnouncer} is told
 * it: the row's post-state, the value before the first write of the transaction and who wrote it.
 *
 * @param entityId the row
 * @param projectId its project
 * @param archetype its archetype — an epic or a ticket, the two kinds with an assignee
 * @param number its per-project number
 * @param qualifiedId {@code <project-slug>-<number>}, or null when the slug could not be read
 * @param title its title as the transaction left it
 * @param status its status as the transaction left it
 * @param assignee the assignee now, null when cleared
 * @param previousAssignee the assignee before, null when there was none
 * @param claimed {@link eu.wohlben.qits.entities.entity.WorkEntity#assigneeClaimed} now
 * @param changedBy the principal of the (last) write
 * @param changedByName how a reader names {@code changedBy} ({@link AssignerNames}), read inside
 *     the write; {@code changedBy} when nothing better could be said
 * @param changedAt when the transaction committed
 */
public record AssigneeChange(
    String entityId,
    String projectId,
    Archetype archetype,
    long number,
    String qualifiedId,
    String title,
    String status,
    String assignee,
    String previousAssignee,
    boolean claimed,
    String changedBy,
    String changedByName,
    Instant changedAt) {}
