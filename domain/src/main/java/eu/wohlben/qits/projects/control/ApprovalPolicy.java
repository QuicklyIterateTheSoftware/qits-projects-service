package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether a release of this repository has to be approved by a person, on top of the build gate.
 *
 * <p>Today the answer is "it is the wrapper": a release of an archetype {@link
 * RepositoryArchetype#PROJECT} repository is the estate's own version moving, and letting a green
 * build alone ship one would make the single most consequential release the least deliberate. Every
 * other repository releases on its gates.
 *
 * <p><b>This is a seam, and the rule inside it is the placeholder.</b> What replaces it is the
 * primary quality gate — the step that asks whether the change did what it set out to do — and once
 * that exists, approval stops being a property of being a wrapper and becomes the <em>escalation</em>
 * from that gate: a person is asked when the gate cannot vouch for the work, whatever the repository
 * is. The archetype test is here because "the wrapper" is the population that needs the escalation
 * first, not because being a wrapper is what approval means. When the gate lands, this method's body
 * changes and nothing that calls it does.
 *
 * <p>Which is the whole point of it being one method in one class: <b>nothing else in this domain
 * may ask about the archetype to decide this question</b>. An {@code archetype == PROJECT} spelled
 * inline at the gate, in the API or in the announcement is a second answer that the quality gate
 * would have to find and replace one by one, and one of them would be missed. Ask here.
 *
 * <p><b>A repository with no row requires no approval.</b> A release request outlives the repository
 * it named — {@code repo_id} is a plain string and never a foreign key, for exactly that reason — so
 * this is asked for ids that no longer resolve, and the answer must be the one that lets settled
 * history be read rather than a hold nobody can ever satisfy.
 */
@ApplicationScoped
public class ApprovalPolicy {

  @Inject RepositoryRepository repositories;

  /** Whether a release of {@code repoId} needs a person's approval. See the class javadoc. */
  public boolean requiresApproval(String repoId) {
    return repositories
        .findByIdOptional(repoId)
        .map(r -> r.archetype == RepositoryArchetype.PROJECT)
        .orElse(false);
  }

  /**
   * Whether {@code repoId} is a repository whose tree <b>pins an estate</b> — a wrapper, whose
   * release is the whole platform's version moving and whose gitlinks therefore have to name what
   * its members have released before it may ship.
   *
   * <p><b>This is a different question from {@link #requiresApproval}, and the two must not be
   * conflated.</b> They read the same fact today and that is a coincidence of where the platform has
   * got to: approval is asked because the wrapper is the population that needs a person first, and
   * its rule is explicitly a placeholder that the primary quality gate replaces — after which
   * approval stops being about being a wrapper at all. Pinning an estate is not a placeholder for
   * anything. It is a property of what the repository <em>is</em>: a superproject has gitlinks and
   * nothing else does. One method changes its body on the day the quality gate lands and the other
   * does not, so they are two methods.
   *
   * <p><b>Both readings live in this one class, and that is what keeps the class's rule
   * enforceable.</b> The javadoc above forbids anything else in the domain from reading the archetype
   * to answer the approval question — a rule that only works while there is exactly one place the
   * archetype is read at all. A second reading spelled inline at the arming path would be the first
   * crack in it, and the next one would be argued for on the same grounds.
   *
   * <p><b>It is asked on the ARMING path, never on the release execution path.</b> Commit 4b12e6b
   * deliberately took the archetype question out of the execution: what a fold declares is read from
   * the fold itself — the presence of {@code .gitmodules} — because a release must act on the tree it
   * is about to tag and not on a row somebody could have re-typed. Arming is the other case and
   * legitimately needs the row: the question there is which requests this platform owes a pin
   * refresh, asked before any tree has been read and for a request that may have no fold yet. None of
   * {@code wrapperCatalogOf} or the catalogue machinery deleted with it comes back for this.
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
