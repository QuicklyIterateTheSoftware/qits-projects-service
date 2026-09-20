package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.TicketType;
import java.time.Instant;

/**
 * <b>One entity's whole intended state after a transition</b> — what it is, where it hangs, and
 * every property it carries. The unit of {@code EntityTransitionService.transition}, keyed in that
 * call by the id it describes.
 *
 * <h2>It is the entity in full, and that is a PUT</h2>
 *
 * <p><b>An absent property is CLEARED, not left alone.</b> This is deliberately not the partial
 * update the four per-entity services offer, and the reason is what the operation is: a caller
 * re-archetyping a row is stating what the row <em>becomes</em>, and a merge with what the row used
 * to be would carry a property the new kind has no meaning for into a state nobody asked for. A
 * caller that means "leave the description alone" restates the description — it read the entity a
 * moment ago and is holding it.
 *
 * <p>There is no clear-flag pairing here for the same reason. Those flags exist on a PATCH because
 * absent and "make it absent" are indistinguishable there; under a PUT they are the same statement
 * and a second spelling of it would be a second thing to keep in step.
 *
 * <p><b>Two properties are NOT statable here and are not cleared by omission either</b>, because a
 * caller could not have written them in the first place:
 *
 * <ul>
 *   <li>{@code slug} is minted from the title at create, is {@code @Column(updatable = false)}, and
 *       names branches and URLs people have already sent each other. A move leaves it exactly where
 *       it is; what moves is {@code slug_scope}, which is the whole point of those two being
 *       separate columns.
 *   <li>{@code createdBy} is stamped from the request identity. It is <b>carried</b> when the target
 *       archetype permits it and <b>cleared</b> when the target has no slot for it — a demotion from
 *       {@code TICKET} to {@code FEATURE} clears it — and it is never a {@code NOT_PERMITTED}
 *       violation, because refusing a caller for a value the server put there would be refusing it
 *       for the server's own act.
 * </ul>
 *
 * @param archetype the kind this entity <b>becomes</b>. Required; the whole entry is judged against
 *     it by {@code Archetypes.validate}, so a property the target has no slot for is refused rather
 *     than dropped
 * @param membership where it hangs afterwards. Absent is the same statement as {@code {"parent":
 *     null}}: a root, with any existing edge deleted
 * @param title the label. Required of every archetype
 * @param description the long-form Markdown body
 * @param status the status word as it would be stored. <b>Required whenever the target archetype
 *     declares status words</b> — a rule the transition states rather than the registry: a
 *     transition mints nothing, so an omitted status would clear one under the PUT rule and leave a
 *     status-less epic no lifecycle can read. The word itself is judged by {@code Archetypes}
 *     against the target's vocabulary
 * @param ticketType {@code BUG} or {@code IMPROVEMENT}; a ticket's, and only a ticket's
 * @param impetus why a ticket came about, in the reporter's words
 * @param assignee who is looking at a ticket, as free text
 * @param supersededBy the successor draft a superseded epic spawned
 * @param repositoryId the one concrete repository a task names
 * @param implementedAt the implemented marker
 * @param dependsOn the sibling ordering edge. <b>Never nesting</b> — {@link Membership} is nesting
 */
public record EntityTransition(
    Archetype archetype,
    Membership membership,
    String title,
    String description,
    String status,
    TicketType ticketType,
    String impetus,
    String assignee,
    String supersededBy,
    String repositoryId,
    Instant implementedAt,
    String dependsOn) {

  /**
   * Where an entity hangs after the transition, and where among its siblings.
   *
   * @param parent the entity it becomes part of, or <b>null for a root</b>. Resolved against the
   *     transition's own map <em>first</em> and the store second, which is what lets two entities
   *     swap their relation in one request. An id that is in neither is a refusal and never a create
   * @param position where among the new parent's children it lands, zero-based. <b>Clamped to the
   *     legal range rather than refused</b> — a caller stating 99 means "last", and a refusal there
   *     would make a client compute the sibling count before it could move anything. Absent appends.
   *     Both the old and the new parent end dense and zero-based whatever is stated
   */
  public record Membership(String parent, Integer position) {}

  /** The membership as stated, with an absent one read as "a root" rather than as "unchanged". */
  public Membership membershipOrRoot() {
    return membership == null ? new Membership(null, null) : membership;
  }

  /** What this entity becomes part of, or null for a root. */
  public String parent() {
    return membershipOrRoot().parent();
  }

  /** The stated index among the new parent's children, or null to append. */
  public Integer position() {
    return membershipOrRoot().position();
  }
}
