package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectEntities.NamedEntity;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectEntities.QualifiedId;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * <b>An entity named either way — its UUID or its qualified id {@code <project-slug>-<n>} — resolved
 * to the row.</b> The doors that take an id a person typed ({@code /work/{qualifiedId}/comments}, the
 * {@code add_comment} tool, {@code qits work --entity qits-551}) accept both, and this is the one
 * place the second form is looked up. The grammar stays {@link CommitSubjectEntities}' ({@link
 * CommitSubjectEntities#parse}); what moved here with qits-551 is the lookup that class made for
 * a commit subject, so the doors and the subject reader cannot disagree about what {@code qits-7}
 * names.
 *
 * <h2>The id first, the qualified form second</h2>
 *
 * <p>A UUID whose last group is all digits parses as a qualified id ({@code …-123456789012}), so the
 * string is tried as an entity id before it is tried as anything else: a real row under that id is
 * the answer, and only a miss falls through to the grammar. The reverse order would read such a
 * UUID as a number in a project that does not exist and answer 404 for a row that does.
 *
 * <h2>Across projects, like the subject reader</h2>
 *
 * <p>Resolution names no "current project": {@code other-7} is project {@code other}'s entity 7
 * wherever it is asked. The door that binds a caller to a project compares afterwards, in its own
 * words — the REST binding's 403, the MCP scope's "not found in this project".
 *
 * <h2>Two databases, two transactions</h2>
 *
 * <p>The slug is in {@code domain}'s {@code project} table and the {@code (project_id, number)}
 * pair in {@code entities}' {@code entity} table, two local (non-XA) datasources, and Narayana
 * enlists only one such resource per transaction — the rule {@code EpicMcpTools} states for the
 * MCP tools. Each read is {@code requiringNew}, so this is callable from a request thread, an MCP
 * tool and a plain worker thread with no request scope alike.
 */
@ApplicationScoped
public class EntityIdResolver {

  @Inject ProjectService projects;

  @Inject WorkEntityRepository entities;

  /**
   * The row {@code idOrQualified} names, of any archetype, or a 404 naming what was asked for.
   *
   * @param idOrQualified an entity UUID, or {@code <project-slug>-<n>}
   */
  public WorkEntity resolve(String idOrQualified) {
    if (idOrQualified == null || idOrQualified.isBlank()) {
      throw new NotFoundException("Entity not found: " + idOrQualified);
    }
    Optional<WorkEntity> byId =
        QuarkusTransaction.requiringNew()
            .call(() -> entities.findByIdOptional(idOrQualified.trim()));
    if (byId.isPresent()) {
      return byId.get();
    }
    return CommitSubjectEntities.parse(idOrQualified)
        .flatMap(this::row)
        .orElseThrow(() -> new NotFoundException("Entity not found: " + idOrQualified));
  }

  /**
   * <b>The project a person named, by its id or by its slug</b> (qits-548) — the {@code project} of
   * {@code POST /work} and the {@code {project}} of {@code GET /projects/{project}/work},
   * where {@code qits} is what a person types and the id is what a program holds. The slug first:
   * slugs are unique (V6) and never UUID-shaped by derivation, and a miss falls through to the id,
   * whose absence is {@code ProjectService.get}'s 404 naming what was asked for.
   */
  public Project resolveProject(String idOrSlug) {
    if (idOrSlug == null || idOrSlug.isBlank()) {
      throw new NotFoundException("Project not found: " + idOrSlug);
    }
    String named = idOrSlug.trim();
    return QuarkusTransaction.requiringNew()
        .call(() -> projects.findBySlug(named).orElseGet(() -> projects.get(named)));
  }

  /**
   * <b>A qualified id looked up</b>: the project slug, then the {@code (project_id, number)} pair
   * against {@code uq_entity_project_number}. A project or an entity that does not exist is
   * {@link Optional#empty()}, never a complaint — for a commit subject that is the ordinary case
   * (see {@link CommitSubjectEntities}), and a door turns it into its own 404.
   */
  public Optional<NamedEntity> lookup(QualifiedId id) {
    return row(id)
        .map(
            entity ->
                new NamedEntity(
                    entity.id,
                    id.rendered(),
                    entity.projectId,
                    id.projectSlug(),
                    entity.number,
                    entity.archetype,
                    entity.status,
                    entity.title));
  }

  /** The row behind a qualified id, or empty: one read per database, each in its own transaction. */
  private Optional<WorkEntity> row(QualifiedId id) {
    Optional<Project> project =
        QuarkusTransaction.requiringNew().call(() -> projects.findBySlug(id.projectSlug()));
    if (project.isEmpty()) {
      return Optional.empty();
    }
    String projectId = project.get().id;
    return QuarkusTransaction.requiringNew()
        .call(() -> entities.findByProjectAndNumber(projectId, id.number()));
  }
}
