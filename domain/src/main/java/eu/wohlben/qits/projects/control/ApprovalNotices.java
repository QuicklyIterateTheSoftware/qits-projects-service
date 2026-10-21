package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRoles;
import eu.wohlben.qits.projects.control.approval.ApprovalStanding;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.persistence.ReleaseRequestApprovalNoticeRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>What is announced about a fold's approval requirements, and the once-only rule behind it</b>
 * (epic qits-1038). Each requirement of a fold is in one state — OPEN, SATISFIED or DECLINED — and a
 * re-fold leaves the old fold's requirements VOID. Each (request, fold, requirement, state) is said
 * <b>once</b>: {@code release_request_approval_notice} records it in the caller's transaction ({@code
 * insert … on conflict do nothing}), and only the transaction whose insert landed announces it —
 * <b>after its commit</b>, the way {@link ReleaseRequestStates} announces a state, so a rolled-back
 * transaction says nothing and a restart never says anything twice.
 *
 * <p>Two things are said: {@code ApprovalRequirementChanged} through {@link
 * ApprovalRequirementAnnouncer}, for every state, and the work-item comment through {@link
 * ApprovalRequirementComments}, for OPEN alone — once per (request, fold, requirement), since OPEN is
 * recorded once.
 *
 * <p><b>When.</b> {@link #reconcile} with {@code gateReached}: {@code ReleaseRequests.evaluate} has
 * reached the approval gate on the fold — the automations have passed, so the fold is final — and
 * every requirement's state is recorded. Without it (a door's decision, which can land while CI still
 * runs): only requirements already announced at this fold move on, so nothing opens before the gate
 * does. {@link #voidLeftBehind}: on every evaluation of a fold, every requirement announced at an
 * older fold of the request and not yet VOID.
 */
@ApplicationScoped
public class ApprovalNotices {

  private static final Logger LOG = Logger.getLogger(ApprovalNotices.class);

  private static final Object PENDING = ApprovalNotices.class.getName() + ".pending";

  @Inject ReleaseRequestApprovalNoticeRepository notices;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject ApprovalRoles roles;

  @Inject Instance<ApprovalRequirementAnnouncer> announcers;

  @Inject Instance<ApprovalRequirementComments> comments;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  /** What one transaction owes the platform once it commits. */
  private record Owed(
      ApprovalRequirementAnnouncer.RequirementChange change,
      ApprovalRequirementComments.Opened opened) {}

  /**
   * Records — and, after the commit, announces — the state each of {@code requirements} is in at
   * {@code row}'s current fold. Inside the caller's transaction, with the row already locked.
   *
   * @param gateReached whether the approval gate was reached on this fold; see the class javadoc
   */
  public void reconcile(
      ReleaseRequest row,
      List<ApprovalRequirement> requirements,
      ApprovalStanding standing,
      boolean gateReached) {
    if (row.mergedSha == null || requirements.isEmpty()) {
      return;
    }
    try {
      Set<String> announcedHere = new LinkedHashSet<>();
      if (!gateReached) {
        for (ReleaseRequestApprovalNotice notice : notices.listByRequest(row.id)) {
          if (row.mergedSha.equals(notice.mergedSha)) {
            announcedHere.add(notice.requirementId);
          }
        }
        if (announcedHere.isEmpty()) {
          return;
        }
      }
      List<ReleaseRequestSource> named = sources.listByRequest(row.id);
      Instant now = Instant.now();
      for (ApprovalStanding.Entry entry : standing.entries()) {
        ApprovalRequirement requirement = entry.requirement();
        if (!gateReached && !announcedHere.contains(requirement.id())) {
          continue;
        }
        ReleaseRequestApprovalNotice.State state =
            ReleaseRequestApprovalNotice.State.valueOf(entry.state());
        List<String> admits = roles.renderAll(requirement.admits());
        boolean recorded =
            notices.recordIfAbsent(
                row.id,
                row.mergedSha,
                requirement.id(),
                state,
                requirement.reason(),
                String.join(" ", admits),
                requirement.excludesAuthors(),
                requirement.detail(),
                now);
        if (!recorded) {
          continue;
        }
        owe(
            row.id + "|" + row.mergedSha + "|" + requirement.id() + "|" + state,
            new Owed(
                change(
                    row,
                    row.mergedSha,
                    requirement.id(),
                    requirement.reason(),
                    admits,
                    requirement.excludesAuthors(),
                    named,
                    state,
                    now),
                state == ReleaseRequestApprovalNotice.State.OPEN
                    ? new ApprovalRequirementComments.Opened(
                        row.id,
                        row.qualifiedId,
                        row.projectId,
                        row.repoName,
                        row.mergedSha,
                        requirement.id(),
                        requirement.reason(),
                        admits,
                        requirement.detail(),
                        branchesOf(named))
                    : null));
      }
    } catch (RuntimeException e) {
      // Bookkeeping about an announcement must never fail the decision it rides on.
      LOG.warnf(e, "Could not record the approval notices of release request %s", row.id);
    }
  }

  /**
   * Records — and, after the commit, announces — VOID for every requirement announced at a fold of
   * {@code row} other than its current one and not voided yet. Inside the caller's transaction.
   */
  public void voidLeftBehind(ReleaseRequest row) {
    if (row.mergedSha == null) {
      return;
    }
    try {
      List<ReleaseRequestApprovalNotice> all = notices.listByRequest(row.id);
      Map<String, ReleaseRequestApprovalNotice> behind = new LinkedHashMap<>();
      Set<String> voided = new LinkedHashSet<>();
      for (ReleaseRequestApprovalNotice notice : all) {
        if (row.mergedSha.equals(notice.mergedSha)) {
          continue;
        }
        String key = notice.mergedSha + "|" + notice.requirementId;
        if (ReleaseRequestApprovalNotice.State.VOID.name().equals(notice.state)) {
          voided.add(key);
        } else {
          behind.putIfAbsent(key, notice);
        }
      }
      voided.forEach(behind::remove);
      if (behind.isEmpty()) {
        return;
      }
      List<ReleaseRequestSource> named = sources.listByRequest(row.id);
      Instant now = Instant.now();
      for (ReleaseRequestApprovalNotice left : behind.values()) {
        if (!notices.recordIfAbsent(
            row.id,
            left.mergedSha,
            left.requirementId,
            ReleaseRequestApprovalNotice.State.VOID,
            left.reason,
            left.admits,
            left.excludesAuthors,
            left.detail,
            now)) {
          continue;
        }
        owe(
            row.id + "|" + left.mergedSha + "|" + left.requirementId + "|VOID",
            new Owed(
                change(
                    row,
                    left.mergedSha,
                    left.requirementId,
                    left.reason,
                    left.admits == null || left.admits.isBlank()
                        ? List.of()
                        : List.of(left.admits.trim().split("\\s+")),
                    left.excludesAuthors,
                    named,
                    ReleaseRequestApprovalNotice.State.VOID,
                    now),
                null));
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not void the approval notices of release request %s", row.id);
    }
  }

  private static ApprovalRequirementAnnouncer.RequirementChange change(
      ReleaseRequest row,
      String mergedSha,
      String requirementId,
      String reason,
      List<String> admits,
      boolean excludesAuthors,
      List<ReleaseRequestSource> named,
      ReleaseRequestApprovalNotice.State state,
      Instant at) {
    return new ApprovalRequirementAnnouncer.RequirementChange(
        row.id,
        row.qualifiedId,
        row.projectId,
        row.repoId,
        row.repoName,
        mergedSha,
        requirementId,
        reason,
        List.copyOf(admits),
        excludesAuthors,
        authorsOf(named),
        state.name(),
        at);
  }

  /** The request's authors: the principals that added its branch sources, in order, once each. */
  public static List<String> authorsOf(List<ReleaseRequestSource> named) {
    Set<String> authors = new LinkedHashSet<>();
    for (ReleaseRequestSource source : named) {
      if (source.kind == ReleaseRequestSource.Kind.BRANCH
          && source.addedBy != null
          && !source.addedBy.isBlank()) {
        authors.add(source.addedBy);
      }
    }
    return List.copyOf(authors);
  }

  private static List<String> branchesOf(List<ReleaseRequestSource> named) {
    return named.stream()
        .filter(source -> source.kind == ReleaseRequestSource.Kind.BRANCH)
        .map(source -> source.name)
        .toList();
  }

  // ---- after the commit ------------------------------------------------------------------------

  private void owe(String key, Owed owed) {
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      say(owed);
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Owed> pending = (Map<String, Owed>) registry.getResource(PENDING);
    if (pending == null) {
      pending = new LinkedHashMap<>();
      registry.putResource(PENDING, pending);
      Map<String, Owed> captured = pending;
      registry.registerInterposedSynchronization(
          new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
              if (status == Status.STATUS_COMMITTED) {
                new ArrayList<>(captured.values()).forEach(ApprovalNotices.this::say);
              }
            }
          });
    }
    pending.putIfAbsent(key, owed);
  }

  private void say(Owed owed) {
    if (announcers.isResolvable()) {
      try {
        announcers.get().onRequirementChanged(owed.change());
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not announce approval requirement %s of release request %s as %s",
            owed.change().requirementId(),
            owed.change().releaseRequestId(),
            owed.change().state());
      }
    }
    if (owed.opened() != null && comments.isResolvable()) {
      try {
        comments.get().opened(owed.opened());
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not tell the work item of release request %s that %s opened",
            owed.opened().releaseRequestId(),
            owed.opened().requirementId());
      }
    }
  }

  private TransactionSynchronizationRegistry activeTransaction() {
    if (!transactions.isResolvable()) {
      return null;
    }
    try {
      TransactionSynchronizationRegistry registry = transactions.get();
      int status = registry.getTransactionStatus();
      return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK
          ? registry
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
