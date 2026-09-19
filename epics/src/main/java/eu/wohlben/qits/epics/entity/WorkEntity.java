package eu.wohlben.qits.epics.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The merged planning row: an {@link Epic}, a {@link Ticket}, a {@link Feature} or a {@link Task},
 * said by {@link #archetype} (V9). The four were always one noun with different columns filled in —
 * a titled, slugged, described thing owned by a project, possibly hanging under another one — and
 * the cost of keeping them apart was four services repeating one create, four slug rules, four
 * audit vocabularies, and a feature that could never be promoted to an epic because promotion would
 * have meant moving a row between tables while every id pointing at it stayed behind.
 *
 * <p><b>Nothing reads this yet.</b> The four old entities are untouched and are still what answers
 * every route; this class and its table exist so a later task can backfill into them. The ids are
 * the <em>same</em> id space — V10 copies each old row in under the id it already has — because
 * every dossier page, audit entry, branch name and URL on the platform names one of those strings.
 *
 * <p><b>Why it is not called {@code Entity}.</b> {@code jakarta.persistence.Entity} owns that word.
 * A class named {@code Entity} would have to import its own annotation under an alias in every file
 * that mentioned it, and a reader of {@code import ...epics.entity.Entity} could not tell which of
 * the two was meant. The table is {@code entity} — that name is right, and it is the one a reader
 * of the SQL sees — so the class carries {@code @Table(name = "entity")} and takes a different
 * word. {@code Work} is what this module is about; {@code WorkEntity} says "the entity of work"
 * rather than "some entity". This is the one place in the module where the table name and the class
 * simple name deliberately disagree; V1's other tables rely on unquoted mixed case folding to lower
 * case and need no {@code @Table} at all.
 *
 * <p><b>A {@link CausedRow}</b>, for the reason every row in this module is one: these are minted
 * by agents as much as by people — the MCP tools reach the same services the SPA does, on the same
 * request thread — so the {@code CausationStamp} listener reads the scope the {@code
 * X-Qits-Causation-Id} filter restored and the row records what asked for it. A person typing into
 * the SPA sends no header and leaves a rootless row, which is the correct answer rather than a
 * missing one.
 *
 * <p>Panache active-record with public fields and no getters, matching {@link Epic} and {@link
 * Ticket}; the id is a string minted by the service, like every other id here.
 */
@Entity
@Table(name = "entity")
@EntityListeners(CausationStamp.class)
public class WorkEntity extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  /**
   * The owning project — {@code domain}'s {@code Project} by String id, with no JPA relation and no
   * cross-DB FK (epics is a separate physical database); existence is validated in {@code
   * service}'s controllers, exactly as {@link Epic} has it.
   *
   * <p>It is carried on <em>every</em> row, root and descendant alike, where today a {@link
   * Feature} reaches its project by walking up to its epic. A merged tree is read project-first —
   * the board, the listing, the change hint — and a walk per row to answer "whose is this" would be
   * a join this column makes unnecessary.
   */
  @Column(name = "project_id", nullable = false)
  public String projectId;

  /**
   * Which of the four kinds this row is. Behind {@code ck_entity_archetype}; what each word
   * <em>means</em> is declared in {@code control/Archetypes} rather than implied here.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public Archetype archetype;

  /** Short label for lists and breadcrumbs. */
  @Column(nullable = false)
  public String title;

  /**
   * Git-safe path segment, minted from the title at create and never re-derived — the rule all four
   * old slugs already carry, and for their reason: it names a branch and sits in URLs people have
   * already sent each other. Unique within {@link #slugScope}.
   */
  @Column(nullable = false, updatable = false)
  public String slug;

  /**
   * <b>What {@link #slug} is unique within</b>, carried as a value because it is no longer the same
   * column for every row: the <b>project id</b> for a row with no parent, and the <b>parent entity
   * id</b> for a row that has one. Those two cases are exactly today's three constraints —
   * {@code uq_epic_project_slug} and {@code uq_ticket_project_slug} are the first,
   * {@code uq_feature_epic_slug} and {@code uq_task_feature_slug} the second — expressed once, as
   * {@code uq_entity_slug_scope_slug}.
   *
   * <p><b>Unlike {@link #slug} it is not immutable</b>, and that is the whole point of separating
   * them: a reparent leaves the slug alone and recomputes the scope, so the slug is judged against
   * its new siblings without the branch names it is in ever moving. A collision found there is a
   * validation refusal naming the slug and the new parent — never a constraint violation surfacing
   * as a 500, and never a silent re-mint.
   */
  @Column(name = "slug_scope", nullable = false)
  public String slugScope;

  /** The long-form Markdown body. */
  public String description;

  /**
   * <b>One column for two lifecycles</b>, and {@link #archetype} is what says which. An {@link
   * Archetype#EPIC} holds one of {@link EpicStatus}' five words, an {@link Archetype#TICKET} one of
   * {@link TicketStatus}' five, and a feature and a task hold none — which is why this is nullable:
   * an absent status is the ordinary state of most rows here rather than a gap.
   *
   * <p><b>It is a String and not an enum</b>, deliberately. There is no Java type that is "either
   * an EpicStatus or a TicketStatus", and inventing one — a nine-word merged enum — would give
   * {@code IMPLEMENTED} (which both already spell) a single identity across two lifecycles that
   * mean different things by it, and would leave every existing switch over the two real enums with
   * a third vocabulary to translate from. The stored word is the enum's own {@code name()}, the
   * check constraint is the union of the two, and {@code control/Archetypes} is what refuses an
   * epic word on a ticket — a split the database cannot make, because a check constraint has no way
   * to say "these five when the archetype is EPIC" without becoming a second place the vocabulary
   * is written down.
   */
  @Column(length = 32)
  public String status;

  /**
   * Bug or improvement ({@link TicketType}), on a ticket and on nothing else. Named {@code
   * ticketType} rather than {@code type}, because {@code type} in a row holding four archetypes
   * reads as the archetype, which is the one thing it is not.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "ticket_type", length = 32)
  public TicketType ticketType;

  /**
   * Why a ticket came about, in the reporter's or the triage agent's own words — see {@link
   * Ticket#impetus} for the length rule and why it exists. Never rewritten by a later phase.
   */
  public String impetus;

  /** Who is looking at a ticket, as a free-text name. The platform has no person table. */
  public String assignee;

  /** The principal that filed a ticket. Stamped from the request identity, never client-supplied. */
  @Column(name = "created_by", updatable = false)
  public String createdBy;

  /**
   * The successor draft a superseded epic spawned, pointing into this same table; null on every
   * other row. Self-FK with {@code on delete set null}, V1's safety net unchanged.
   */
  @Column(name = "superseded_by_entity_id")
  public String supersededByEntityId;

  /**
   * The concrete repository a task names — {@code domain}'s {@code Repository} by String id, no
   * cross-DB FK, indexed. Null on every other archetype.
   */
  @Column(name = "repository_id")
  public String repositoryId;

  /**
   * <b>One implemented marker for what were two</b>: {@link Feature#implementedOn} and {@link
   * Task#implementedAt}. They were never two facts — both mean "this is done, as of then" — and the
   * two names are an accident of the two tables having been written apart. {@code implementedAt}
   * wins because a timestamp answers "at", and because the epic lifecycle's guard already speaks of
   * "the implemented markers" in the plural as one rule.
   */
  @Column(name = "implemented_at")
  public Instant implementedAt;

  /**
   * <b>One sibling-dependency edge for what were two</b>: {@link Feature#dependsOnFeatureId} and
   * {@link Task#dependsOnTaskId}. Self-FK with {@code on delete set null}.
   *
   * <p><b>This is not nesting and must never be validated as nesting.</b> A dependency says "do
   * that one first"; a membership says "this one is part of that one". They point in unrelated
   * directions and have unrelated cycle rules — a dependency cycle is a real error the services
   * already check for, while a membership cycle cannot occur at all under the depth rule. The
   * parent/child relation is {@link EntityMembership} and nothing in this class.
   */
  @Column(name = "depends_on_entity_id")
  public String dependsOnEntityId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
