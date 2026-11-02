package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.EntityCommented;
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
 * <b>A person answered an issue</b> (qits-1819): they resolved it or commented on it, so the desk
 * can continue the blocked work. Its own moves and comments are dropped by the listener, and an
 * agent's comment does not count: an issue waits on a person.
 */
@ApplicationScoped
public class IssueAnsweredSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "issue-answered";

  private static final String ISSUE = "ISSUE";

  private static final String RESOLVED = "RESOLVED";

  private static final String TRANSITIONED = EntityTransitioned.class.getSimpleName();

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 25;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(TRANSITIONED, EntityCommented.class.getSimpleName());
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    return TRANSITIONED.equals(frame.name()) ? resolved(frame) : commented(frame);
  }

  private List<DeskTrigger> resolved(EventFrame frame) {
    EntityTransitioned transitioned = DeskTriggerFrames.decode(frame, EntityTransitioned.class);
    UUID eventId = DeskTriggerFrames.eventIdOf(frame);
    Instant occurredAt =
        transitioned.transitionedAt() == null ? frame.occurredAt() : transitioned.transitionedAt();
    List<DeskTrigger> triggers = new ArrayList<>();
    for (EntityTransitioned.Entity entity : transitioned.entities()) {
      if (!ISSUE.equals(entity.archetype())
          || !RESOLVED.equals(entity.status())
          || RESOLVED.equals(entity.statusBefore())
          || entity.projectId() == null) {
        continue;
      }
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
              join(entity.title(), "resolved by " + entity.changedBy()),
              eventId,
              occurredAt));
    }
    return triggers;
  }

  private List<DeskTrigger> commented(EventFrame frame) {
    EntityCommented commented = DeskTriggerFrames.decode(frame, EntityCommented.class);
    if (!ISSUE.equals(commented.archetype())
        || commented.authorAgentOf() != null
        || commented.projectId() == null) {
      return List.of();
    }
    Project project = Project.findById(commented.projectId());
    if (project == null || project.slug == null) {
      return List.of();
    }
    return List.of(
        new DeskTrigger(
            commented.projectId(),
            project.slug + "-" + commented.number(),
            commented.entityId(),
            DeskMentionedSource.cause(commented.commentId()),
            join(commented.title(), "comment from " + commented.author()),
            DeskTriggerFrames.eventIdOf(frame),
            commented.commentedAt() == null ? frame.occurredAt() : commented.commentedAt(),
            commented.commentId(),
            commented.changedBy()));
  }

  private static String join(String title, String what) {
    return title == null || title.isBlank() ? what : title + "; " + what;
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Issues a person answered (read the thread, then continue the blocked work):\n"
        + events.stream()
            .map(DeskTriggerEventRow::line)
            .distinct()
            .collect(Collectors.joining("\n"));
  }
}
