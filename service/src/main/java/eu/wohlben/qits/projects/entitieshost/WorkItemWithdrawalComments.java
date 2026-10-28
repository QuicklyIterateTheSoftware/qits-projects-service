package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.control.ReleaseRequestWithdrawalComments;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * {@link ReleaseRequestWithdrawalComments} onto the work item's thread (qits-1671): when a release
 * request is withdrawn because its branch was deleted, each work item one of its branch sources
 * named — {@code ticket/<qualified id>} or {@code epic/<qualified id>}, and an agent's own {@code
 * ticket/<qualified id>-<anything>} beside it, {@link WorkItemBranches}' reading shared with {@link
 * WorkItemApprovalComments} — is told which request was withdrawn, by the deletion of which branch,
 * and what that cost: nothing, unreleased work, or a verdict nobody could read.
 *
 * <p>The gap it closes: qits-1675 waited on {@code qits-qits-rr-226} after the request was gone,
 * because the withdrawal was written on the request's thread and nowhere else. The item's thread is
 * the one its agent waits on and its owner reads, so that is where it is said.
 *
 * <p><b>"Nothing was lost" is said for {@link Lost#NOTHING} alone.</b> The verdict is the caller's
 * lineage read of the request's last fold against the default branch; {@link Lost#UNKNOWN} says it
 * could not be established and nothing more, because telling an item its work is safe when nobody
 * checked is the one sentence here that could cost somebody the work.
 *
 * <p>Resolved in process, through {@link EntityIdResolver} and {@link EntityCommentService}, as
 * {@link TicketUnattendedGateTickets} comments, once per distinct work item. A comment changes no
 * status. Called after the withdrawal committed. <b>Never throws</b>: a comment that could not be
 * written is a WARN and the next item is still told, and a branch naming no work item is no comment
 * at all.
 */
@ApplicationScoped
@DefaultBean
public class WorkItemWithdrawalComments implements ReleaseRequestWithdrawalComments {

  private static final Logger LOG = Logger.getLogger(WorkItemWithdrawalComments.class);

  /** Who the comment is by — the same reporter the gate tickets name. */
  static final String AUTHOR = TicketUnattendedGateTickets.REPORTER;

  @Inject EntityIdResolver resolver;

  @Inject EntityCommentService comments;

  @Inject ProjectChangePublisher publisher;

  @Override
  @ActivateRequestContext
  public void withdrawn(Withdrawn withdrawn) {
    try {
      Map<String, CommitSubjectEntities.NamedEntity> items = workItems(withdrawn);
      if (items.isEmpty()) {
        return;
      }
      String body = body(withdrawn);
      for (CommitSubjectEntities.NamedEntity item : items.values()) {
        try {
          comment(item.id(), body);
          LOG.infof(
              "Release request %s was withdrawn (%s deleted); said so on %s",
              withdrawn.releaseRequestQualifiedId(), withdrawn.deletedBranch(), item.qualifiedId());
          redraw(item.projectId());
        } catch (RuntimeException e) {
          LOG.warnf(
              e,
              "Could not tell %s that release request %s was withdrawn",
              item.qualifiedId(),
              withdrawn.releaseRequestQualifiedId());
        }
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not tell the work items of release request %s that it was withdrawn",
          withdrawn == null ? null : withdrawn.releaseRequestQualifiedId());
    }
  }

  /**
   * The work items the request's branch sources name, once each. The deleted branch is among the
   * sources, and is added should a caller ever leave it out.
   */
  Map<String, CommitSubjectEntities.NamedEntity> workItems(Withdrawn withdrawn) {
    List<String> branches = new ArrayList<>();
    if (withdrawn.branches() != null) {
      branches.addAll(withdrawn.branches());
    }
    if (withdrawn.deletedBranch() != null && !branches.contains(withdrawn.deletedBranch())) {
      branches.add(withdrawn.deletedBranch());
    }
    return WorkItemBranches.workItems(branches, this::lookup);
  }

  /** The work item a qualified id names, through {@link EntityIdResolver}. */
  Optional<CommitSubjectEntities.NamedEntity> lookup(CommitSubjectEntities.QualifiedId id) {
    return resolver.lookup(id);
  }

  /** One comment, outside the caller's transaction, as {@link #AUTHOR}. */
  void comment(String itemId, String body) {
    QuarkusTransaction.suspendingExisting().run(() -> comments.addComment(itemId, body, AUTHOR));
  }

  /** The comment: which request, which branch, what was left, and what that cost. */
  static String body(Withdrawn withdrawn) {
    String main = withdrawn.defaultBranch() == null ? "main" : withdrawn.defaultBranch();
    StringBuilder text = new StringBuilder();
    text.append("Release request `")
        .append(withdrawn.releaseRequestQualifiedId())
        .append("` was withdrawn: its source `")
        .append(withdrawn.deletedBranch())
        .append("` was deleted on the git host, which left only `")
        .append(main)
        .append("`.\n\n");
    Lost lost = withdrawn.lost() == null ? Lost.UNKNOWN : withdrawn.lost();
    switch (lost) {
      case NOTHING ->
          text.append("Its fold added nothing to `")
              .append(main)
              .append("`, so no work of this item was lost: what it carried is already released.");
      case UNRELEASED ->
          text.append("Its fold held commits `")
              .append(main)
              .append("` does not have, so the work on `")
              .append(withdrawn.deletedBranch())
              .append("` was not released; ask for its release again.");
      case UNKNOWN ->
          text.append("Whether its fold added anything to `")
              .append(main)
              .append("` could not be established.");
    }
    return text.toString();
  }

  private void redraw(String projectId) {
    try {
      publisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not fire the TICKETS hint for project %s", projectId);
    }
  }
}
