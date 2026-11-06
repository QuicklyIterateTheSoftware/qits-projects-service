package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.NeedRef;
import eu.wohlben.qits.entities.control.NeedService;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.ProjectService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>What holds a work item's implementation back</b> (qits-1903): its needs that are not met, read
 * for the two readers that act on them — {@link EntityDispatch}'s refusal and {@link PhaseAdvance}'s
 * hold — each need told its item's qualified id here, where the project slug can be read.
 *
 * <p>Needs gate the start of implementation and nothing else: an item at REFINED or READY_FOR_DEV
 * with an unmet need is neither dispatched nor handed a turn. Refine (REPORTED) and verify (from
 * IMPLEMENTED on) are never held by one, because neither starts the work the need orders.
 */
@ApplicationScoped
public class UnmetNeeds {

  @Inject NeedService needs;

  @Inject ProjectService projects;

  /** Whether {@code entity}'s status is one its needs hold: REFINED or READY_FOR_DEV. */
  public static boolean gates(WorkEntity entity) {
    return EntityStatus.REFINED.name().equals(entity.status)
        || EntityStatus.READY_FOR_DEV.name().equals(entity.status);
  }

  /**
   * The needs of {@code entity} not met now, qualified, in the order written — empty at a status
   * they do not hold ({@link #gates}), so a caller need not ask first.
   */
  public List<NeedRef> holding(WorkEntity entity) {
    if (!gates(entity)) {
      return List.of();
    }
    List<NeedRef> unmet = needs.unmet(entity.id);
    if (unmet.isEmpty()) {
      return unmet;
    }
    Map<String, String> slugs = projects.slugsByIds(Set.of(entity.projectId));
    String slug = slugs.get(entity.projectId);
    return slug == null
        ? unmet
        : unmet.stream()
            .map(need -> need.withQualifiedId(QualifiedEntityIds.render(slug, need.number())))
            .toList();
  }

  /** {@code q1 at S1 (now C1), q2 at S2 (now C2)} — how a refusal and a log line name them. */
  public static String describe(List<NeedRef> unmet) {
    return unmet.stream()
        .map(
            need ->
                (need.qualifiedId() == null ? need.id() : need.qualifiedId())
                    + " at "
                    + need.status()
                    + " (now "
                    + need.currentStatus()
                    + ")")
        .collect(Collectors.joining(", "));
  }
}
