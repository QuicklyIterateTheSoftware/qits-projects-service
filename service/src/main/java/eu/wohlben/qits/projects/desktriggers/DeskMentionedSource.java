package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.EntityCommented;
import eu.wohlben.qits.projects.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>A comment addresses the desk</b> (qits-1660): its body mentions {@code @desk}. The desk often
 * waits for an agent's answer before it acts, and the answer arrives as such a comment.
 *
 * <p>Reads this service's own {@link EntityCommented}. The line names the item the comment is on and
 * who wrote it; the desk reads the thread for the text. Two comments by one author on one item in
 * a window are one line. The cause is {@code changedBy}, so the desk's own comment is dropped by the
 * listener.
 */
@ApplicationScoped
public class DeskMentionedSource implements DeskTriggerSource {

  /** Storage — see {@link DeskTriggerSource#id()}. */
  static final String ID = "desk-mentioned";

  /** The handle that addresses the desk, as {@link EntityCommented#mentions()} spells it. */
  static final String DESK = "desk";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int order() {
    return 30;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(EntityCommented.class.getSimpleName());
  }

  @Override
  public List<DeskTrigger> qualify(EventFrame frame) {
    EntityCommented commented = DeskTriggerFrames.decode(frame, EntityCommented.class);
    if (!commented.mentions().contains(DESK) || commented.projectId() == null) {
      return List.of();
    }
    Project project = Project.findById(commented.projectId());
    if (project == null || project.slug == null) {
      return List.of();
    }
    Instant occurredAt =
        commented.commentedAt() == null ? frame.occurredAt() : commented.commentedAt();
    return List.of(
        new DeskTrigger(
            commented.projectId(),
            project.slug + "-" + commented.number(),
            commented.entityId(),
            commented.changedBy(),
            label(commented),
            DeskTriggerFrames.eventIdOf(frame),
            occurredAt));
  }

  @Override
  public String render(String projectSlug, List<DeskTriggerEventRow> events) {
    return "Comments to @desk:\n"
        + events.stream().map(DeskTriggerEventRow::line).collect(Collectors.joining("\n"));
  }

  /** {@code <title>; from <who>}: an agent by its work item, anyone else by name. */
  private static String label(EntityCommented commented) {
    String who =
        commented.authorAgentOf() != null
            ? commented.authorAgentOf() + "'s agent"
            : commented.author();
    String from = who == null ? null : "from " + who;
    if (commented.title() == null || commented.title().isBlank()) {
      return from;
    }
    return from == null ? commented.title() : commented.title() + "; " + from;
  }
}
