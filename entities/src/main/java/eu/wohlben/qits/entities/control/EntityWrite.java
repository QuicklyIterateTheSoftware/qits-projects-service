package eu.wohlben.qits.entities.control;

import java.time.Instant;
import java.util.List;

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
 * unassign a ticket, drop its body, un-ship a feature or lose a dependency. On a create the flags
 * mean nothing and the values are the new row's.
 *
 * <p><b>The acceptance criteria (qits-887) are a list and take no flag</b>: null leaves them alone,
 * a list (an empty one included) replaces them whole. A list has an empty value of its own, so the
 * clear flag's job is already done by it.
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
    String dependsOn,
    boolean clearDependsOn,
    Instant implementedAt,
    boolean clearImplementedAt,
    Instant implementingAt,
    List<String> acceptanceCriteria) {

  /** An epic: its title and its long-form spine. */
  public static EntityWrite epic(String title, String description) {
    return new EntityWrite(
        title, description, false, null, false, null, null, false, null, null, false, null, false,
        null, null);
  }

  /** A campaign: a title and a description, the same two words an epic is written in. */
  public static EntityWrite campaign(String title, String description) {
    return epic(title, description);
  }

  /** A ticket's intake: the impetus and the type are what a report consists of. */
  public static EntityWrite ticket(
      String title, String impetus, String description, String type, String assignee) {
    return new EntityWrite(
        title, description, false, impetus, false, type, assignee, false, null, null, false, null,
        false, null, null);
  }

  /** A feature under an epic, optionally depending on a sibling. */
  public static EntityWrite feature(String title, String description, String dependsOn) {
    return new EntityWrite(
        title, description, false, null, false, null, null, false, null, dependsOn, false, null,
        false, null, null);
  }

  /** A task under a feature: the work in one repository, optionally depending on a sibling. */
  public static EntityWrite task(
      String repositoryId, String title, String description, String dependsOn) {
    return new EntityWrite(
        title, description, false, null, false, null, null, false, repositoryId, dependsOn, false,
        null, false, null, null);
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
        null,
        false,
        null, null);
  }

  /**
   * An edit of a plan node — a feature or a task, which take the same edit: words, the sibling
   * dependency and the implemented marker, the last two with their clear flags.
   */
  public static EntityWrite nodeEdit(
      String title,
      String description,
      String dependsOn,
      boolean clearDependsOn,
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
        dependsOn,
        clearDependsOn,
        implementedAt,
        clearImplementedAt,
        null, null);
  }

  /** Only the implemented marker, stamped — what {@code mark_task_implemented} writes. */
  public static EntityWrite implementedAt(Instant at) {
    return nodeEdit(null, null, null, false, at, false);
  }

  /**
   * Only the implementing marker, stamped — what {@code mark_task_implementing} writes (qits-749).
   * There is no clear flag: the marker is history, kept once the work is implemented.
   */
  public static EntityWrite implementingAt(Instant at) {
    return new EntityWrite(
        null, null, false, null, false, null, null, false, null, null, false, null, false, at,
        null);
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
        dependsOn,
        clearDependsOn,
        implementedAt,
        clearImplementedAt,
        implementingAt,
        criteria);
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
        || dependsOn != null
        || clearDependsOn
        || (!touchesMarker() && !touchesCriteria());
  }
}
