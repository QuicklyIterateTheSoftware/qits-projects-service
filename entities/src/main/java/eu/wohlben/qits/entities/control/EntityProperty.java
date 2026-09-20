package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;

/**
 * <b>The property vocabulary the archetype registry is declared over</b> — one word per thing a
 * {@link WorkEntity} can carry, so that "an epic may not have a repository" is a statement about
 * named properties rather than a switch over columns.
 *
 * <p><b>Why a vocabulary at all, rather than reading the columns.</b> A merged table has a column
 * for every archetype's every property, so the row shape says nothing about which of them are
 * <em>allowed</em> on any given row. The rule has to live somewhere, and the choice is between a
 * conditional per column at every write site and one declared set per archetype in one place. This
 * enum is what makes the second possible: {@code Archetypes} declares required and permitted sets
 * over these words, a violation names one of them, and a caller can put the message on the
 * offending field instead of on the form.
 *
 * <p><b>These are properties, not columns, and the difference shows up twice.</b> {@link
 * #IMPLEMENTED_AT} covers what were {@code feature.implemented_on} and {@code task.implemented_at},
 * and {@link #DEPENDS_ON} covers what were {@code depends_on_feature_id} and {@code
 * depends_on_task_id}; each pair was one property spelled twice because it lived in two tables.
 *
 * <p><b>Nesting is not in here.</b> Whether a row may hang under another is not a property of the
 * row — it is a fact about a pair — so the parent/child rule is {@code Nesting}'s and never a
 * required-property check. {@link #DEPENDS_ON} is in here and is <em>not</em> that rule: a
 * dependency is a sibling ordering edge, and treating it as containment is the misreading the
 * column's own javadoc warns about.
 */
public enum EntityProperty {

  /** {@code entity.title}. Every archetype requires one: an untitled row cannot be listed. */
  TITLE,

  /**
   * {@code entity.slug}. Permitted everywhere and required nowhere, because it is <b>minted by the
   * writer from the title</b> ({@code Slugs.slugify}) rather than supplied by a caller. Declaring it
   * required would make every create fail the gate before the writer had run, and declaring it
   * absent would let a caller's slug through unexamined.
   */
  SLUG,

  /** {@code entity.description}. The long-form Markdown body; optional on all four. */
  DESCRIPTION,

  /**
   * {@code entity.status}. One column, two vocabularies: an epic's word comes from {@code
   * EpicStatus} and a ticket's from {@code TicketStatus}, and which set is legal is part of the
   * archetype's declaration rather than a thing the column can express.
   */
  STATUS,

  /** {@code entity.ticket_type} — {@code BUG} or {@code IMPROVEMENT}. A ticket's, and only a ticket's. */
  TICKET_TYPE,

  /** {@code entity.impetus} — why a ticket came about, in the reporter's words. */
  IMPETUS,

  /** {@code entity.assignee} — free text, not an id: the platform has no person table. */
  ASSIGNEE,

  /** {@code entity.created_by} — stamped from the request identity, never client-supplied. */
  CREATED_BY,

  /** {@code entity.superseded_by_entity_id} — the successor draft a superseded epic spawned. */
  SUPERSEDED_BY,

  /** {@code entity.repository_id} — the one concrete repository a task names. */
  REPOSITORY_ID,

  /**
   * {@code entity.implemented_at} — the merge of {@code feature.implemented_on} and {@code
   * task.implemented_at}, which were one fact under two names.
   */
  IMPLEMENTED_AT,

  /**
   * {@code entity.depends_on_entity_id} — the merge of {@code depends_on_feature_id} and {@code
   * depends_on_task_id}. A <b>sibling ordering</b> edge and never containment; {@code
   * EntityMembership} is the relation that is containment, and {@link Nesting} is what judges it.
   */
  DEPENDS_ON
}
