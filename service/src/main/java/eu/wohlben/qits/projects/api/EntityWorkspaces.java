package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.control.WorkBranches;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Where an entity's agent stands: the repository a workspace is made on, the branch it works from
 * and the refs it may push, resolved once for everybody who has to name them. It was {@code
 * TicketWorkspaces} until qits-394 widened the one dispatch path to epics.
 *
 * <h2>Why this is a class and not six lines in each caller</h2>
 *
 * <p>Two flows address the same workspace. {@link EntityDispatch} stands one up on a press, and
 * {@link PhaseAdvance} speaks to the one already standing when a transition is recorded — and they
 * have to arrive at <b>the same address</b>, or the second talks to a branch the first never made
 * and the hand-off between phases silently stops working. That address is derived rather than
 * stored (the project's wrapper by name, the branch by the entity's slug), so "the same" is only
 * true while one piece of code computes it.
 *
 * <h2>The wrapper is the project, and neither archetype names a repository</h2>
 *
 * <p>A ticket is filed against work and not against a component; an epic spans the estate and its
 * <em>tasks</em> name repositories, one each, with no one of them the epic is about. So both stand
 * on the project's <b>wrapper</b> with {@code branchTree} — the rule "The wrapper is the project" in
 * AGENTS.md. The branch and its refs come from {@link WorkBranches}, the one place that computes
 * both: {@code ticket/<slug>} pushing its own branch, or {@code epic/<slug>} pushing the epic branch
 * plus every feature and task branch of the epic as it stands when the address is resolved.
 *
 * <p><b>An epic's refs are read at resolution time</b>, which for a dispatch at REPORTED is before
 * refinement has finished writing the tree. The epic's agent works on {@code epic/<slug>}, which is
 * always in the list; a feature or task branch minted by refinement after the press is not, until
 * the next press re-resolves it. Stated rather than fixed: qits-workspaces fixes a workspace's refs
 * at creation, and widening them later is a change to that service.
 *
 * <h2>Two verbs, because a missing wrapper means different things to the two callers</h2>
 *
 * <p>{@link #require} refuses with the dispatch door's own <b>409</b>, word for word as that door
 * has always phrased it: somebody pressed a button and the sentence has to reach them. {@link #find}
 * answers empty instead, because the advance path is told <em>after</em> a transition that is
 * already recorded and has nobody to refuse.
 */
@ApplicationScoped
class EntityWorkspaces {

  @Inject ProjectService projects;

  @Inject RepositoryService repositories;

  /** An epic's features and tasks name the branches its agent may push. */
  @Inject WorkEntityService entities;

  /**
   * An entity's workspace address.
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

    /** {@code ticket/<slug>} or {@code epic/<slug>} — the branch every phase runs on. */
    String branch() {
      return scope.branch();
    }
  }

  /**
   * The address, or the <b>409</b> that says this project has no wrapper to make one on. For a
   * caller somebody is waiting on.
   */
  Target require(WorkEntity entity) {
    Project project = projects.get(entity.projectId);
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
    return new Target(wrapper, scopeOf(entity));
  }

  /** The address, or <b>empty</b> where the project has no wrapper. For a caller with nobody to refuse. */
  Optional<Target> find(WorkEntity entity) {
    Project project = projects.get(entity.projectId);
    return repositories
        .findByProjectAndName(project.id, ProjectService.wrapperName(project))
        .map(wrapper -> new Target(wrapper, scopeOf(entity)));
  }

  /** The branch alone, with no catalog read — for the release ask, which has its own repository. */
  static String branchOf(WorkEntity entity) {
    return switch (entity.archetype) {
      case TICKET -> WorkBranches.ticket(entity).branch();
      case EPIC -> WorkBranches.epic(entity, List.of(), feature -> List.of())
          .branch();
      case FEATURE, TASK ->
          throw new IllegalStateException("A " + entity.archetype + " is never dispatched");
    };
  }

  /** What a workspace names this entity as — see {@link WorkspaceAgentDispatch.Subject}. */
  static WorkspaceAgentDispatch.Subject subjectOf(WorkEntity entity) {
    return switch (entity.archetype) {
      case TICKET -> WorkspaceAgentDispatch.Subject.ticket(entity.id);
      case EPIC -> WorkspaceAgentDispatch.Subject.epic(entity.id);
      case FEATURE, TASK ->
          throw new IllegalStateException("A " + entity.archetype + " is never dispatched");
    };
  }

  private WorkBranches.Scope scopeOf(WorkEntity entity) {
    return switch (entity.archetype) {
      case TICKET -> WorkBranches.ticket(entity);
      case EPIC ->
          WorkBranches.epic(
              entity,
              entities.listChildren(Archetype.FEATURE, entity.id),
              feature -> entities.listChildren(Archetype.TASK, feature.entity().id));
      case FEATURE, TASK ->
          throw new IllegalStateException("A " + entity.archetype + " is never dispatched");
    };
  }
}
