package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether a release of this repository has to be approved by a person.
 *
 * <p><b>The answer is what the repository configures</b>: {@code manual-review: true} in its {@code
 * .config/qits/release-requests.yml}, read from its {@code main} through {@link ReleaseGates}.
 * Approval is one member of that gate set, standing beside the CI and deployment gates rather than
 * behind either of them — a repository whose graduation is a person reading a diff configures this
 * and nothing else, and waits on nothing else.
 *
 * <p><b>It used to test the archetype, and that was always the placeholder.</b> A release of an
 * archetype {@link RepositoryArchetype#PROJECT} repository is the estate's own version moving, so
 * the wrapper was the population that needed a person first — but being a wrapper was never what
 * approval <em>meant</em>. The quality gates replaced the test, the wrapper still requires approval
 * because its own {@code main} says {@code manual-review: true}, and this method's body changed
 * while nothing that calls it did.
 *
 * <p>Which is the whole point of it being one method in one class, and the rule survives the swap:
 * <b>nothing else in this domain may read {@code manual-review} to decide this question</b>, exactly
 * as nothing else was allowed to read the archetype for it. A second reading spelled inline at a
 * gate, in the API or in the announcement is an answer that the next change would have to find and
 * replace one by one, and one of them would be missed. Ask here.
 *
 * <p><b>A repository whose gate configuration cannot be read requires no approval <em>here</em>, and
 * that is not a hole.</b> This is asked on the read path and from inside the evaluation, and the
 * evaluation has already refused to release an unknown gate set before it reaches this line — so an
 * unreadable configuration holds the request one gate earlier, where the sentence a person reads
 * says why. Answering "yes, ask somebody" here instead would put a person in front of a fold whose
 * rules nobody could establish.
 *
 * <p><b>A repository with no row requires no approval</b>, and follows from the same line: a release
 * request outlives the repository it named — {@code repo_id} is a plain string and never a foreign
 * key, for exactly that reason — so this is asked for ids that no longer resolve, and the answer must
 * be the one that lets settled history be read rather than a hold nobody can ever satisfy.
 */
@ApplicationScoped
public class ApprovalPolicy {

  @Inject RepositoryRepository repositories;

  @Inject ReleaseGates gates;

  /** Whether a release of {@code repoId} needs a person's approval. See the class javadoc. */
  public boolean requiresApproval(String repoId) {
    return gates.resolve(repoId).requires(ReleaseGates.Kind.APPROVAL);
  }

  /**
   * Whether {@code repoId} is a repository whose tree <b>pins an estate</b> — a wrapper, whose
   * release is the whole platform's version moving and whose gitlinks therefore have to name what
   * its members have released before it may ship.
   *
   * <p><b>This is a different question from {@link #requiresApproval}, and they no longer read the
   * same fact at all.</b> They did until the quality gates landed, and that was always a coincidence
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
   * <p>A repository with no row is <b>not</b> a wrapper, for {@link #requiresApproval}'s reason: a
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
