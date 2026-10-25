package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.EntityTransitioned;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <b>An epic or a ticket reached VERIFIED</b> (epic qits-1604): the delivery desk is told, so it can
 * take the item on from there — release it, close it out.
 *
 * <p>Reads this service's own {@link EntityTransitioned}. An entry qualifies when it MOVED to {@code
 * VERIFIED} ({@code statusBefore} is something else, or absent for a created one) and is an {@code
 * EPIC} or a {@code TICKET}: a feature or a task is a piece of one of those, and the desk acts on the
 * whole. Its cause is {@code changedBy}, so a move the desk made itself is dropped by the listener.
 */
@ApplicationScoped
public class WorkVerifiedSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "work-verified";

  private static final String VERIFIED = "VERIFIED";

  private static final Set<String> ARCHETYPES = Set.of("EPIC", "TICKET");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 10;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(EntityTransitioned.class.getSimpleName());
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    EntityTransitioned transitioned = DeskTriggerFrames.decode(frame, EntityTransitioned.class);
    UUID eventId = DeskTriggerFrames.eventIdOf(frame);
    Instant occurredAt =
        transitioned.transitionedAt() == null ? frame.occurredAt() : transitioned.transitionedAt();
    List<DeskTrigger> triggers = new ArrayList<>();
    for (EntityTransitioned.Entity entity : transitioned.entities()) {
      if (!VERIFIED.equals(entity.status())
          || VERIFIED.equals(entity.statusBefore())
          || !ARCHETYPES.contains(entity.archetype())
          || entity.projectId() == null) {
        continue;
      }
      // The qualified id the desk types into `qits work`; the event carries the number, not the
      // project's slug.
      Project project = Project.findById(entity.projectId());
      if (project == null || project.slug == null) {
        continue;
      }
      triggers.add(
          new DeskTrigger(
              entity.projectId(),
              project.slug + "-" + entity.number(),
              entity.entityId(),
              entity.changedBy(),
              entity.title(),
              eventId,
              occurredAt));
    }
    return triggers;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Moved to VERIFIED:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }
}
