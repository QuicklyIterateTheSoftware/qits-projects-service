package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.control.TransitionedEntity;
import eu.wohlben.qits.epics.dto.EpicDto;
import eu.wohlben.qits.epics.dto.FeatureDto;
import eu.wohlben.qits.epics.dto.TaskDto;
import eu.wohlben.qits.epics.dto.TicketDto;
import eu.wohlben.qits.projects.control.ProjectService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Fills the {@code qualifiedId} field on an epic, a ticket, a feature, a task or a transitioned
 * entity: one batched slug lookup, then a row-for-row decoration of what the mapper already
 * produced. The rendering is {@code <project-slug>-<number>} — {@code qits-1337} — and this class is
 * the only place in the estate that performs it on the way out.
 *
 * <h2>Why it sits in {@code projects.api} and is called from {@code epics.api}</h2>
 *
 * <p>{@link DispatchedWorkspaces} is the precedent and this is the same crossing, made for the same
 * reason. <b>The {@code epics} module depends on {@code domain} nowhere and must keep not depending
 * on it</b> — it has its own package, its own error types and its own physical database, and it is
 * the module most likely to be lifted out next. The project slug lives in {@code domain}'s {@code
 * project} table, in a <em>different physical database</em>, so {@code epics} could not resolve it
 * even if the module boundary had permitted the reach: {@code entity} holds the bare {@code number}
 * and the {@code project_id}, and nothing more. The <em>service</em> layer may cross, so the
 * crossing happens once, here, declared by the package this class is in.
 *
 * <h2>One call per listing, never one per row</h2>
 *
 * <p>Every entry point takes the whole collection, collects the <b>distinct</b> project ids in it
 * and makes a <b>single</b> {@link ProjectService#slugsByIds} query for the lot — one statement,
 * {@code id in (…)}. A per-row lookup would put a query inside a loop over a page, and a project
 * with four hundred entities would pay four hundred round trips for a string that is the same on
 * almost every one of them. {@code QualifiedEntityIdsTest} is what pins that, by counting the
 * statements a listing issues rather than by trusting this paragraph.
 *
 * <h2>A project that cannot be resolved leaves the field null, and never throws</h2>
 *
 * <p>An entity whose {@code projectId} names no project row — a project deleted between the two
 * reads, an entity written before its project existed — keeps a null {@code qualifiedId} and is
 * returned otherwise untouched. That is {@link DispatchedWorkspaces}' contract applied again: a
 * decoration must degrade to what the screen showed before the field existed, never fail the read
 * it decorates. An empty input is returned unchanged, without asking the database at all.
 */
@ApplicationScoped
public class QualifiedEntityIds {

  /** The separator between the project slug and the number. One place, so nothing re-decides it. */
  private static final String SEPARATOR = "-";

  @Inject ProjectService projectService;

  /**
   * The rendering itself, and the only place it happens on the way out. {@code
   * CommitSubjectEntities.QualifiedId#rendered()} is the reading half and produces the identical
   * string; the two are asserted equal rather than left to agree by eye.
   */
  public static String render(String projectSlug, long number) {
    return projectSlug + SEPARATOR + number;
  }

  // --- Epics ------------------------------------------------------------------------------------

  /** The epics, each told what it is called in a commit subject. One lookup for the whole list. */
  public List<EpicDto> qualifyEpics(List<EpicDto> epics) {
    return qualify(epics, EpicDto::projectId, EpicDto::number, EpicDto::withQualifiedId);
  }

  /** One epic — the same call, asked about a list of one. */
  public EpicDto qualify(EpicDto epic) {
    return qualifyEpics(List.of(epic)).get(0);
  }

  // --- Tickets ----------------------------------------------------------------------------------

  /** The tickets, each told what it is called in a commit subject. */
  public List<TicketDto> qualifyTickets(List<TicketDto> tickets) {
    return qualify(tickets, TicketDto::projectId, TicketDto::number, TicketDto::withQualifiedId);
  }

  /** One ticket. */
  public TicketDto qualify(TicketDto ticket) {
    return qualifyTickets(List.of(ticket)).get(0);
  }

  // --- Features ---------------------------------------------------------------------------------

  /** The features, each told what it is called in a commit subject. */
  public List<FeatureDto> qualifyFeatures(List<FeatureDto> features) {
    return qualify(features, FeatureDto::projectId, FeatureDto::number, FeatureDto::withQualifiedId);
  }

  /** One feature. */
  public FeatureDto qualify(FeatureDto feature) {
    return qualifyFeatures(List.of(feature)).get(0);
  }

  // --- Tasks ------------------------------------------------------------------------------------

  /** The tasks, each told what it is called in a commit subject. */
  public List<TaskDto> qualifyTasks(List<TaskDto> tasks) {
    return qualify(tasks, TaskDto::projectId, TaskDto::number, TaskDto::withQualifiedId);
  }

  /** One task. */
  public TaskDto qualify(TaskDto task) {
    return qualifyTasks(List.of(task)).get(0);
  }

  // --- Transitioned entities --------------------------------------------------------------------

  /**
   * The merged answer shape, each entry told what it is called in a commit subject. A transition's
   * batch is one project by construction — the service refuses a cross-project move — but the
   * lookup is batched all the same, because {@code list_entities} answers this shape too and the
   * rule here is about the read, not about the writer.
   */
  public List<TransitionedEntity> qualifyEntities(List<TransitionedEntity> entities) {
    return qualify(
        entities,
        TransitionedEntity::projectId,
        TransitionedEntity::number,
        TransitionedEntity::withQualifiedId);
  }

  /** One transitioned entity. */
  public TransitionedEntity qualify(TransitionedEntity entity) {
    return qualifyEntities(List.of(entity)).get(0);
  }

  /**
   * The same map a transition answers, keyed exactly as it was handed back. The values are qualified
   * in <b>one</b> lookup for the whole map.
   */
  public Map<String, TransitionedEntity> qualifyEntities(Map<String, TransitionedEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return entities;
    }
    Map<String, String> slugs = slugsFor(entities.values(), TransitionedEntity::projectId);
    java.util.LinkedHashMap<String, TransitionedEntity> qualified = new java.util.LinkedHashMap<>();
    entities.forEach((key, entity) -> qualified.put(key, qualified(entity, slugs)));
    return qualified;
  }

  // --- The one implementation -------------------------------------------------------------------

  /**
   * Collect the distinct project ids, ask once, decorate. Every public entry point above is this
   * method with three accessors, so there is exactly one place the N+1 could be reintroduced.
   */
  private <T> List<T> qualify(
      List<T> rows,
      Function<T, String> projectId,
      Function<T, Long> number,
      java.util.function.BiFunction<T, String, T> withQualifiedId) {
    if (rows == null || rows.isEmpty()) {
      return rows;
    }
    Map<String, String> slugs = slugsFor(rows, projectId);
    return rows.stream()
        .map(
            row -> {
              String slug = slugs.get(projectId.apply(row));
              // No project row: leave it null. A decoration never throws — see the class javadoc.
              return slug == null ? row : withQualifiedId.apply(row, render(slug, number.apply(row)));
            })
        .toList();
  }

  private TransitionedEntity qualified(TransitionedEntity entity, Map<String, String> slugs) {
    String slug = slugs.get(entity.projectId());
    return slug == null ? entity : entity.withQualifiedId(render(slug, entity.number()));
  }

  /** The distinct project ids of a whole collection, resolved in ONE query. */
  private <T> Map<String, String> slugsFor(Collection<T> rows, Function<T, String> projectId) {
    Set<String> ids = new LinkedHashSet<>();
    for (T row : rows) {
      String id = projectId.apply(row);
      if (id != null && !id.isBlank()) {
        ids.add(id);
      }
    }
    return projectService.slugsByIds(ids);
  }
}
