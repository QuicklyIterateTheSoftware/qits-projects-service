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
 * for the delivery desk's {@code manual-review-verified} rule and for the feature classification of
 * a release request's sources ({@code FeatureBranches}, epic qits-1580).
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

  private Optional<WorkItem> resolve(String branch, String prefix, Archetype archetype) {
    String rest = branch.substring(prefix.length());
    List<String> candidates = new ArrayList<>();
    candidates.add(rest);
    for (int dash = rest.lastIndexOf('-'); dash > 0; dash = rest.lastIndexOf('-', dash - 1)) {
      candidates.add(rest.substring(0, dash));
    }
    for (String candidate : candidates) {
      Optional<CommitSubjectEntities.NamedEntity> item =
          CommitSubjectEntities.parse(candidate)
              .flatMap(resolver::lookup)
              .filter(found -> found.archetype() == archetype)
              .filter(found -> WorkBranches.isAgentBranch(prefix + found.qualifiedId(), branch));
      if (item.isPresent()) {
        CommitSubjectEntities.NamedEntity found = item.get();
        return Optional.of(
            new WorkItem(
                found.qualifiedId(),
                found.projectId(),
                found.status(),
                found.archetype().name(),
                found.ticketType() == null ? null : found.ticketType().name()));
      }
    }
    return Optional.empty();
  }
}
