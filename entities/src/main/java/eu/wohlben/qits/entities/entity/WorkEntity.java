package eu.wohlben.qits.entities.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The merged planning row: an epic, a ticket, a feature or a task, said by {@link #archetype} (V9).
 * The four were always one noun with different columns filled in —
 * a titled, slugged, described thing owned by a project, possibly hanging under another one — and
 * the cost of keeping them apart was four services repeating one create, four slug rules, four
 * audit vocabularies, and a feature that could never be promoted to an epic because promotion would
 * have meant moving a row between tables while every id pointing at it stayed behind.
 *
 * <p><b>This is the only planning row there is.</b> {@code WorkEntityService} answers every read
 * and judges every rule against this table, for all four archetypes, and hands its callers <em>this
 * row</em> — bare for the two roots, and beside its parent's
 * id ({@code control/Nested}) for the two descendants, whose parent is an {@link EntityMembership}
 * edge rather than a column.
 *
 * <p>The four old classes and their four tables are <b>gone</b> (V13). They stood for two releases
 * as shapes the services answered with, so that the mappers, the DTOs and the controllers above
 * could be left untouched while the storage moved underneath them. Those four per-archetype DTOs,
 * their mapper and their routes went in qits-976: the merged {@code TransitionedEntity} is the one
 * shape this row is answered in.
 *
 * <p>The ids are the <em>same</em> id space — V10 copied each old row in under the id it already had
 * — because every dossier page, audit entry, branch name and URL on the platform names one of those
 * strings. That is also what made V12's repointing of the outward foreign keys and V13's drop
 * possible without re-keying anything.
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
 * <p>Panache active-record with public fields and no getters, the idiom every entity in this module
 * uses; the id is a string minted by the service, like every other id here.
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
   * service}'s controllers, which is where it always was.
   *
   * <p>It is carried on <em>every</em> row, root and descendant alike, where a feature used to reach
   * its project by walking up to its epic. A merged tree is read project-first —
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

  /**
   * <b>The per-project numeric id</b> (V11): unique within {@link #projectId}, never reused, and
   * written by hand in its qualified form {@code <project>-<n>} — {@code qits-1337}. Allocated by
   * {@code control/EntityNumbers} at create and by nothing else.
   *
   * <p><b>It does not replace {@link #id}.</b> The uuid is still the primary key and is still what
   * every dossier page, audit entry, branch name, workspace and URL on the platform names. This is a
   * second identifier, and it exists because the id has to survive where neither the uuid nor the
   * slug does: a uuid does not fit in a commit subject, and a slug is truncated at {@code
   * Slugs.MAX_LENGTH} (55) characters and minted from a title. The number is short enough to write
   * by hand, stable for the life of the row, and unambiguous once qualified by its project.
   *
   * <p><b>It names a NODE, not a ticket.</b> The unified table holds every archetype and the numbers
   * are drawn from one run of integers per project, so a ticket and a feature in the same project
   * can never share one. {@code uq_entity_project_number} is what makes that a fact rather than a
   * convention.
   *
   * <p><b>{@code updatable = false}, for {@link #slug}'s reason and one of its own.</b> A number is
   * stable for the life of the entity, and the multi-entity transition — which re-archetypes and
   * re-parents existing rows — creates nothing and must therefore allocate nothing. The schema is
   * where that is best said: a re-archetype that tried to move the number would be a silent no-op
   * rather than a wrong row.
   */
  @Column(nullable = false, updatable = false)
  public long number;

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
   * <b>One column for one lifecycle</b>: every archetype holds one of {@link EntityStatus}' eight
   * words — an {@link Archetype#EPIC}, a {@link Archetype#TICKET}, a {@link Archetype#FEATURE} and a
   * {@link Archetype#TASK} the whole set, a campaign the set less the two "-ING" words. Not null since
   * epics V24 (qits-763), which gave the features and tasks that held none until then the status
   * their markers and their epic implied. (Until qits-392 an epic held words of its own, {@code
   * EpicStatus}; epics V15 backfilled them.)
   *
   * <p><b>It is a String and not an enum</b>, which means a comparison against a word nobody spells
   * any more compiles and fails silently at runtime — compare against {@link EntityStatus#name()},
   * never a literal. The stored word is the enum's own {@code name()}, {@code ck_entity_status}
   * spells exactly the nine, and {@code control/Archetypes} is what refuses a word a kind's
   * lifecycle does not hold — a split the database cannot make.
   */
  @Column(length = 32)
  public String status;

  /**
   * <b>The phase this entity's status starts cannot finish right now.</b> On every kind with a
   * lifecycle — a {@link Archetype#TICKET}, and since qits-592 an {@link Archetype#EPIC} and a
   * {@link Archetype#CAMPAIGN} — and false on a feature and a task, which hold a status (qits-763)
   * but run no phase of their own to block.
   *
   * <p><b>It is a flag and not a status, and that is the decision rather than a shortcut.</b>
   * {@link EntityStatus} forbids a word for what is being <em>done</em> — there is no {@code
   * IN_PROGRESS} there and there must never be one — and a {@code BLOCKED} word would be worse than
   * that rule's usual violation: it would <em>overwrite</em> the status, so a ticket blocked while
   * REFINED would lose the one fact saying implement is the phase to resume. Held beside the status
   * instead, both facts stay true at once: what has been achieved, and whether what runs next can
   * proceed.
   *
   * <p><b>It is temporary by construction.</b> {@code WorkEntityService.transition} clears it
   * unconditionally, so a block lives exactly as long as the phase it blocks — carrying one into
   * the next phase would assert a blocker nobody re-checked. That is what keeps this from becoming
   * a second lifecycle running alongside the first.
   *
   * <p><b>Not an {@code EntityProperty}, deliberately</b>, and the reason is
   * {@code transition_entities}: that door is full-state PUT semantics over the declared
   * properties, so an omitted one is cleared. A registry property here would silently unblock a row
   * on every reshape that did not restate it. {@link #createdAt} and {@link #updatedAt} are the
   * precedent — real columns the archetype registry is indifferent to.
   *
   * <p>A {@code boolean} and not a {@code Boolean}: the column is {@code not null} with a default
   * (V14), unblocked is the ordinary state, and there is no third answer for a null to mean.
   */
  @Column(nullable = false)
  public boolean blocked;

  /**
   * <b>Whether the phase advance carries this entity's run on by itself</b> — the continue-or-stop
   * bit of the one dispatch path (qits-394). {@code true} is <em>Dispatch</em>: every transition
   * the agent claims delivers the next phase's prompt into the workspace standing on the branch.
   * {@code false} is <em>Run the next phase</em>: one phase runs and the next transition delivers
   * nothing, so a person steps the entity on by pressing again.
   *
   * <p><b>It lives on the entity because the entity is the one row this service holds for a run of
   * work.</b> The press that records it and the transition that reads it are different requests,
   * minutes or hours apart, and the workspace the run stands in is qits-workspaces' row, not ours.
   * Written by every dispatch press ({@code EntityDispatchService.setDispatchContinues}) and by
   * nothing else — a transition leaves it alone, which is exactly what lets it survive the round
   * trip through the agent's own claim.
   *
   * <p>The release a move into VERIFIED asks for is <b>not</b> governed by it: that branch is
   * finished whoever pressed what. Not an {@code EntityProperty}, for {@link #blocked}'s reason.
   * The Java default matches the column's ({@code not null default true}, epics V16), so a row
   * created here without saying is a row that continues, as a ticket always did.
   */
  @Column(name = "dispatch_continues", nullable = false)
  public boolean dispatchContinues = true;

  /**
   * <b>The person who pre-approved scheduling this entity</b> (qits-1075), or {@code null} for no
   * pre-approval. A person's <em>Dispatch</em> (FLOW) press on a REPORTED epic or ticket writes their
   * name here before the refine turn goes out, so that when the refine phase lands REFINED the
   * platform makes the scheduling move (REFINED → READY_FOR_DEV) as that person and hands the
   * implement phase to the same session — one press from REPORTED to VERIFIED.
   *
   * <p><b>Stamped only from a verified person</b>: the dispatch door writes it from the {@code Mover}
   * qits-891's person check built, and nothing else writes a name — no REST body, no MCP tool, no
   * create. It is spent once: the automatic schedule and a person's press at REFINED clear it, as do
   * a PHASE press and a move to DROPPED. Written through {@code
   * EntityDispatchService.setPreApprovedBy}. Not an {@code EntityProperty}, for {@link #blocked}'s
   * reason.
   */
  @Column(name = "pre_approved_by")
  public String preApprovedBy;

  /**
   * Bug, improvement or the platform's own maintenance failure ({@link TicketType}), on a ticket and
   * on nothing else. Named {@code
   * ticketType} rather than {@code type}, because {@code type} in a row holding four archetypes
   * reads as the archetype, which is the one thing it is not.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "ticket_type", length = 32)
  public TicketType ticketType;

  /**
   * <b>Why a ticket came about, in the reporter's or the triage agent's own words.</b> It is what a
   * {@link EntityStatus#REPORTED} ticket consists of — an impetus and nothing else.
   *
   * <p><b>The length rule, which is the whole of the field's discipline.</b> An impetus takes one of
   * two shapes — <em>"{some error} occurs {in some context}"</em> or <em>"{an existing part} should
   * be {something to introduce or improve}"</em> — and is almost always one sentence, rarely a
   * paragraph, very rarely two. A bug's steps to reproduce may be included and do not count against
   * that length: they are part of saying what occurs.
   *
   * <p><b>Why the rule exists.</b> An impetus that grows into an essay is indistinguishable from the
   * refined {@link #description}, and at that point it stops being a record of what was originally
   * asked for — which is the one thing nothing else in the row holds.
   *
   * <p><b>It is never rewritten by a later phase.</b> Refinement writes {@link #description};
   * implementation and verification write neither. It stays editable by triage, because a report
   * filed in haste is often the wrong words for the right problem. Nullable in the column and
   * required at every intake surface: rows that predate V7 have none.
   */
  public String impetus;

  /** Who is looking at a ticket, as a free-text name. The platform has no person table. */
  public String assignee;

  /**
   * The principal that filed a ticket. Stamped from the request identity, <b>never
   * client-supplied</b> — no surface reads it off a request body and none may start.
   *
   * <p><b>It is not {@code updatable = false}, and that is a decision rather than an omission.</b>
   * The multi-entity transition re-archetypes existing rows, and a row demoted from {@code TICKET}
   * to {@code FEATURE} has no slot for this property any more: it is <em>cleared</em> there, because
   * leaving a reporter on a row that is no longer a report is a value nothing would ever correct and
   * nothing could explain. An {@code updatable = false} column would have made that clear a silent
   * no-op — the field null in Java, the column unchanged in postgres — which is the worst of the
   * three possible behaviours.
   *
   * <p>The guarantee that stands is the one that was ever meant: this column is written by the
   * server at create and by a re-archetype that removes it, and by nothing else. {@link #slug} keeps
   * {@code updatable = false} precisely because <em>its</em> rule is the opposite one — a slug is
   * never re-minted and never cleared, and the schema is where that is best said.
   */
  @Column(name = "created_by")
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
   * <b>One implemented marker for what were two</b>: a feature's {@code implemented_on} and a task's
   * {@code implemented_at}. They were never two facts — both mean "this is done, as of then" — and
   * the two names are an accident of the two tables having been written apart. {@code implementedAt}
   * wins because a timestamp answers "at", and because the epic lifecycle's guard already speaks of
   * "the implemented markers" in the plural as one rule.
   */
  @Column(name = "implemented_at")
  public Instant implementedAt;

  /**
   * <b>The implementing marker</b> (qits-749, epics V22): when the implementation of a feature or a
   * task was started — stamped by {@code mark_task_implementing} on a task, and on its feature by
   * the first of its tasks so marked. Since qits-763 the same press moves the row's status to
   * IMPLEMENTING, so the status says where it stands and this says when it got there. Skippable: a task may be marked implemented without ever being marked
   * implementing, and then this stays null. Kept as history once {@link #implementedAt} is set;
   * consumers rank that one over this one. Entering IMPLEMENTING on the epic stamps nothing.
   */
  @Column(name = "implementing_at")
  public Instant implementingAt;

  /**
   * <b>One sibling-dependency edge for what were two</b>: a feature's {@code depends_on_feature_id}
   * and a task's {@code depends_on_task_id}. Self-FK with {@code on delete set null}.
   *
   * <p><b>This is not nesting and must never be validated as nesting.</b> A dependency says "do
   * that one first"; a membership says "this one is part of that one". They point in unrelated
   * directions and have unrelated cycle rules — a dependency cycle is a real error the services
   * already check for, while a membership cycle cannot occur at all under the depth rule. The
   * parent/child relation is {@link EntityMembership} and nothing in this class.
   */
  @Column(name = "depends_on_entity_id")
  public String dependsOnEntityId;

  /**
   * <b>The acceptance criteria</b> (qits-887, qits-934, epics V26): short Markdown statements the
   * work is accepted against, in order. An {@link Archetype#EPIC}'s and a {@link Archetype#TICKET}'s,
   * and empty on every other kind ({@code control/Archetypes} refuses them there). Each item obeys
   * {@code control/AcceptanceCriteria}' rules; the list is what the {@code ACCEPTANCE_CRITERIA} gate
   * asks for on entering REFINED and READY_FOR_DEV.
   *
   * <p><b>An element collection, loaded with the row</b>, so every reader of the row — the DTOs,
   * the catalogue, the audit snapshot, a gate — has it without a second lookup, and a row handed
   * out of a write's transaction carries it rather than a proxy that can no longer load. {@code
   * SUBSELECT} makes a listing one extra query for all its rows, never one per row.
   *
   * <p><b>Write it in place</b> ({@code clear()} then {@code addAll}), never by assigning a new
   * list: Hibernate then updates the positions it already has rather than deleting and
   * re-inserting the whole collection. A row built by hand from a projection carries an empty list,
   * which is <em>not</em> a statement that it has none — such a row is never written.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @Fetch(FetchMode.SUBSELECT)
  @CollectionTable(
      name = "entity_acceptance_criterion",
      joinColumns = @JoinColumn(name = "entity_id"))
  @OrderColumn(name = "position")
  @Column(name = "text", nullable = false)
  public List<String> acceptanceCriteria = new ArrayList<>();

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
