package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <b>An approval requirement of a release request's fold changed state</b> (epic qits-1038). The
 * owner's decision 6: an open requirement is an event, so a later feature can send a reviewer with a
 * specific context to it; that dispatch subscribes here and is not part of this service.
 *
 * <p>{@code state} is {@code OPEN} (the approval gate reached the fold and nothing satisfies the
 * requirement), {@code SATISFIED} (an approval at the fold does), {@code DECLINED} (an approver who
 * qualified for it declined) or {@code VOID} (a re-fold left it behind; {@code mergedSha} is the old
 * fold). Each (request, fold, requirement, state) is announced once, after the commit that recorded
 * it ({@code release_request_approval_notice}).
 *
 * <p>Payload {@code {releaseRequestId, releaseRequestQualifiedId, projectId, repoId, repoName,
 * mergedSha, requirementId, reason, admits[], excludesAuthors, authors[], state, changedAt}};
 * {@code changedAt} is the envelope's {@code occurredAt}; a null field is an absent key. {@code
 * admits} are approval roles rendered ({@code person}, {@code agent}, {@code agent@<slug>}); {@code
 * authors} the principals that added the request's branch sources. Pinned by {@code
 * ApprovalRequirementChangedContractTest}; registered in {@link EventWireReflection}. A local record,
 * like every event this service publishes: a consumer decodes it into a record of its own.
 */
public record ApprovalRequirementChanged(
    UUID eventId,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    String projectId,
    String repoId,
    String repoName,
    String mergedSha,
    String requirementId,
    String reason,
    List<String> admits,
    boolean excludesAuthors,
    List<String> authors,
    String state,
    Instant changedAt)
    implements QitsEvent {

  public ApprovalRequirementChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    admits = admits == null ? List.of() : List.copyOf(admits);
    authors = authors == null ? List.of() : List.copyOf(authors);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
