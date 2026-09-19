package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.WorkEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The merged planning rows, plain CRUD; the caller owns the transaction, exactly as {@link
 * EpicRepository} and {@link DossierPageRepository} leave it.
 *
 * <p><b>{@code EpicService} and {@code TicketService} read every epic and every ticket through
 * this.</b> {@link #listByProjectAndArchetype} and {@link #listByProjectArchetypeAndStatus} are the
 * two listings the board draws, one query each; {@link #slugsInScope} is what a create mints its
 * slug against. The reads the rest of the merge still needs are carried here too, and were carried
 * <em>before</em> they had a caller so that each task inherits a shape rather than inventing one —
 * in particular {@link #listByIds}, which exists so a tree read is two queries instead of one per
 * node. A merged model is read by fanning out over memberships, and the N+1 that invites is the one
 * performance mistake this table makes easy.
 *
 * <p>The orders match today's: oldest first, id as the tie-break, which is what {@code
 * EpicRepository.listByProject} already answers and what V2's slug backfill ranked duplicates by.
 *
 * <p><b>The by-id read is the inherited {@code findByIdOptional}</b> and is deliberately not
 * re-declared here: Panache's own {@code findById} returns the entity or null, so an override
 * returning an {@link Optional} under that name would not compile, and a second name for the same
 * query is a second thing to keep in step.
 */
@ApplicationScoped
public class WorkEntityRepository implements PanacheRepositoryBase<WorkEntity, String> {

  private static final Sort OLDEST_FIRST = Sort.by("createdAt").and("id");

  /** Every row of a project, whatever its archetype or depth. */
  public List<WorkEntity> listByProject(String projectId) {
    return find("projectId", OLDEST_FIRST, projectId).list();
  }

  /**
   * A project's rows of one kind — the read behind "the epics of this project" and "the tickets of
   * this project", which are two listings over one table now. Served by {@code
   * idx_entity_project_id}.
   */
  public List<WorkEntity> listByProjectAndArchetype(String projectId, Archetype archetype) {
    return find("projectId = ?1 and archetype = ?2", OLDEST_FIRST, projectId, archetype).list();
  }

  /**
   * The same list narrowed to one phase, over {@code idx_entity_project_status}. The status is a
   * String here for the reason {@link WorkEntity#status} is one: there is no Java type that is
   * either an epic's word or a ticket's, and the archetype is what says which vocabulary the caller
   * is asking in.
   */
  public List<WorkEntity> listByProjectArchetypeAndStatus(
      String projectId, Archetype archetype, String status) {
    return find(
            "projectId = ?1 and archetype = ?2 and status = ?3",
            OLDEST_FIRST,
            projectId,
            archetype,
            status)
        .list();
  }

  /**
   * <b>The bulk read, and the reason it is here before anything calls it.</b> Reading a tree means
   * resolving a set of child ids that a membership query just produced; doing that one id at a time
   * is an N+1 over the deepest read this module has. Callers fetch the whole level at once and index
   * the result themselves.
   *
   * <p>An empty input answers an empty list <em>without</em> asking the database: {@code in ()} is a
   * syntax error in postgres, so the guard is correctness rather than an optimisation.
   */
  public List<WorkEntity> listByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return find("id in ?1", OLDEST_FIRST, ids).list();
  }

  /**
   * The rows whose {@code depends_on_entity_id} points at {@code entityId} — {@code
   * FeatureRepository.listDependents}' and {@code TaskRepository.listDependents}' question, asked
   * once now that the two columns are one.
   *
   * <p>It is reached only from a delete, which clears each dependent's pointer in-service so the
   * clear gets its own UPDATE audit row rather than happening invisibly under the FK's {@code on
   * delete set null}. No read path calls it, which is why {@code ReadPatience} does not wrap it: it
   * runs inside an open transaction, where a retry would re-run on a connection already marked
   * rollback-only.
   */
  public List<WorkEntity> listDependents(String entityId) {
    return find("dependsOnEntityId", OLDEST_FIRST, entityId).list();
  }

  /**
   * <b>The row a qualified id names</b>, or empty — {@code uq_entity_project_number} hit directly,
   * which is the whole reason that constraint is over {@code (project_id, number)} and nothing
   * else. At most one row by construction, whatever its archetype: the number names a NODE.
   *
   * <p>Its caller is {@code projects/epicshost/CommitSubjectEntities}, which reads {@code
   * <project-slug>-<number>} off a commit subject. <b>Empty is an ordinary answer there</b> — a
   * subject may name a number nobody ever allocated — so this answers an {@link Optional} rather
   * than throwing, and nothing here logs.
   */
  public Optional<WorkEntity> findByProjectAndNumber(String projectId, long number) {
    return find("projectId = ?1 and number = ?2", projectId, number).firstResultOptional();
  }

  /**
   * The row holding {@code slug} within {@code slugScope}, or empty. At most one, because {@code
   * uq_entity_slug_scope_slug} says so — that constraint is today's three slug constraints
   * expressed once (see {@link WorkEntity#slugScope}).
   */
  public Optional<WorkEntity> findBySlug(String slugScope, String slug) {
    return find("slugScope = ?1 and slug = ?2", slugScope, slug).firstResultOptional();
  }

  /**
   * <b>Every row living in any of {@code slugScopes}</b> — the bulk half of {@link #slugsInScope},
   * and it answers rows rather than strings because its caller needs to know <em>who</em> holds each
   * slug.
   *
   * <p>It exists for the multi-entity transition's slug-scope layer. A move keeps the slug and
   * changes the scope, so the question that has to be answered before anything is written is "does
   * the post-state put two rows on one slug in one scope" — and the id is what says whether the
   * resident is somebody else or the very row being moved. {@code slugsInScope} cannot answer that,
   * and asking it once per moved entity would be the N+1 this model makes easy.
   *
   * <p>An empty input answers an empty list <em>without</em> asking the database, for {@link
   * #listByIds}' reason: {@code in ()} is a syntax error in postgres.
   */
  public List<WorkEntity> listBySlugScopes(Collection<String> slugScopes) {
    if (slugScopes == null || slugScopes.isEmpty()) {
      return List.of();
    }
    return find("slugScope in ?1", OLDEST_FIRST, slugScopes).list();
  }

  /**
   * The slugs already taken in a scope — what {@code Slugs.unique} needs in order to mint the next
   * free {@code -2}, {@code -3}, … within it.
   */
  public List<String> slugsInScope(String slugScope) {
    return getEntityManager()
        .createQuery("select e.slug from WorkEntity e where e.slugScope = :scope", String.class)
        .setParameter("scope", slugScope)
        .getResultList();
  }
}
