package eu.wohlben.qits.entities.entity;

/**
 * Which thing an {@link AuditEntry} concerns. A closed vocabulary, pinned by {@code
 * ck_audit_entity_type} on {@code auditentry.entity_type} — changing it is a migration (V4 added the
 * two ticket words, V5 the dossier one, V13 re-stated the set, V20 renamed {@code TICKET_COMMENT} to
 * {@link #COMMENT}) as well as a value here.
 *
 * <h2>The four planning words are {@link Archetype}'s, and are no longer written down twice</h2>
 *
 * <p>{@link #EPIC}, {@link #FEATURE}, {@link #TASK} and {@link #TICKET} were a hand-written parallel
 * list of the four planning kinds, and {@code EntityTransitionService} carried a hand-written switch
 * from one enum to the other. The four kinds are {@link Archetype} — one declaration, in {@code
 * control/Archetypes} — so {@link #of(Archetype)} derives the word instead and the switch is gone.
 *
 * <p><b>The enum stays, because the column is typed.</b> {@code auditentry.entity_type} is
 * {@code @Enumerated(STRING)} behind a check constraint and carries two words no archetype will ever
 * spell, so it cannot simply <em>be</em> {@code Archetype}: {@link #COMMENT} and {@link
 * #DOSSIER_PAGE} are not archetypes of the merged model and are not rows in {@code entity} at all.
 * What {@link #of} removes is the second <em>decision</em>, not the second type: a fifth archetype
 * now fails {@code AuditEntityTypeTest} until the word is added here too, where before it would have
 * fallen out of a switch at runtime.
 */
public enum AuditEntityType {

  /** An {@link Archetype#EPIC} row of {@link WorkEntity}. */
  EPIC,

  /** An {@link Archetype#FEATURE} row of {@link WorkEntity}. */
  FEATURE,

  /** An {@link Archetype#TASK} row of {@link WorkEntity}. */
  TASK,

  /**
   * An {@link Archetype#TICKET} row of {@link WorkEntity}. Its rows carry the ticket's own id in
   * {@link AuditEntry#epicId} — see that field, which is the subtree key rather than a foreign key
   * to an epic.
   */
  TICKET,

  /**
   * An {@link Archetype#CAMPAIGN} row of {@link WorkEntity}, a root like an epic: its rows carry
   * the campaign's own id in {@link AuditEntry#epicId}. Permitted by {@code ck_audit_entity_type}
   * from V17, the migration that admitted the archetype itself.
   */
  CAMPAIGN,

  /**
   * An {@link EntityComment}, on an entity of any archetype. Its rows carry the subtree key of the
   * entity it is on: that entity's own id for a root (a ticket, an epic, a campaign), the epic's for
   * a feature or a task — so an epic's history includes what was said on its tasks' threads.
   *
   * <p>It was {@code TICKET_COMMENT} until V20, which rewrote the rows already written; a comment on
   * an epic is not a ticket comment, and one kind of row spelled two ways is a history nobody can
   * query in one go.
   *
   * <p><b>Not an archetype</b>, and it must not become one: a comment is a remark on a row rather
   * than a node of the plan, it has no slug, no number and no membership, and it lives in its own
   * table.
   */
  COMMENT,

  /**
   * A {@link DossierPage}. Unlike a ticket, it is not a root: its rows carry the OWNING entity's id
   * in {@link AuditEntry#epicId}, so an epic's history keeps including what its pages did.
   *
   * <p><b>Not an archetype</b>, for {@link #COMMENT}'s reason.
   */
  DOSSIER_PAGE;

  /**
   * <b>The audit word for an archetype</b> — the four planning kinds derived rather than re-decided.
   *
   * <p>The two enums spell the same four kinds because they are the same four kinds, so {@code
   * valueOf(archetype.name())} is the whole translation and there is no table to keep in step. It
   * throws {@link IllegalArgumentException} for an archetype with no word here, which is a
   * programming error a build should have caught: {@code AuditEntityTypeTest} asserts every {@link
   * Archetype} constant has a match, so a fifth archetype fails the build until both move together.
   * A hand-written switch answered the same question and could only fail at the moment a row was
   * written.
   */
  public static AuditEntityType of(Archetype archetype) {
    return valueOf(archetype.name());
  }
}
