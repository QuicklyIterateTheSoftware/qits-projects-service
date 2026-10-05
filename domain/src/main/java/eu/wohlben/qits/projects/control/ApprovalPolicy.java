package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * </ul>
 *
 * <p>"Changes" is the diff the request's {@code …/changes} view shows — {@code mergedSha} against
 * the newest release tag that does not contain it ({@link FoldChanges}) — so what a person is shown
 * and what the gate counts are one reading. A request with no fold yet has no content, so only the
 * configuration applies to it; a fold whose changes cannot be read <b>requires approval</b>, because
 * "could not look" must not be the cheapest way past a person. The answer is derived from the
 * current {@code mergedSha} on every ask, and a decision is read at that same sha, so a refold that
 * drops the change clears the requirement and one that changes the directory again after an
 * approval asks again.
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

  @Inject RepositoryRepository repositories;

  @Inject ReleaseGates gates;

  @Inject FoldChanges foldChanges;

  /**
   * The answer to the approval question for one request.
   *
   * @param detail which rule applied — {@code configured by manual-review}, {@code changes
   *     .config/qits/: <paths>}, both joined with {@code "; "}, or the sentence saying the fold's
   *     changes could not be read; null exactly when {@code required} is false
   */
  public record ApprovalRequirement(boolean required, String detail) {

    public static final ApprovalRequirement NOT_REQUIRED = new ApprovalRequirement(false, null);
  }

  /** Whether {@code request} needs a person's approval at its current fold. See the class javadoc. */
  public ApprovalRequirement requirementFor(ReleaseRequest request) {
    return requirementFor(request.repoId, request.mergedSha);
  }

  /**
   * The same question asked of a repository and a fold, for the list read that caches per {@code
   * (repoId, mergedSha)}. {@code mergedSha} may be null: a request with no fold yet.
   */
  public ApprovalRequirement requirementFor(String repoId, String mergedSha) {
    List<String> reasons = new ArrayList<>(2);
    if (gates.resolve(repoId).requires(ReleaseGates.Kind.APPROVAL)) {
      reasons.add(MANUAL_REVIEW_DETAIL);
    }
    String content = contentReason(repoId, mergedSha);
    if (content != null) {
      reasons.add(content);
    }
    return reasons.isEmpty()
        ? ApprovalRequirement.NOT_REQUIRED
        : new ApprovalRequirement(true, String.join("; ", reasons));
  }

  /** The content half: a sentence where the fold changes {@link #CONFIG_DIRECTORY}, else null. */
  private String contentReason(String repoId, String mergedSha) {
    if (mergedSha == null || mergedSha.isBlank()) {
      return null;
    }
    if (repositories.findByIdOptional(repoId).isEmpty()) {
      return null;
    }
    List<CommitFileChangeDto> files;
    try {
      files = foldChanges.changes(repoId, mergedSha, CONFIG_DIRECTORY);
    } catch (RuntimeException e) {
      // FAIL CLOSED: the one answer that must not come out of an outage is "changes nothing".
      LOG.warnf(
          "Could not read whether %s of %s changes %s; requiring approval: %s",
          mergedSha, repoId, CONFIG_DIRECTORY, e.getMessage());
      return "the changes to " + CONFIG_DIRECTORY + " could not be read: " + e.getMessage();
    }
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
