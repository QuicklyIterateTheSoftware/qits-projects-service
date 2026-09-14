package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.control.WorkBranches;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Where a ticket's agent stands: the repository a workspace is made on and the branch it works from,
 * resolved once for everybody who has to name them.
 *
 * <h2>Why this is a class and not six lines in each door</h2>
 *
 * <p>Two flows address the same workspace now. {@link TicketDispatchController} stands one up on a
 * press, and {@link TicketPhaseAdvance} speaks to the one already standing when a transition is
 * recorded — and they have to arrive at <b>the same address</b>, or the second talks to a branch the
 * first never made and the hand-off between phases silently stops working. That address is derived
 * rather than stored (the project's wrapper by name, {@code ticket/<slug>} by the ticket's slug), so
 * "the same" is not something the database would enforce; it is only true while one piece of code
 * computes it. Copying the resolution would make a drift between the two a compile-clean, test-green
 * change to one file, which is the most expensive shape a bug can have here.
 *
 * <h2>The wrapper is the project, and a ticket names no repository</h2>
 *
 * <p>A ticket is filed against work and not against a component, so there is no single repository to
 * stand a workspace on and guessing one from the text would be a guess the agent then has to work
 * around. The project's <b>wrapper</b> is the answer — the aggregate over the whole estate — which is
 * the rule "The wrapper is the project" in AGENTS.md, applied where {@code RefinementService} and the
 * dispatch door already apply it. The branch comes from {@link WorkBranches#ticket(Ticket)}, the one
 * place that computes a branch and the refs its agent may push, so a ref can never drift from the
 * branch it belongs to.
 *
 * <h2>Two verbs, because a missing wrapper means different things to the two callers</h2>
 *
 * <p>{@link #require(Ticket)} refuses with the dispatch door's own <b>409</b>, word for word as that
 * door has always phrased it: somebody pressed a button, nothing can be stood up, and the sentence
 * has to reach them. {@link #find(Ticket)} answers empty instead, because the advance path is told
 * <em>after</em> a transition that is already recorded and has nobody to refuse — a project without a
 * wrapper is simply a project where no workspace can be standing, which is the {@code NO_WORKSPACE}
 * case reached one step earlier and gets the same silence.
 *
 * <p>Keeping the 409 on the {@code require} arm alone is deliberate: it is a sentence about
 * dispatching ("…so there is nothing to dispatch an agent onto."), it is the one a person reads, and
 * it stays true of its only caller. Rewording it to also fit a path that never shows it to anybody
 * would trade an exact message for a vaguer one and buy nothing.
 */
@ApplicationScoped
class TicketWorkspaces {

  @Inject ProjectService projects;

  @Inject RepositoryService repositories;

  /**
   * A ticket's workspace address: the repository a workspace is made on and the branch — with the
   * refs its agent may push — that it stands on.
   *
   * @param wrapper the project's wrapper repository, whose {@code id} is the <b>catalog</b> id
   *     qits-workspaces keys workspaces by
   * @param scope the branch and its exact refs, from {@link WorkBranches}
   */
  record Target(Repository wrapper, WorkBranches.Scope scope) {

    /** The catalog repository id both workspace doors are addressed by. */
    String repositoryId() {
      return wrapper.id;
    }

    /** {@code ticket/<slug>} — the branch every phase of this ticket runs on. */
    String branch() {
      return scope.branch();
    }
  }

  /**
   * The address, or the <b>409</b> that says this project has no wrapper to make one on. For a
   * caller somebody is waiting on.
   */
  Target require(Ticket ticket) {
    Project project = projects.get(ticket.projectId);
    String wrapperName = ProjectService.wrapperName(project);
    Repository wrapper =
        repositories
            .findByProjectAndName(project.id, wrapperName)
            .orElseThrow(
                () ->
                    new DomainException(
                        409,
                        "Project "
                            + project.id
                            + " has no wrapper repository ("
                            + wrapperName
                            + "), so there is nothing to dispatch an agent onto."));
    return new Target(wrapper, WorkBranches.ticket(ticket));
  }

  /**
   * The address, or <b>empty</b> where the project has no wrapper. For a caller with nobody to
   * refuse: see the class javadoc — no wrapper means no workspace can be standing on that branch,
   * and that is an answer rather than a failure.
   */
  Optional<Target> find(Ticket ticket) {
    Project project = projects.get(ticket.projectId);
    return repositories
        .findByProjectAndName(project.id, ProjectService.wrapperName(project))
        .map(wrapper -> new Target(wrapper, WorkBranches.ticket(ticket)));
  }
}
