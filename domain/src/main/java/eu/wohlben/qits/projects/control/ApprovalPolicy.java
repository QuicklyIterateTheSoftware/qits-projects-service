package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.ReleaseRequestApprovalRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Whether a release request has to be approved by a person.
 *
 * <p><b>The answer is the repository's configuration OR the request's own content</b>, and it is a
 * question about one request rather than about a repository:
 *
 * <ul>
 *   <li><b>{@code manual-review: true}</b> in the repository's {@code
 *       .config/qits/release-requests.yml}, read from its {@code main} through {@link ReleaseGates}.
 *       A repository whose graduation is a person reading a diff configures this and waits on
 *       nothing else.
 *   <li><b>The fold changes {@value #CONFIG_DIRECTORY}</b> in this repository's own tree — a platform
 *       rule, not a setting. That directory is what tells the platform how to gate, release and
 *       deploy the repository, so a request that rewrites it is changing the rules every later
 *       request of this repository is held by — switching {@code manual-review} off, dropping a QA
 *       slot, re-pointing a deployment — and that is a change a person sees before it ships.
 *       Added, modified, deleted and renamed paths all count, a rename by its old path as well as
 *       its new one, and machine requesters are not exempt. <b>Gitlinks never count</b>: a wrapper moving a pin moves
 *       somebody else's tree, which that repository's own request has already answered for.
 *   <li><b>The fold adds nothing to {@code main}</b> — a platform rule too (qits-760). A request
 *       whose branches carry nothing folds, by fast-forward, to {@code main}'s own head: a commit
 *       the last release already tagged and built, so the CI gate is vouched at once by that old run
 *       and the request could release before its own QA run exists. Read as {@code git diff
 *       main...mergedSha} being empty ({@link FoldChanges#addsNothingToMain}) — a different base from
 *       the one below, because the newest tag not containing such a fold is the release before it.
 *       Detail {@value #NO_CHANGES_DETAIL}. A {@code maintenance/*} bump is never held by it: its
 *       request is opened only for a branch ahead of {@code main}, carrying the bump's commit.
 * </ul>
 *
 * <p>"Changes" is the diff the request's {@code …/changes} view shows — {@code mergedSha} against
 * the newest release tag that does not contain it ({@link FoldChanges}) — so what a person is shown
 * and what the gate counts are one reading. A request with no fold yet has no content, so only the
 * configuration applies to it; a fold whose changes cannot be read <b>requires approval</b>, because
 * "could not look" must not be the cheapest way past a person — and so does one whose diff against
 * {@code main} cannot be read. The answer is derived from the current {@code mergedSha} on every
 * ask, and a decision is read at that same sha, so a refold that drops the change clears the
 * requirement and one that changes the directory again after an approval asks again; likewise a
 * refold that now carries something (an automation's commit, say) is no longer held for being
 * empty, and nothing remembers that an earlier fold was.
 *
 * <p><b>The content is read only for a request still before its tag</b> — {@link #BEFORE_RELEASE}.
 * A settled request ({@code RELEASED} and after, {@code FAILED}, {@code WITHDRAWN}) reads no git at
 * all: its {@code release/<id>} ref is deleted, so the read would force a mirror fetch per row on
 * every list read and then fail closed into a {@code WAITING} on history nobody can approve any more.
 * For those the content half is replaced by the record: approval was required if {@code
 * manual-review} is configured or an approval is recorded at the request's {@code mergedSha}, which
 * is what lets history still say who approved. The detail is then the configuration's sentence or
 * null — a recorded decision speaks for itself.
 *
 * <p>The answer carries a {@link ApprovalRequirement#detail sentence} saying which rule applied, so
 * the surface can say why a person is being asked rather than only that one is.
 *
 * <p><b>Nothing else in this domain may answer this question</b> — not by reading {@code
 * manual-review}, not by diffing a fold, not by testing the archetype the way this class once did. A
 * second reading spelled inline at a gate, in the API or in the announcement is an answer that the
 * next change would have to find and replace one by one, and one of them would be missed. Ask here.
 *
 * <p><b>A repository whose gate configuration cannot be read is not held by the configuration
 * half</b>, and that is not a hole: the evaluation has already refused to release an unknown gate set
 * before it reaches this question, so an unreadable configuration holds the request one gate
 * earlier, where the sentence a person reads says why.
 *
 * <p><b>A repository with no row requires no approval</b>: a release request outlives the repository
 * it named — {@code repo_id} is a plain string and never a foreign key, for exactly that reason — so
 * this is asked for ids that no longer resolve, and the answer must be the one that lets settled
 * history be read rather than a hold nobody can ever satisfy.
 */
@ApplicationScoped
public class ApprovalPolicy {

  private static final Logger LOG = Logger.getLogger(ApprovalPolicy.class);

  /** The directory whose change in a fold puts a person in front of it. */
  public static final String CONFIG_DIRECTORY = ".config/qits/";

  /** The sentence for the configuration half of the rule. */
  static final String MANUAL_REVIEW_DETAIL = "configured by manual-review";

  /** The sentence for a fold that adds nothing to {@code main}. */
  public static final String NO_CHANGES_DETAIL = "no changes against main: the fold adds nothing to main";

  @Inject RepositoryRepository repositories;

  @Inject ReleaseGates gates;

  @Inject FoldChanges foldChanges;

  @Inject ReleaseRequestApprovalRepository approvals;

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
   * The answer to the approval question for one request.
   *
   * @param detail which rule applied — {@code configured by manual-review}, {@code changes
   *     .config/qits/: <paths>}, {@value #NO_CHANGES_DETAIL}, several of those joined with {@code
   *     "; "}, or a sentence saying the fold could not be read; null when {@code required} is false,
   *     and on a settled request required only because an approval is recorded
   */
  public record ApprovalRequirement(boolean required, String detail) {

    public static final ApprovalRequirement NOT_REQUIRED = new ApprovalRequirement(false, null);
  }

  /** Whether {@code request} needs a person's approval at its current fold. See the class javadoc. */
  public ApprovalRequirement requirementFor(ReleaseRequest request) {
    if (isSettled(request.state)) {
      return settled(
          request.repoId,
          request.mergedSha != null
              && approvals
                  .latestFor(request.id, request.mergedSha)
                  .filter(decision -> decision.decision == ReleaseRequestApproval.Decision.APPROVED)
                  .isPresent());
    }
    return requirementFor(request.repoId, request.mergedSha);
  }

  /**
   * The same question for the list read, which has already batched the decisions: {@code
   * approvedAtFold} is whether the newest decision at the request's current {@code mergedSha} is an
   * approval, and is read only for a settled request.
   */
  public ApprovalRequirement requirementFor(
      String repoId, String mergedSha, ReleaseRequest.State state, boolean approvedAtFold) {
    return isSettled(state) ? settled(repoId, approvedAtFold) : requirementFor(repoId, mergedSha);
  }

  /** Whether {@code state} is past the point where the fold's content is read. */
  public static boolean isSettled(ReleaseRequest.State state) {
    return state != null && !BEFORE_RELEASE.contains(state);
  }

  /**
   * A settled request: the configuration, or a recorded approval — never git.
   *
   * <p><b>An approval, not any decision.</b> A decline sitting at the fold of a request that settled
   * anyway is a decision the gate never asked for — a repository nobody had to ask about releases
   * past it — and reading it as "a person was required" would rewrite that history into a declined
   * release.
   */
  private ApprovalRequirement settled(String repoId, boolean approvedAtFold) {
    return settled(gates.resolve(repoId), approvedAtFold);
  }

  private static ApprovalRequirement settled(ReleaseGates.GateSet set, boolean approvedAtFold) {
    if (set.requires(ReleaseGates.Kind.APPROVAL)) {
      return new ApprovalRequirement(true, MANUAL_REVIEW_DETAIL);
    }
    return approvedAtFold ? new ApprovalRequirement(true, null) : ApprovalRequirement.NOT_REQUIRED;
  }

  /**
   * <b>The same question for a read-only display</b> (qits-1203) — the release-request list and
   * single read — which has already read everything this needs: the gate set from {@link
   * ReleaseGates#forDisplay}, and the content half as {@link ApprovalContentRecorder} stored it on the
   * request (qits-1201), or {@link ApprovalContentRecorder#NOT_YET_READ} where it has not been read at
   * the current fold yet. Nothing here touches the database or git.
   *
   * <p>The rule is the one {@link #requirementFor(String, String, ReleaseRequest.State, boolean)}
   * applies, word for word; only where its inputs come from differs. Nothing that decides a release
   * may call this: a decision asks {@link #requirementFor(ReleaseRequest)}, which resolves the gate
   * set strictly and reads the fold afresh.
   *
   * @param contentReasons {@link #readContent}'s reasons for the request's current fold — empty for
   *     a settled request, which reads no git
   */
  public ApprovalRequirement requirementForDisplay(
      ReleaseGates.GateSet gateSet,
      ReleaseRequest.State state,
      boolean approvedAtFold,
      List<String> contentReasons) {
    if (isSettled(state)) {
      return settled(gateSet, approvedAtFold);
    }
    return combined(gateSet, contentReasons);
  }

  /**
   * The question for a request still before its tag, asked of a repository and a fold — the shape
   * the list read caches per {@code (repoId, mergedSha)}. {@code mergedSha} may be null: a request
   * with no fold yet.
   */
  public ApprovalRequirement requirementFor(String repoId, String mergedSha) {
    ReleaseGates.GateSet set = gates.resolve(repoId);
    boolean exists =
        mergedSha != null
            && !mergedSha.isBlank()
            && repositories.findByIdOptional(repoId).isPresent();
    return unsettled(set, exists, repoId, mergedSha);
  }

  private ApprovalRequirement unsettled(
      ReleaseGates.GateSet set, boolean repositoryExists, String repoId, String mergedSha) {
    return combined(set, readContent(repositoryExists, repoId, mergedSha).reasons());
  }

  private static ApprovalRequirement combined(
      ReleaseGates.GateSet set, List<String> contentReasons) {
    List<String> reasons = new ArrayList<>(3);
    if (set.requires(ReleaseGates.Kind.APPROVAL)) {
      reasons.add(MANUAL_REVIEW_DETAIL);
    }
    reasons.addAll(contentReasons);
    return reasons.isEmpty()
        ? ApprovalRequirement.NOT_REQUIRED
        : new ApprovalRequirement(true, String.join("; ", reasons));
  }

  /**
   * The content half's answer for one fold.
   *
   * @param complete false where a read failed and a reason says so (failing closed) — an answer about
   *     an outage rather than about the fold, which {@link ApprovalContentRecorder} therefore never
   *     stores
   */
  public record ContentReading(List<String> reasons, boolean complete) {

    static final ContentReading NOTHING = new ContentReading(List.of(), true);
  }

  /**
   * The content half: a sentence where the fold changes {@link #CONFIG_DIRECTORY}, then one where it
   * adds nothing to {@code main}; empty when neither holds. A fold whose changes could not be read is
   * one sentence and no second read: it is already held, and the same mirror would be asked again.
   *
   * <p>Git work on the repository's mirror (a refresh, then a handful of subprocesses); reads no
   * database itself, though the mirror read underneath looks a repository row up outside any
   * transaction and so needs a request context on the thread that calls it.
   */
  public ContentReading readContent(boolean repositoryExists, String repoId, String mergedSha) {
    if (mergedSha == null || mergedSha.isBlank()) {
      return ContentReading.NOTHING;
    }
    if (!repositoryExists) {
      return ContentReading.NOTHING;
    }
    List<CommitFileChangeDto> files;
    try {
      files = foldChanges.changes(repoId, mergedSha, CONFIG_DIRECTORY);
    } catch (RuntimeException e) {
      // FAIL CLOSED: the one answer that must not come out of an outage is "changes nothing".
      LOG.warnf(
          "Could not read whether %s of %s changes %s; requiring approval: %s",
          mergedSha, repoId, CONFIG_DIRECTORY, e.getMessage());
      return new ContentReading(
          List.of("the changes to " + CONFIG_DIRECTORY + " could not be read: " + e.getMessage()),
          false);
    }
    List<String> reasons = new ArrayList<>(2);
    String config = configReason(files);
    if (config != null) {
      reasons.add(config);
    }
    boolean complete = true;
    String empty;
    try {
      empty = foldChanges.addsNothingToMain(repoId, mergedSha) ? NO_CHANGES_DETAIL : null;
    } catch (RuntimeException e) {
      empty = emptyFoldUnreadable(repoId, mergedSha, e);
      complete = false;
    }
    if (empty != null) {
      reasons.add(empty);
    }
    return new ContentReading(List.copyOf(reasons), complete);
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
   * The sentence for a fold whose emptiness against {@code main} could not be read. The rule it stands
   * in for: a fold that adds nothing to {@code main} is a commit {@code main} already holds — a
   * fast-forward onto its own head, tagged and built by the last release — so the CI gate would be
   * vouched by that release's old run before this request's own QA run exists, and the request would
   * release having been checked by nothing. A person decides whether an empty release is wanted.
   * FAIL CLOSED, for the same reason as the configuration read beside it.
   */
  private static String emptyFoldUnreadable(String repoId, String mergedSha, RuntimeException e) {
    LOG.warnf(
        "Could not read whether %s of %s changes anything against main; requiring approval: %s",
        mergedSha, repoId, e.getMessage());
    return "whether the fold changes anything against main could not be read: " + e.getMessage();
  }

  /** A sentence where {@code files} touch {@link #CONFIG_DIRECTORY}, else null. */
  private static String configReason(List<CommitFileChangeDto> files) {
    Set<String> touched = new LinkedHashSet<>();
    for (CommitFileChangeDto file : files) {
      if (file.touchesGitlink()) {
        continue;
      }
      if (underConfig(file.oldPath())) {
        touched.add(file.oldPath());
      }
      if (underConfig(file.path())) {
        touched.add(file.path());
      }
    }
    return touched.isEmpty() ? null : "changes " + CONFIG_DIRECTORY + ": " + String.join(", ", touched);
  }

  private static boolean underConfig(String path) {
    return path != null && path.startsWith(CONFIG_DIRECTORY);
  }

  /**
   * Whether {@code repoId} is a repository whose tree <b>pins an estate</b> — a wrapper, whose
   * release is the whole platform's version moving and whose gitlinks therefore have to name what
   * its members have released before it may ship.
   *
   * <p><b>This is a different question from {@link #requirementFor(ReleaseRequest)}, and they no
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
