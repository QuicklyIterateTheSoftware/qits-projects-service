package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRequirementSource;
import eu.wohlben.qits.projects.control.approval.ApprovalRoles;
import eu.wohlben.qits.projects.control.approval.ApprovalSubject;
import eu.wohlben.qits.projects.control.approval.ConfigDirectorySource;
import eu.wohlben.qits.projects.control.approval.FoldUnreadable;
import eu.wohlben.qits.projects.control.approval.ManualReviewSource;
import eu.wohlben.qits.projects.control.approval.NoChangesSource;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.ReleaseRequestApprovalRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>What a release request's current fold needs approved, and by whom</b> — the list of {@link
 * ApprovalRequirement}s (epic qits-1038). It used to be one boolean; it is now a list gathered from
 * every {@link ApprovalRequirementSource} bean, each requirement naming the approval roles that may
 * satisfy it. An empty list is a request nobody has to approve.
 *
 * <p><b>The sources</b>, in order: {@link ManualReviewSource} ({@code manual-review: true} at {@code
 * main}, a person), {@link ConfigDirectorySource} (the fold changes {@value #CONFIG_DIRECTORY}, a
 * person), {@code ArtifactDeclarationSource} (the fold only declares what a release publishes in
 * {@code release.yml}, a person or the request's own agent), {@link NoChangesSource} (the fold adds
 * nothing to {@code main}, qits-760, a person) and {@code ContractChangeSource} (the fold changes
 * golden masters another project consumes, a person or that project's agent, qits-1291). <b>A new kind of requirement is one more bean</b>:
 * this class, the gate, the doors, the DTO and the event read whatever the list holds.
 *
 * <p>"Changes" is the diff the request's {@code …/changes} view shows — {@code mergedSha} against
 * the newest release tag that does not contain it ({@link FoldChanges}) — so what a person is shown
 * and what the requirements count are one reading. The fold's content is read lazily and once per
 * question ({@link ApprovalSubject}). A request with no fold yet has no content, so only the
 * configuration applies to it. <b>A fold whose content cannot be read fails closed</b>: its read
 * becomes one requirement only a person may satisfy ({@link FoldUnreadable}), because "could not
 * look" must not be the cheapest way past a reviewer. The answer is derived from the current {@code
 * mergedSha} on every ask, and a decision is matched at that same sha, so a refold that drops a
 * cause drops its requirement and a refold that brings one back asks again.
 *
 * <p><b>The content is read only for a request still before its tag</b> — {@link #BEFORE_RELEASE}.
 * A settled request ({@code RELEASED} and after, {@code FAILED}, {@code WITHDRAWN}) reads no git at
 * all: its {@code release/<id>} ref is deleted, so the read would force a mirror fetch per row on
 * every list read and then fail closed into a {@code WAITING} on history nobody can approve any more.
 * For those the content half is replaced by the record: {@code manual-review} from the
 * configuration, otherwise an APPROVED decision recorded at the request's {@code mergedSha} reports
 * one {@value #RECORDED_ID} requirement admitting that decision's roles — which is what lets history
 * still say who approved.
 *
 * <p><b>Nothing else in this domain may answer this question</b> — not by reading {@code
 * manual-review}, not by diffing a fold, not by testing the archetype the way this class once did. A
 * second reading spelled inline at a gate, in the API or in the announcement is an answer that the
 * next change would have to find and replace one by one. Ask here.
 *
 * <p><b>A repository whose gate configuration cannot be read is not held by the configuration
 * source</b>, and that is not a hole: the evaluation has already refused to release an unknown gate
 * set before it reaches this question. <b>A repository with no row has no content half</b>: a
 * release request outlives the repository it named, and a hold nobody can ever satisfy is worse than
 * settled history being readable.
 */
@ApplicationScoped
public class ApprovalPolicy {

  private static final Logger LOG = Logger.getLogger(ApprovalPolicy.class);

  /** The directory whose change in a fold puts a person in front of it. */
  public static final String CONFIG_DIRECTORY = ConfigDirectorySource.DIRECTORY;

  /** The sentence for the configuration half of the rule. */
  static final String MANUAL_REVIEW_DETAIL = ManualReviewSource.REASON;

  /** The sentence for a fold that adds nothing to {@code main}. */
  public static final String NO_CHANGES_DETAIL = NoChangesSource.REASON;

  /** The one requirement a settled request reports for a recorded approval. */
  public static final String RECORDED_ID = "recorded";

  /** Its sentence. */
  public static final String RECORDED_REASON = "approved before it released";

  @Inject RepositoryRepository repositories;

  @Inject ReleaseGates gates;

  @Inject FoldChanges foldChanges;

  @Inject ReleaseRequestApprovalRepository approvals;

  @Inject ApprovalRoles roles;

  @Inject Instance<ApprovalRequirementSource> sources;

  /**
   * The states in which the fold's content is still a question — nothing has been tagged, so the
   * gate may yet hold it. Every other state is settled; see the class javadoc.
   */
  public static final Set<ReleaseRequest.State> BEFORE_RELEASE =
      EnumSet.of(
          ReleaseRequest.State.PENDING,
          ReleaseRequest.State.READY,
          ReleaseRequest.State.REJECTED,
          ReleaseRequest.State.CONFLICTED);

  /**
   * What {@code request} needs approved at its current fold. See the class javadoc. The answer every
   * decision reads: the gate set resolved strictly and the fold read afresh.
   */
  public List<ApprovalRequirement> requirementsFor(ReleaseRequest request) {
    if (isSettled(request.state)) {
      ReleaseRequestApproval recorded =
          request.mergedSha == null
              ? null
              : approvals
                  .latestFor(request.id, request.mergedSha)
                  .filter(decision -> decision.decision == ReleaseRequestApproval.Decision.APPROVED)
                  .orElse(null);
      return settled(gates.resolve(request.repoId), recorded);
    }
    return requirementsFor(request.repoId, request.projectId, request.mergedSha);
  }

  /**
   * The question for a request still before its tag, asked of a repository and a fold. {@code
   * mergedSha} may be null: a request with no fold yet.
   */
  public List<ApprovalRequirement> requirementsFor(String repoId, String mergedSha) {
    return requirementsFor(repoId, null, mergedSha);
  }

  private List<ApprovalRequirement> requirementsFor(
      String repoId, String projectId, String mergedSha) {
    ReleaseGates.GateSet set = gates.resolve(repoId);
    boolean exists =
        mergedSha != null
            && !mergedSha.isBlank()
            && repositories.findByIdOptional(repoId).isPresent();
    ApprovalSubject subject =
        new ApprovalSubject(
            repoId,
            projectId == null ? projectOf(repoId) : projectId,
            mergedSha,
            ReleaseRequest.State.PENDING,
            set,
            exists,
            foldChanges);
    List<ApprovalRequirement> all = new ArrayList<>(configuration(subject));
    all.addAll(content(subject).requirements());
    return merged(all);
  }

  /** Whether {@code state} is past the point where the fold's content is read. */
  public static boolean isSettled(ReleaseRequest.State state) {
    return state != null && !BEFORE_RELEASE.contains(state);
  }

  /**
   * A settled request: the configuration, or a recorded approval — never git.
   *
   * <p><b>An approval, not any decision.</b> A decline sitting at the fold of a request that settled
   * anyway is a decision the gate never asked for, and reading it as "somebody was required" would
   * rewrite that history into a declined release.
   *
   * @param recorded the newest decision at the fold where it is an approval, else null
   */
  private List<ApprovalRequirement> settled(
      ReleaseGates.GateSet set, ReleaseRequestApproval recorded) {
    List<ApprovalRequirement> configured =
        configuration(
            new ApprovalSubject(null, null, null, ReleaseRequest.State.RELEASED, set, false, null));
    if (!configured.isEmpty() || recorded == null) {
      return configured;
    }
    return List.of(
        new ApprovalRequirement(
            RECORDED_ID, RECORDED_REASON, roles.rolesOf(recorded), false, null));
  }

  /**
   * <b>The same question for a read-only display</b> (qits-1203) — the release-request list and
   * single read — which has already read everything this needs: the gate set from {@link
   * ReleaseGates#forDisplay}, and the content requirements as {@link ApprovalContentRecorder} stored
   * them on the request (qits-1201), or {@link ApprovalContentRecorder#notYetRead()} where the
   * current fold has not been read yet. Nothing here touches git; a recorded approval's roles are
   * the row's own.
   *
   * <p>The rule is {@link #requirementsFor(ReleaseRequest)}'s, word for word; only where its inputs
   * come from differs. Nothing that decides a release may call this.
   *
   * @param recorded the newest decision at the fold where it is an approval, else null — read for a
   *     settled request only
   * @param content the content requirements of the request's current fold — empty for a settled
   *     request, which reads no git
   */
  public List<ApprovalRequirement> requirementsForDisplay(
      ReleaseGates.GateSet gateSet,
      ReleaseRequest.State state,
      ReleaseRequestApproval recorded,
      List<ApprovalRequirement> content) {
    if (isSettled(state)) {
      return settled(gateSet, recorded);
    }
    List<ApprovalRequirement> all =
        new ArrayList<>(
            configuration(new ApprovalSubject(null, null, null, state, gateSet, false, null)));
    all.addAll(content);
    return merged(all);
  }

  /**
   * The content half's answer for one fold.
   *
   * @param complete false where a read failed and a requirement says so (failing closed) — an
   *     answer about an outage rather than about the fold, which {@link ApprovalContentRecorder}
   *     therefore never stores
   */
  public record ContentReading(List<ApprovalRequirement> requirements, boolean complete) {

    static final ContentReading NOTHING = new ContentReading(List.of(), true);

    public ContentReading {
      requirements = List.copyOf(requirements);
    }
  }

  /**
   * The content half: what every content source requires of the fold. A read that fails is one
   * fail-closed requirement; a failure to read the fold's changes stops there, since every later
   * source would ask the same mirror again.
   *
   * <p>Git work on the repository's mirror (a refresh, then a handful of subprocesses); the mirror
   * read underneath looks a repository row up outside any transaction and so needs a request context
   * on the thread that calls it.
   */
  public ContentReading readContent(boolean repositoryExists, String repoId, String mergedSha) {
    if (mergedSha == null || mergedSha.isBlank() || !repositoryExists) {
      return ContentReading.NOTHING;
    }
    return content(
        new ApprovalSubject(
            repoId,
            projectOf(repoId),
            mergedSha,
            ReleaseRequest.State.PENDING,
            null,
            true,
            foldChanges));
  }

  private ContentReading content(ApprovalSubject subject) {
    if (!subject.foldReadable()) {
      return ContentReading.NOTHING;
    }
    List<ApprovalRequirement> all = new ArrayList<>();
    boolean complete = true;
    for (ApprovalRequirementSource source : ordered()) {
      if (!source.readsFold()) {
        continue;
      }
      try {
        all.addAll(source.requirementsFor(subject));
      } catch (FoldUnreadable unreadable) {
        all.add(unreadable.requirement());
        complete = false;
        if (unreadable.stopsReading()) {
          break;
        }
      }
    }
    return new ContentReading(merged(all), complete);
  }

  private List<ApprovalRequirement> configuration(ApprovalSubject subject) {
    List<ApprovalRequirement> all = new ArrayList<>();
    for (ApprovalRequirementSource source : ordered()) {
      if (!source.readsFold()) {
        all.addAll(source.requirementsFor(subject));
      }
    }
    return all;
  }

  private List<ApprovalRequirementSource> ordered() {
    List<ApprovalRequirementSource> all = new ArrayList<>();
    sources.forEach(all::add);
    all.sort(Comparator.comparingInt(ApprovalRequirementSource::order));
    return all;
  }

  /** One requirement per id — the first one wins, which is the earlier source's. */
  private static List<ApprovalRequirement> merged(List<ApprovalRequirement> all) {
    Map<String, ApprovalRequirement> byId = new LinkedHashMap<>();
    all.forEach(requirement -> byId.putIfAbsent(requirement.id(), requirement));
    return List.copyOf(byId.values());
  }

  /** The project of {@code repoId}, or null where it cannot be told. */
  private String projectOf(String repoId) {
    try {
      return repositories
          .findByIdOptional(repoId)
          .map(repository -> repository.project == null ? null : repository.project.id)
          .orElse(null);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not read the project of %s", repoId);
      return null;
    }
  }

  /**
   * Whether {@code mergedSha} is <b>known</b> to add nothing to {@code repoId}'s {@code main} — the
   * qits-760 reading above, asked on its own by the estate wrapper's backstop in {@link
   * ReleaseRequests} (qits-1273), which withdraws or holds such a fold instead of building {@code
   * main}'s head again and tagging it twice.
   *
   * <p><b>A read that fails answers false</b>, the opposite direction from {@link #readContent}, and
   * for the same reason: false sends the request down the ordinary path, where the approval gate
   * reads the same fold again and fails closed into a person. A guess of "empty" would withdraw a
   * request that may carry the estate's pins.
   */
  public boolean knownToAddNothingToMain(String repoId, String mergedSha) {
    if (mergedSha == null || mergedSha.isBlank()) {
      return false;
    }
    try {
      return foldChanges.addsNothingToMain(repoId, mergedSha);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read whether %s of %s changes anything against main; it is gated as ever: %s",
          mergedSha, repoId, e.getMessage());
      return false;
    }
  }

  /**
   * Whether {@code repoId} is a repository whose tree <b>pins an estate</b> — a wrapper, whose
   * release is the whole platform's version moving and whose gitlinks therefore have to name what
   * its members have released before it may ship.
   *
   * <p><b>This is a different question from {@link #requirementsFor(ReleaseRequest)}, and they no
   * longer read the same fact at all.</b> They did until the quality gates landed, and that was always a coincidence
   * of where the platform had got to: approval was asked because the wrapper was the population that
   * needed a person first, and its rule was a placeholder. Pinning an estate is not a placeholder for
   * anything. It is a property of what the repository <em>is</em>: a superproject has gitlinks and
   * nothing else does. So one method's body changed on the day the gate set landed and this one's did
   * not, which is why they were two methods, and the day arrived.
   *
   * <p><b>Both readings live in this one class, and that is what keeps the class's rule
   * enforceable.</b> The javadoc above forbids anything else in the domain from reading {@code
   * manual-review} to answer the approval question; the same rule holds here for the archetype, and
   * it only works while there is exactly one place each is read at all. A second reading spelled
   * inline at the arming path would be the first crack in it, and the next one would be argued for on
   * the same grounds.
   *
   * <p><b>It is asked on the ARMING path, never on the release execution path.</b> Commit 4b12e6b
   * deliberately took the archetype question out of the execution: what a fold declares is read from
   * the fold itself — the presence of {@code .gitmodules} — because a release must act on the tree it
   * is about to tag and not on a row somebody could have re-typed. Arming is the other case and
   * legitimately needs the row: the question there is which requests this platform owes a pin
   * refresh, asked before any tree has been read and for a request that may have no fold yet. None of
   * {@code wrapperCatalogOf} or the catalogue machinery deleted with it comes back for this.
   *
   * <p><b>It reads the row and not the tree, and that is the last thing the archetype is read for
   * here.</b> The gate set is a tree reading of {@code main}; this one is the row, because arming is
   * asked before any tree has been read and for a request that may have no fold yet.
   *
   * <p>A repository with no row is <b>not</b> a wrapper, for the approval question's reason: a
   * release request outlives the repository it named, and a hold nobody can ever satisfy is worse
   * than settled history being readable.
   */
  public boolean isEstateWrapper(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(r -> r.archetype == RepositoryArchetype.PROJECT)
        .orElse(false);
  }
}
