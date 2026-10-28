package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.entity.Archetype;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The work items a release request's branch sources name, for the comments written onto their
 * threads — {@link WorkItemApprovalComments} (epic qits-1038) and {@link WorkItemWithdrawalComments}
 * (qits-1671). Extracted from the first when the second needed the same reading, so the two cannot
 * disagree about which item a branch names.
 *
 * <p>{@code ticket/<qualified id>} names a ticket and {@code epic/<qualified id>} an epic, each also
 * with a {@code -<suffix>} an agent adds. The whole name is tried first, then each shorter prefix
 * ending before a hyphen. The lookup is handed in ({@link EntityIdResolver#lookup}, in production)
 * so a test can resolve without a database.
 */
final class WorkItemBranches {

  private static final Logger LOG = Logger.getLogger(WorkItemBranches.class);

  private WorkItemBranches() {}

  /** The work items the branches name, once each (by entity id), in branch order. */
  static Map<String, CommitSubjectEntities.NamedEntity> workItems(
      List<String> branches,
      Function<CommitSubjectEntities.QualifiedId, Optional<CommitSubjectEntities.NamedEntity>>
          lookup) {
    Map<String, CommitSubjectEntities.NamedEntity> items = new LinkedHashMap<>();
    if (branches == null) {
      return items;
    }
    for (String branch : branches) {
      try {
        workItemOf(branch, lookup).ifPresent(item -> items.putIfAbsent(item.id(), item));
      } catch (RuntimeException e) {
        LOG.debugf(e, "Could not resolve the work item of branch %s", branch);
      }
    }
    return items;
  }

  /** The work item {@code branch} names, or empty for a branch that names none. */
  static Optional<CommitSubjectEntities.NamedEntity> workItemOf(
      String branch,
      Function<CommitSubjectEntities.QualifiedId, Optional<CommitSubjectEntities.NamedEntity>>
          lookup) {
    Archetype archetype;
    String rest;
    if (branch == null) {
      return Optional.empty();
    } else if (branch.startsWith("ticket/")) {
      archetype = Archetype.TICKET;
      rest = branch.substring("ticket/".length());
    } else if (branch.startsWith("epic/")) {
      archetype = Archetype.EPIC;
      rest = branch.substring("epic/".length());
    } else {
      return Optional.empty();
    }
    List<String> candidates = new ArrayList<>();
    candidates.add(rest);
    for (int dash = rest.lastIndexOf('-'); dash > 0; dash = rest.lastIndexOf('-', dash - 1)) {
      candidates.add(rest.substring(0, dash));
    }
    for (String candidate : candidates) {
      Optional<CommitSubjectEntities.NamedEntity> item =
          CommitSubjectEntities.parse(candidate)
              .flatMap(lookup)
              .filter(found -> found.archetype() == archetype);
      if (item.isPresent()) {
        return item;
      }
    }
    return Optional.empty();
  }
}
