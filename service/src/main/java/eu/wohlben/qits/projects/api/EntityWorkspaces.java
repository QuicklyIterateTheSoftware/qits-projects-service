package eu.wohlben.qits.projects.api;

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
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Where an entity's agent stands: the repository it is dispatched on and the wrapper branch it works
 * from, resolved once for everybody who has to name them. It was {@code TicketWorkspaces} until
 * qits-394 widened the one dispatch path to epics.
 *
 * <h2>The agent is found by its work item, the branch is only named</h2>
 *
 * <p>Since qits-1152 qits-workspaces keys an agent by the work item's id: every later call — a
 * turn, an entity signal, a lookup — names the work item and never the branch. The branch is
 * computed here for the dispatch, which tells the agent where to stand, and for the sentences a
 * thread is told. It comes from {@link WorkBranches}: {@code ticket/<qualified id>} or {@code
 * epic/<qualified id>}.
 *
 * <h2>The wrapper is the project, and neither archetype names a repository</h2>
 *
 * <p>A ticket is filed against work and not against a component; an epic spans the estate. So both
 * stand on the project's <b>wrapper</b> — the rule "The wrapper is the project" in AGENTS.md.
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

  private static final Logger LOG = Logger.getLogger(EntityWorkspaces.class);

  @Inject ProjectService projects;

  @Inject RepositoryService repositories;

  /**
   * An entity's agent address.
   *
   * @param wrapper the project's wrapper repository, whose {@code id} is the <b>catalog</b> id
   *     qits-workspaces keys agents' repositories by
   * @param branch the wrapper branch, from {@link WorkBranches}
   */
  record Target(Repository wrapper, String branch) {

    /** The catalog repository id the dispatch is addressed by. */
    String repositoryId() {
      return wrapper.id;
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
    String qualifiedId = qualifiedIdOf(entity);
    if (qualifiedId == null) {
      throw new DomainException(
          409,
          capitalised(entity)
              + " "
              + entity.id
              + " has no qualified id, so there is no branch to dispatch an agent onto.");
    }
    return new Target(wrapper, WorkBranches.of(entity, qualifiedId));
  }

  /**
   * The address, or <b>empty</b> where the project has no wrapper or the row no qualified id. For a
   * caller with nobody to refuse.
   */
  Optional<Target> find(WorkEntity entity) {
    Project project = projects.get(entity.projectId);
    Optional<Repository> wrapper =
        repositories.findByProjectAndName(project.id, ProjectService.wrapperName(project));
    String qualifiedId = wrapper.isEmpty() ? null : qualifiedIdOf(entity);
    if (qualifiedId == null) {
      return Optional.empty();
    }
    return Optional.of(new Target(wrapper.get(), WorkBranches.of(entity, qualifiedId)));
  }

  /**
   * What a workspace names this entity as — see {@link WorkspaceAgentDispatch.Subject} — <b>with its
   * qualified id</b>, {@code <project-slug>-<number>}, and the row's title, status name and blocked
   * flag as they stand now, so the agent's sessions read {@code <status square> qits-617 <title>}
   * from the first one (qits-614, qits-617). The three are read off {@code entity} with no further
   * read; every later change of them is {@link AgentEntitySignals}' to push.
   *
   * <p><b>Degrades, never throws.</b> The qualified id is a label on a dispatch somebody is waiting
   * for, and a label is not worth a refused press: a project row that does not resolve, a row with
   * no number, or a slug read that fails outright each leave the subject naming the row by id alone,
   * which is exactly what it said before the field existed. The rendering is {@link
   * QualifiedEntityIds#render}'s — the separator is decided there and nowhere else.
   */
  WorkspaceAgentDispatch.Subject subjectOf(WorkEntity entity) {
    WorkspaceAgentDispatch.Subject subject =
        bareSubjectOf(entity)
            .withEntityState(
                entity.title, entity.status, entity.blocked);
    String qualifiedId = qualifiedIdOf(entity);
    return qualifiedId == null ? subject : subject.withQualifiedId(qualifiedId);
  }

  /**
   * {@code <project-slug>-<number>}, or {@code null} where either half cannot be had — what the
   * subject is named by, and what the phase turns name the entity by and put in its commit subjects
   * ({@link PhasePrompts}, qits-301). Degrades as {@link #subjectOf} does.
   */
  String qualifiedIdOf(WorkEntity entity) {
    // A number is minted from 1 per project; anything below is a row that never got one.
    if (entity.projectId == null || entity.number < 1) {
      return null;
    }
    try {
      String slug = projects.slugsByIds(Set.of(entity.projectId)).get(entity.projectId);
      return slug == null || slug.isBlank() ? null : QualifiedEntityIds.render(slug, entity.number);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not resolve the qualified id of %s %s; dispatching it by id alone: %s",
          entity.archetype, entity.id, e.toString());
      return null;
    }
  }

  /** The row ids alone, by archetype. */
  private static WorkspaceAgentDispatch.Subject bareSubjectOf(WorkEntity entity) {
    return switch (entity.archetype) {
      case TICKET -> WorkspaceAgentDispatch.Subject.ticket(entity.id);
      case EPIC -> WorkspaceAgentDispatch.Subject.epic(entity.id);
      case FEATURE, TASK, CAMPAIGN ->
          throw new IllegalStateException("A " + entity.archetype + " is never dispatched");
    };
  }

  private static String capitalised(WorkEntity entity) {
    String noun = entity.archetype.name().toLowerCase(java.util.Locale.ROOT);
    return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
  }
}
