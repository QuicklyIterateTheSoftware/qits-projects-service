package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkTag;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * <b>What a create or an edit writes, as data</b> — every writable property of every archetype in
 * one record, handed to {@link WorkEntityService#create} and {@link WorkEntityService#update}
 * together with the archetype it is for.
 *
 * <p>It replaced four method signatures, one per archetype service, that said the same thing in four
 * shapes (qits-399). Which of these a given archetype may carry is not decided here and not decided
 * by the caller: the service writes whatever is supplied and the archetype registry ({@link
 * Archetypes#validate}) refuses a row carrying a property its kind has no slot for — an {@code
 * impetus} on an epic is a 400 naming the violation, never a value silently dropped.
 *
 * <h2>Supplied, cleared, or left alone</h2>
 *
 * <p>On an edit, <b>a null value leaves the property as it is</b>, and the nullable properties that
 * can be emptied carry a {@code clear*} flag beside them: true empties it, whatever the value says.
 * That is the pairing the ticket, feature and task edits always had, so a retitle cannot silently
 * unassign a ticket, drop its body or un-ship a feature. On a create the flags
 * mean nothing and the values are the new row's.
 *
 * <p><b>The acceptance criteria (qits-887) are a list and take no flag</b>: null leaves them alone,
 * a list (an empty one included) replaces them whole. A list has an empty value of its own, so the
 * clear flag's job is already done by it.
 *
 * <p><b>The priority (qits-753) is neither scope nor a marker</b>, like the inspiration: an edit
 * stating only it passes every freeze, and the writer refuses it on a DONE or DROPPED row instead.
 *
 * <p><b>The tags (qits-634) are a set and take no flag</b>, as the criteria do: null leaves them
 * alone, a set (an empty one included) replaces them whole. They are neither scope nor a marker, so
 * an edit stating only them passes every freeze, at every status, DONE and DROPPED included.
 *
 * <p>The static factories are the shapes the four surfaces speak — an epic's words, a ticket's
 * intake, a plan node's parts — so a call site reads as what it is writing. They build this record
 * and nothing else; there is no rule in any of them.
 */
public record EntityWrite(
    String title,
    String description,
    boolean clearDescription,
    String impetus,
    boolean clearImpetus,
    String type,
    String assignee,
    boolean clearAssignee,
    String repositoryId,
    Instant implementedAt,
    boolean clearImplementedAt,
    Instant implementingAt,
    List<String> acceptanceCriteria,
    String inspiredBy,
    boolean clearInspiredBy,
    Integer priority,
    boolean clearPriority,
    Set<WorkTag> tags,
    String relatedTo,
    boolean clearRelatedTo) {

  /** Every property but an issue's related item (qits-1819): {@link #withRelatedTo} adds it. */
  public EntityWrite(
      String title,
      String description,
      boolean clearDescription,
      String impetus,
      boolean clearImpetus,
      String type,
      String assignee,
      boolean clearAssignee,
      String repositoryId,
      Instant implementedAt,
      boolean clearImplementedAt,
      Instant implementingAt,
      List<String> acceptanceCriteria,
      String inspiredBy,
      boolean clearInspiredBy,
      Integer priority,
      boolean clearPriority,
      Set<WorkTag> tags) {
    this(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        inspiredBy,
        clearInspiredBy,
        priority,
        clearPriority,
        tags,
        null,
        false);
  }

  /**
   * Every property but the tags (qits-634), which this leaves alone: the shape every surface that
   * does not tag wrote before the tags existed. {@link #withTags} adds them.
   */
  public EntityWrite(
      String title,
      String description,
      boolean clearDescription,
      String impetus,
      boolean clearImpetus,
      String type,
      String assignee,
      boolean clearAssignee,
      String repositoryId,
      Instant implementedAt,
      boolean clearImplementedAt,
      Instant implementingAt,
      List<String> acceptanceCriteria,
      String inspiredBy,
      boolean clearInspiredBy,
      Integer priority,
      boolean clearPriority) {
    this(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        inspiredBy,
        clearInspiredBy,
        priority,
        clearPriority,
        null);
  }

  /**
   * Every property but the priority (qits-753), which this leaves alone: the shape every surface
   * that does not rank wrote before the priority existed. {@link #withPriority} adds it.
   */
  public EntityWrite(
      String title,
      String description,
      boolean clearDescription,
      String impetus,
      boolean clearImpetus,
      String type,
      String assignee,
      boolean clearAssignee,
      String repositoryId,
      Instant implementedAt,
      boolean clearImplementedAt,
      Instant implementingAt,
      List<String> acceptanceCriteria,
      String inspiredBy,
      boolean clearInspiredBy) {
    this(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        inspiredBy,
        clearInspiredBy,
        null,
        false);
  }

  /** An epic: its title and its long-form spine. */
  public static EntityWrite epic(String title, String description) {
    return new EntityWrite(
        title, description, false, null, false, null, null, false, null, null, false,
        null, null, null, false);
  }

  /** A ticket's intake: the impetus and the type are what a report consists of. */
  public static EntityWrite ticket(
      String title, String impetus, String description, String type, String assignee) {
    return new EntityWrite(
        title, description, false, impetus, false, type, assignee, false, null, null,
        false, null, null, null, false);
  }

  /** A feature under an epic. */
  public static EntityWrite feature(String title, String description) {
    return new EntityWrite(
        title, description, false, null, false, null, null, false, null, null,
        false, null, null, null, false);
  }

  /** A task under a feature: the work in one repository. */
  public static EntityWrite task(String repositoryId, String title, String description) {
    return new EntityWrite(
        title, description, false, null, false, null, null, false, repositoryId,
        null, false, null, null, null, false);
  }

  /** An edit of a ticket's words, each nullable field with its clear flag. */
  public static EntityWrite ticketEdit(
      String title,
      String impetus,
      boolean clearImpetus,
      String description,
      boolean clearDescription,
      String type,
      String assignee,
      boolean clearAssignee) {
    return new EntityWrite(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        null,
        null,
        false,
        null, null, null, false);
  }

  /**
   * An edit of a plan node — a feature or a task, which take the same edit: words and the
   * implemented marker, with its clear flag.
   */
  public static EntityWrite nodeEdit(
      String title,
      String description,
      Instant implementedAt,
      boolean clearImplementedAt) {
    return new EntityWrite(
        title,
        description,
        false,
        null,
        false,
        null,
        null,
        false,
        null,
        implementedAt,
        clearImplementedAt,
        null, null, null, false);
  }

  /** Only the implemented marker, stamped — what {@code mark_task_implemented} writes. */
  public static EntityWrite implementedAt(Instant at) {
    return nodeEdit(null, null, at, false);
  }

  /**
   * Only the implementing marker, stamped — what {@code mark_task_implementing} writes (qits-749).
   * There is no clear flag: the marker is history, kept once the work is implemented.
   */
  public static EntityWrite implementingAt(Instant at) {
    return new EntityWrite(
        null, null, false, null, false, null, null, false, null, null, false, at,
        null, null, false);
  }

  /**
   * An edit that sets ({@code ideaId}) or clears ({@code clear}) the idea the work was inspired by
   * and touches nothing else (qits-1387). Neither scope nor a marker: legal at every status.
   */
  public static EntityWrite inspiration(String ideaId, boolean clear) {
    return new EntityWrite(
        null, null, false, null, false, null, null, false, null, null, false, null,
        null, ideaId, clear);
  }

  /**
   * This write with its acceptance criteria (qits-887) replaced by {@code criteria} — null leaves
   * them alone on an edit, an empty list clears them. The surfaces that take criteria beside the
   * words of an archetype's factory ({@code update_epic}, {@code create_ticket}) build it this way.
   */
  public EntityWrite withAcceptanceCriteria(List<String> criteria) {
    return new EntityWrite(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        criteria,
        inspiredBy,
        clearInspiredBy,
        priority,
        clearPriority,
        tags,
        relatedTo,
        clearRelatedTo);
  }

  /**
   * This write, also stating the idea the work was inspired by (qits-1387): {@code ideaId} sets it,
   * {@code clear} empties it, and neither leaves it alone. Every archetype may carry one
   * (qits-1792); {@link IdeaService#requireInspirable} judges the idea it names.
   */
  public EntityWrite withInspiredBy(String ideaId, boolean clear) {
    return new EntityWrite(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        ideaId,
        clear,
        priority,
        clearPriority,
        tags,
        relatedTo,
        clearRelatedTo);
  }

  /**
   * This write, also stating the ranking among its siblings (qits-753): {@code value} sets it,
   * {@code clear} empties it, and neither leaves it alone. Neither scope nor a marker, so no freeze
   * judges it; the writer refuses it on a DONE or DROPPED row.
   */
  public EntityWrite withPriority(Integer value, boolean clear) {
    return new EntityWrite(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        inspiredBy,
        clearInspiredBy,
        value,
        clear,
        tags,
        relatedTo,
        clearRelatedTo);
  }

  /**
   * This write, also stating the tags (qits-634): {@code stated} replaces them whole — an empty set
   * clears them — and null leaves them alone. Neither scope nor a marker, so no freeze judges it,
   * and no status refuses it.
   */
  public EntityWrite withTags(Set<WorkTag> stated) {
    return new EntityWrite(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        inspiredBy,
        clearInspiredBy,
        priority,
        clearPriority,
        stated == null ? null : Collections.unmodifiableSet(EnumSet.copyOf(withNone(stated))),
        relatedTo,
        clearRelatedTo);
  }

  private static Set<WorkTag> withNone(Set<WorkTag> stated) {
    return stated.isEmpty() ? EnumSet.noneOf(WorkTag.class) : stated;
  }

  /** This write, also stating the one item an issue relates to (qits-1819). */
  public EntityWrite withRelatedTo(String entityId, boolean clear) {
    return new EntityWrite(
        title,
        description,
        clearDescription,
        impetus,
        clearImpetus,
        type,
        assignee,
        clearAssignee,
        repositoryId,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        acceptanceCriteria,
        inspiredBy,
        clearInspiredBy,
        priority,
        clearPriority,
        tags,
        entityId,
        clear);
  }

  /** Whether this write restates the tags (qits-634). */
  boolean touchesTags() {
    return tags != null;
  }

  /** Whether this write sets or clears the priority (qits-753). */
  boolean touchesPriority() {
    return priority != null || clearPriority;
  }

  /** Whether this write sets or clears the idea the work was inspired by. */
  boolean touchesInspiration() {
    return inspiredBy != null || clearInspiredBy;
  }

  /**
   * Whether this edit restates the acceptance criteria (qits-887). They are neither scope nor a
   * marker: an epic's stay editable at REFINED, outside the scope freeze, and from READY_FOR_DEV on
   * a changed list is refused for an epic and a ticket alike ({@code WorkEntityService.edit}).
   */
  boolean touchesCriteria() {
    return acceptanceCriteria != null;
  }

  /**
   * Whether this edit touches a task marker, implemented or implementing — the properties whose
   * phase is the epic being implemented (READY_FOR_DEV or IMPLEMENTING) rather than REPORTED, see {@code
   * EntityLifecycle.requireBeingImplemented}.
   */
  boolean touchesMarker() {
    return implementedAt != null || clearImplementedAt || implementingAt != null;
  }

  /**
   * Whether this edit touches scope — anything but the marker and the acceptance criteria. <b>An
   * edit that supplies nothing at all counts as scope</b>: it is the structural endpoint, and
   * letting an empty body through a
   * frozen epic's guard would make "nothing" the one write a freeze cannot refuse.
   */
  boolean touchesScope() {
    return title != null
        || description != null
        || clearDescription
        || impetus != null
        || clearImpetus
        || type != null
        || assignee != null
        || clearAssignee
        || repositoryId != null
        || (!touchesMarker() && !touchesCriteria() && !touchesInspiration() && !touchesPriority()
            && !touchesTags());
  }
}
