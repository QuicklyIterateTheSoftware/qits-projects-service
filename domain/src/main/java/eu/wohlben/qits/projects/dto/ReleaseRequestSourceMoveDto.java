package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One source moved between two release requests (qits-1665), as a request's {@code sourceMoves}
 * answers it: the moves into or out of that request, oldest first.
 *
 * @param branch the moved source branch, {@code ticket/qits-1660}
 * @param fromQualifiedId the request it left, {@code <repo>-rr-<n>}; null where that request is gone
 * @param toQualifiedId the request queued after it, which took the source; null where it is gone
 * @param movedBy the authenticated principal that moved it
 * @param reason why, as the mover said it
 * @param movedAt when
 */
public record ReleaseRequestSourceMoveDto(
    String branch,
    String fromQualifiedId,
    String toQualifiedId,
    String movedBy,
    String reason,
    Instant movedAt) {}
