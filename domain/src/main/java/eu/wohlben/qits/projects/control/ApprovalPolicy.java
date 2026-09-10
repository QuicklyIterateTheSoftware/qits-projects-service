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
}
