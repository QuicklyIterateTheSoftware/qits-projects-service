package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The release requests' rows. Plain CRUD and nothing else — the caller ({@code ReleaseRequests})
 * owns every transaction, because the writers are a request thread, the bus consumption and the
 * sweep, and each brackets itself.
 */
@ApplicationScoped
public class ReleaseRequestRepository implements PanacheRepositoryBase<ReleaseRequest, String> {

  /**
   * <b>Open means NOT FINISHED</b> — every state but the three a request never leaves: FINALIZED,
   * WITHDRAWN and OBSOLETE. REJECTED is in the set deliberately: a rejected request comes back to
   * life when the fix lands, which is the merge-request shape of the whole aggregate. CONFLICTED is
   * in it for exactly the same reason — the next push that makes the fold succeed clears it, and a
   * request nothing re-merges is a request nothing can ever clear.
   *
   * <p><b>RELEASED joined it with ticket b27384a3.</b> A release is a tag, and what the tag released
   * has still to publish, to deploy and to reach {@code main}; a request that vanished from every
   * listing at the tag was a publish failure nobody was holding open. So this set is what a default
   * listing answers and what "still happening here" means — and it is deliberately <b>not</b> the
   * set that decides whether a request may still be folded, added to or approved, because a RELEASED
   * request may not be. That is {@link #UNRELEASED}, one field down, and every write path reads it.
   */
  public static final List<ReleaseRequest.State> OPEN =
      List.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.RELEASED,
          ReleaseRequest.State.FAILED,
          ReleaseRequest.State.REJECTED,
          ReleaseRequest.State.CONFLICTED);

  /**
   * <b>{@link #OPEN} minus RELEASED: the states in which a request's content can still move.</b>
   * What a push re-arms, what a create converges onto, what the gate sweep visits and what a fold
   * may write to.
   *
   * <p>The two sets were one word until RELEASED became open, and separating them is the whole of
   * what keeps that change safe. A released request's fold has been tagged: re-folding it would
   * re-arm a request whose content already shipped, converging onto it would add a branch to a
   * release that has happened, and approving it would be a row nothing reads. Every one of those
   * paths reads this set, and only the reading paths read {@link #OPEN}.
   */
  public static final List<ReleaseRequest.State> UNRELEASED =
      List.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.FAILED,
          ReleaseRequest.State.REJECTED,
          ReleaseRequest.State.CONFLICTED);

  /**
   * The unreleased request of this repository that already names {@code branch} as a source, if any
   * — what the converge-on-create rule converges on, and what a push to that branch re-merges.
   *
   * <p>A subquery rather than a join: the child rows are not mapped as an association (the parent is
   * loaded by id, never navigated), and this read wants the parent alone.
   */
  public Optional<ReleaseRequest> findUnreleasedByBranch(String repoId, String branch) {
    return findUnreleasedByBranches(repoId, List.of(branch)).stream().findFirst();
  }

  /**
   * Every unreleased request of this repository naming any of {@code branches} as a named source,
   * oldest first. The push consumption's read: one push touches one branch and may participate in
   * several requests, and <b>each of them re-merges on its own</b> — a shared trigger is never a
   * shared merge.
   *
   * <p>{@link #UNRELEASED} and not {@link #OPEN}: a push to a branch a released request named is a
   * push to a branch that release already consumed, and re-folding on it would re-arm a request
   * whose tag is cut.
   */
  public List<ReleaseRequest> findUnreleasedByBranches(
      String repoId, Collection<String> branches) {
    if (branches.isEmpty()) {
      return List.of();
    }
    return list(
        "repoId = ?1 and state in ?2 and id in"
            + " (select s.requestId from ReleaseRequestSource s where s.name in ?3)"
            + " order by createdAt",
        repoId,
        UNRELEASED,
        branches);
  }

  /**
   * Every request still pending a verdict for one commit — what an arriving verdict resolves. The
   * commit is the request's <b>merged</b> sha: the fold is what CI built and what a verdict is
   * about.
   */
  public List<ReleaseRequest> findPendingByCommit(String repoId, String commitSha) {
    return list(
        "repoId = ?1 and mergedSha = ?2 and state = ?3",
        repoId,
        commitSha,
        ReleaseRequest.State.PENDING);
  }

  /**
   * Every request this commit's verdict <b>rejected</b> and could therefore un-reject — REJECTED, at
   * this exact fold, and carrying the run that rejected it.
   *
   * <p><b>A sibling of {@link #findPendingByCommit} rather than a widening of it, and that is the
   * point.</b> The two answer different questions: that one is "who is waiting for this verdict",
   * this one is "whose rejection might this verdict have answered". Widening the first to {@code
   * state in (PENDING, REJECTED)} would put both through the one path and make the caller re-derive
   * which it had, and — the part that matters — it would hand the verdict path every REJECTED
   * request including the ones a person declined. The {@code rejectingRunId is not null} clause here
   * is what keeps a human decline out of a query about builds; see {@link
   * ReleaseRequest#rejectingRunId}.
   *
   * <p>It does not decide anything. Whether an arriving verdict actually superseded the run each row
   * names is {@code ReleaseRequests.onVerdict}'s, against what the ledger reported it cleared.
   */
  public List<ReleaseRequest> findRejectedByCommit(String repoId, String commitSha) {
    return list(
        "repoId = ?1 and mergedSha = ?2 and state = ?3 and rejectingRunId is not null",
        repoId,
        commitSha,
        ReleaseRequest.State.REJECTED);
  }

  /**
   * Every unreleased request, oldest first — the gate sweep's worklist. A RELEASED request is open
   * but has no gate left in front of its tag; what is still owed for it is the publish phase's, and
   * {@code ReleaseFinalization.sweep} is the belt under that.
   */
  public List<ReleaseRequest> listUnreleased() {
    return list("state in ?1 order by createdAt", UNRELEASED);
  }

  /**
   * Every unreleased request of one repository, oldest first — the worklist of a trigger that is
   * about the <b>repository</b> rather than about a branch: a sibling release adding an implicit
   * tag, or one reaching {@code main} and leaving the set.
   */
  public List<ReleaseRequest> listUnreleasedByRepo(String repoId) {
    return list("repoId = ?1 and state in ?2 order by createdAt", repoId, UNRELEASED);
  }

  /**
   * The repository's requests that have released and not been finalized, oldest first — what a fresh
   * request of the same repository <b>obsoletes</b>. See {@code ReleaseRequests.request}.
   */
  public List<ReleaseRequest> listReleasedUnfinalized(String repoId) {
    return list(
        "repoId = ?1 and state = ?2 order by createdAt", repoId, ReleaseRequest.State.RELEASED);
  }

  /**
   * One repository's requests in the named states, newest first. The state set is the caller's,
   * because "which requests" is a question about the reading and not about the repository — see
   * {@code ReleaseRequests.statesFor} for the vocabulary and for what an absent one means.
   */
  public List<ReleaseRequest> listByRepo(
      String repoId, Collection<ReleaseRequest.State> states) {
    if (states.isEmpty()) {
      return List.of();
    }
    return list("repoId = ?1 and state in ?2 order by createdAt desc", repoId, states);
  }

  /**
   * The repository's last {@code limit} <b>finalized</b> requests, most recently moved first — the
   * tail the default reading adds to the open set, so that a list which is otherwise "what is still
   * waiting" also says what has just landed.
   *
   * <p><b>FINALIZED and no longer RELEASED</b>, because RELEASED is in the open set now: a released
   * request is already on the page as work in flight, and topping the same page up with it would
   * list it twice. What a reader wants behind the open ones is what has <em>finished</em>, which is
   * exactly the state a request leaves the open set for.
   *
   * <p>Ordered and paged by {@code updatedAt} rather than by {@code createdAt}: what makes a release
   * recent is when it <em>landed</em>, and a request asked for a week ago that finalized this
   * morning is the one somebody is looking for. It is a page and not a filter on purpose — a
   * repository with a year of releases must cost the same read as one with three.
   */
  public List<ReleaseRequest> listRecentFinalized(String repoId, int limit) {
    return find(
            "repoId = ?1 and state = ?2 order by updatedAt desc",
            repoId,
            ReleaseRequest.State.FINALIZED)
        .page(0, limit)
        .list();
  }

  /** {@link #listRecentFinalized} across every repository of one project. */
  public List<ReleaseRequest> listRecentFinalizedByProject(String projectId, int limit) {
    return find(
            "projectId = ?1 and state = ?2 order by updatedAt desc",
            projectId,
            ReleaseRequest.State.FINALIZED)
        .page(0, limit)
        .list();
  }

  /**
   * One project's requests in the named states, across every repository it owns, <b>most recently
   * moved first</b>. Ordered by {@code updatedAt} rather than {@code createdAt} because this list is
   * read as a worklist: a re-armed request that has been waiting a week is the live one, and burying
   * it under a request created an hour ago and untouched since would be the wrong answer.
   *
   * <p>{@code projectId} is the column on the row, denormalised at creation. A repository with no
   * project has null there and appears in no project's list, which is correct — nothing addresses it
   * through a project.
   */
  public List<ReleaseRequest> listByProject(
      String projectId, Collection<ReleaseRequest.State> states) {
    if (states.isEmpty()) {
      return List.of();
    }
    return list("projectId = ?1 and state in ?2 order by updatedAt desc", projectId, states);
  }
}
