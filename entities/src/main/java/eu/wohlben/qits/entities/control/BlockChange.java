package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import java.time.Instant;

/**
 * <b>A committed change of a row's explicit block flag</b> (qits-2674), as {@link BlockAnnouncer}
 * is told it: the row's post-state, what was said about it and who wrote it.
 *
 * @param entityId the row
 * @param projectId its project
 * @param archetype its archetype — an epic or a ticket, the two kinds a block stands on
 * @param number its per-project number
 * @param qualifiedId {@code <project-slug>-<number>}, or null when the slug could not be read
 * @param status its status as the transaction left it
 * @param blocked the flag now
 * @param reason the stated blocker of a block, the stated reason of an unblock (null when none was
 *     given), or {@code cleared by transition to <STATUS>} for a transition's clear
 * @param changedBy the principal of the (last) write
 * @param changedAt when the transaction committed
 */
public record BlockChange(
    String entityId,
    String projectId,
    Archetype archetype,
    long number,
    String qualifiedId,
    String status,
    boolean blocked,
    String reason,
    String changedBy,
    Instant changedAt) {}
