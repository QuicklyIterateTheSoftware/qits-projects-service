package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.control.WorkBranches;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * {@link BranchWorkItems} out of the entities module (epic qits-1440): the work item a branch names,
 * for the delivery desk's {@code manual-review-verified} rule, for the feature classification of
 * a release request's sources ({@code FeatureBranches}, epic qits-1580), and for the items a release
 * request answers with (qits-1673, which also reads {@code external/} branches).
 *
 * <p>{@code ticket/<qualified id>}, {@code epic/<qualified id>} and {@code task/<qualified id>} name
 * the ticket, epic or task with that qualified id, and so does an agent's {@code <that
 * branch>-<anything>} ({@link WorkBranches#isAgentBranch}). The whole name is tried first, then each
 * shorter prefix ending before a hyphen — {@code WorkItemApprovalComments}' reading, with the task
 * prefix beside it. Resolved in process through {@link EntityIdResolver}, each read its own short
 * transaction, so callable inside the decision's transaction on the other datasource.
 */
@ApplicationScoped
@DefaultBean
public class EntityBranchWorkItems implements BranchWorkItems {

  private static final Logger LOG = Logger.getLogger(EntityBranchWorkItems.class);

  /** Each work-item branch prefix and the archetype it names. */
  static final Map<String, Archetype> PREFIXES =
      Map.of("ticket/", Archetype.TICKET, "epic/", Archetype.EPIC, "task/", Archetype.TASK);

  @Inject EntityIdResolver resolver;

  @Override
  public Optional<WorkItem> workItemOf(String branch) {
    if (branch == null) {
      return Optional.empty();
    }
    for (Map.Entry<String, Archetype> prefix : PREFIXES.entrySet()) {
      if (branch.startsWith(prefix.getKey())) {
        try {
          return resolve(branch, prefix.getKey(), prefix.getValue());
        } catch (RuntimeException e) {
          LOG.debugf(e, "Could not resolve the work item of branch %s", branch);
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  /**
   * {@inheritDoc}
   *
   * <p>An {@code external/} branch is read the way a work-item branch is — the whole rest, then
   * each shorter prefix ending before a hyphen — with no archetype to match and no agent-branch
   * shape to hold it to: {@code external/qits-12-fix-typo} names {@code qits-12}, whatever it is.
   */
  @Override
  public Optional<WorkItem> releaseItemOf(String branch) {
    if (branch == null || !branch.startsWith(EXTERNAL)) {
      return workItemOf(branch);
    }
    try {
      for (String candidate : candidates(branch.substring(EXTERNAL.length()))) {
        Optional<CommitSubjectEntities.NamedEntity> item =
            CommitSubjectEntities.parse(candidate).flatMap(resolver::lookup);
        if (item.isPresent()) {
          return Optional.of(workItem(item.get()));
        }
      }
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not resolve the work item of branch %s", branch);
    }
    return Optional.empty();
  }

  /** The branch an outside contributor pushes, named for the item it is about. */
  static final String EXTERNAL = "external/";

  private Optional<WorkItem> resolve(String branch, String prefix, Archetype archetype) {
    for (String candidate : candidates(branch.substring(prefix.length()))) {
      Optional<CommitSubjectEntities.NamedEntity> item =
          CommitSubjectEntities.parse(candidate)
              .flatMap(resolver::lookup)
              .filter(found -> found.archetype() == archetype)
              .filter(found -> WorkBranches.isAgentBranch(prefix + found.qualifiedId(), branch));
      if (item.isPresent()) {
        return Optional.of(workItem(item.get()));
      }
    }
    return Optional.empty();
  }

  /** The whole name, then each shorter prefix ending before a hyphen. */
  private static List<String> candidates(String rest) {
    List<String> candidates = new ArrayList<>();
    candidates.add(rest);
    for (int dash = rest.lastIndexOf('-'); dash > 0; dash = rest.lastIndexOf('-', dash - 1)) {
      candidates.add(rest.substring(0, dash));
    }
    return candidates;
  }

  private static WorkItem workItem(CommitSubjectEntities.NamedEntity found) {
    return new WorkItem(
        found.qualifiedId(),
        found.projectId(),
        found.status(),
        found.archetype().name(),
        found.ticketType() == null ? null : found.ticketType().name(),
        found.title());
  }
}
