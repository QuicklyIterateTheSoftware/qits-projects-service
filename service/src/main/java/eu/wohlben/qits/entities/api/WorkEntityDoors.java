package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.ArchetypeRegistryDocument;
import eu.wohlben.qits.entities.control.ArchetypeViolation;
import eu.wohlben.qits.entities.control.AuditService;
import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityProperty;
import eu.wohlben.qits.entities.control.EntitySummary;
import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.Nested;
import eu.wohlben.qits.entities.control.TransitionGate;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.dto.AuditEntryDto;
import eu.wohlben.qits.entities.dto.CommentDto;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.mapper.AuditEntryMapper;
import eu.wohlben.qits.entities.mapper.EntityCommentMapper;
import eu.wohlben.qits.projects.api.EntityBlocks;
import eu.wohlben.qits.projects.api.PhaseAdvance;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jboss.logging.Logger;

/**
 * <b>The generic work-entity doors</b> (qits-969): every rule behind the archetype-free {@code /work}
 * family addressed by qualified id ({@code Work*Controller}, {@code ProjectWorkController}). They
 * were moved here verbatim from the {@code /entities} and per-archetype controllers, which epic
 * qits-965 deleted in its phase 3 (qits-976). Nothing here names a controller class.
 *
 * <p><b>Ids in bodies.</b> Every entity id a body carries — the transition map's keys, a transition
 * entry's {@code parent}, {@code supersededBy} and {@code dependsOn}, a patch's {@code dependsOn} —
 * may be a qualified id ({@code qits-7}) as well as a UUID ({@link #toIds}).
 *
 * <p>Nothing here fires inside a write's retried body: every hint goes out after the service
 * returns, as each controller fired it before.
 */
@ApplicationScoped
public class WorkEntityDoors {

  private static final Logger LOG = Logger.getLogger(WorkEntityDoors.class);

  /** RFC 7396's media type. Plain {@code application/json} is accepted beside it. */
  public static final String MERGE_PATCH_JSON = "application/merge-patch+json";

  /** The properties a patch refuses, each with the door to use instead. */
  private static final Map<String, String> MOVES =
      Map.of(
          "status", "a lifecycle move — POST /projects/api/work/{qualifiedId}/status",
          "archetype",
              "a reshape — state it through PUT /projects/api/work/{qualifiedId} or POST"
                  + " /projects/api/work/transition",
          "membership",
              "a reparent — state it through PUT /projects/api/work/{qualifiedId} or POST"
                  + " /projects/api/work/transition",
          "supersededBy",
              "a supersede — state it through PUT /projects/api/work/{qualifiedId} or POST"
                  + " /projects/api/work/transition",
          "blocked", "the block — set it through POST /projects/api/work/{qualifiedId}/blocked");

  /** What the server writes on an entity and a patch never does. */
  private static final List<String> SERVER_OWNED = List.of("slug", "createdBy");

  /** What the server writes on a comment and a patch names only to be refused. */
  private static final List<String> COMMENT_SERVER_OWNED =
      List.of("id", "entityId", "author", "createdAt", "updatedAt");

  /**
   * The editable properties, as {@code EntityTransition} spells them, and their registry slot — read
   * off {@link EntityWireProperties}, the table the published update schema is built from, so the
   * schema a client is handed and the names the patch accepts are one list (qits-548).
   */
  private static final Map<String, EntityProperty> EDITABLE = EntityWireProperties.editable();

  /**
   * Editable, but with no clear flag behind them: every kind that permits one requires it. The
   * table's {@code clearable} column, which is also what types these as non-nullable in the schema.
   */
  private static final List<String> NOT_CLEARABLE = EntityWireProperties.notClearable();

  @Inject WorkEntityService entities;

  @Inject EntityCatalogService catalog;

  @Inject EntityTransitionService transitions;

  @Inject EntityCommentService comments;

  @Inject EntityCommentMapper commentMapper;

  @Inject EntityIdResolver ids;

  @Inject RepositoryService repositories;

  @Inject ProjectChangePublisher publisher;

  @Inject EpicsTopicHints epicHints;

  @Inject TicketsTopicHints ticketHints;

  @Inject QualifiedEntityIds qualifiedIds;

  /** The only way a door moves a lifecycle: discards a refinement a resolving move would strand. */
  @Inject EntityResolutions resolutions;

  /** The next phase after a move, delivered outside the move's transaction. */
  @Inject PhaseAdvance phaseAdvance;

  /** Who is moving, as qits-891's person check says (qits-887) — see {@link EntityMovers}. */
  @Inject EntityMovers movers;

  @Inject EntityBlocks blocks;

  @Inject AuditService audits;

  @Inject AuditEntryMapper auditMapper;

  /** Every quality gate (qits-887), so each served move names the gates it has to pass. */
  @Inject Instance<TransitionGate> gates;

  // --- read ------------------------------------------------------------------------------------

  /** The row in the merged shape, qualified. */
  public TransitionedEntity read(WorkEntity row) {
    return qualifiedIds.qualify(catalog.byIds(List.of(row.id)).get(row.id));
  }

  // --- create ----------------------------------------------------------------------------------

  /**
   * A new row of any archetype from one body shape — see {@code POST /work} for the wire. The order
   * is the entity doors': the placement (404), the binding (403), the body (400), the write.
   */
  public TransitionedEntity create(SecurityIdentity identity, JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new BadRequestException("a create must be a JSON object");
    }
    Archetype archetype = archetypeOf(body.get("archetype"));

    // The placement, resolved before anything is judged: its 404, then the binding's 403.
    String projectId = null;
    String under = null;
    WorkEntity parent = null;
    if (Archetypes.mayBeRoot(archetype)) {
      String project = text(body, EntitySchemas.PROJECT);
      if (project != null && !project.isBlank()) {
        projectId = ids.resolveProject(project).id;
        under = projectId;
      }
    } else {
      String named = text(body, EntitySchemas.PARENT);
      if (named != null && !named.isBlank()) {
        parent = ids.resolve(named);
        projectId = parent.projectId;
        under = parent.id;
      }
    }
    if (projectId != null) {
      EntitiesAgentAccess.requireProject(identity, projectId);
    }

    List<String> refused = new ArrayList<>(EntitySchemas.createRefusals(archetype, body));
    Archetype parentKind = WorkEntityService.parentKindOf(archetype);
    if (parent != null && parent.archetype != parentKind) {
      refused.add(
          "a "
              + archetype
              + "'s parent must be "
              + (parentKind == Archetype.EPIC ? "an " : "a ")
              + parentKind
              + ": "
              + text(body, EntitySchemas.PARENT)
              + " is a "
              + parent.archetype);
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }

    EntityWrite write =
        new EntityWrite(
            text(body, "title"),
            text(body, "description"),
            false,
            text(body, "impetus"),
            false,
            text(body, "ticketType"),
            text(body, "assignee"),
            false,
            text(body, "repositoryId"),
            entityId(text(body, "dependsOn")),
            false,
            null,
            false,
            null,
            EntitySchemas.strings(body.get("acceptanceCriteria")).orElse(null));
    requireRepositoryIn(write.repositoryId(), projectId);

    Nested created = entities.create(archetype, under, write, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.of(archetype));
    String id = created.entity().id;
    return qualifiedIds.qualify(catalog.byIds(List.of(id)).get(id));
  }

  /** The body's archetype, or a 400 naming what is wrong with it. */
  private static Archetype archetypeOf(JsonNode value) {
    if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
      throw new BadRequestException(
          "archetype is required: EPIC, TICKET, FEATURE, TASK or CAMPAIGN");
    }
    try {
      return Archetype.valueOf(value.textValue().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown archetype: " + value.textValue());
    }
  }

  /** The property's value when it was sent as text; null when absent, null or not text. */
  private static String text(JsonNode body, String name) {
    JsonNode value = body.get(name);
    return value == null || !value.isTextual() ? null : value.textValue();
  }

  // --- patch -----------------------------------------------------------------------------------

  /**
   * Applies a merge patch to the entity {@code id} (a UUID) and answers it as it now stands. The
   * order is the refusal order: the id (404), the binding (403), the body (400), the write.
   */
  public TransitionedEntity patch(SecurityIdentity identity, String id, JsonNode body) {
    TransitionedEntity current = catalog.byIds(List.of(id)).get(id);
    if (current == null) {
      throw new NotFoundException("Entity not found: " + id);
    }
    EntitiesAgentAccess.requireProject(identity, current.projectId());

    // A dependency may be named by its qualified id as well as its UUID.
    EntityWrite write = readPatch(current, body, this::entityId);
    requireRepositoryIn(write.repositoryId(), current.projectId());
    Nested updated =
        entities.update(current.archetype(), id, write, EntitiesPrincipal.changedBy(identity));

    // After the write and outside it, as the transition door fires them: its body is retried.
    epicHints.fire(current.projectId());
    ticketHints.fire(current.projectId());
    // The row the write handed back, never a re-read — see TransitionedEntity.edited for why a
    // re-read in this request answers the row as it was.
    return qualifiedIds.qualify(TransitionedEntity.edited(updated.entity(), current));
  }

  /** A task's repository must be in the entity's project — a task must not bind a repository from an unrelated project. */
  private void requireRepositoryIn(String repositoryId, String projectId) {
    if (repositoryId == null) {
      return;
    }
    Repository repo = repositories.get(repositoryId); // 404 if absent
    if (repo.project == null || !Objects.equals(projectId, repo.project.id)) {
      throw new BadRequestException(
          "Repository " + repositoryId + " is not in this entity's project");
    }
  }

  /**
   * The body as an {@link EntityWrite} for {@code current}'s archetype, or one 400 carrying every
   * complaint about it.
   */
  private static EntityWrite readPatch(
      TransitionedEntity current, JsonNode body, UnaryOperator<String> dependency) {
    if (body == null || !body.isObject()) {
      throw new BadRequestException("a merge patch must be a JSON object");
    }
    if (body.isEmpty()) {
      throw new BadRequestException("a merge patch must name at least one property");
    }
    Archetype archetype = current.archetype();
    List<String> refused = new ArrayList<>();
    for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      JsonNode value = body.get(name);
      if (MOVES.containsKey(name)) {
        String door = MOVES.get(name);
        refused.add(name + " is not written by a patch: it is " + door);
      } else if (SERVER_OWNED.contains(name)) {
        refused.add(name + " is server-owned and never written");
      } else if (!EDITABLE.containsKey(name)) {
        refused.add("unknown property: " + name);
      } else if (!Archetypes.spec(archetype).permits(EDITABLE.get(name))) {
        refused.add(
            new ArchetypeViolation(
                    archetype, EDITABLE.get(name), ArchetypeViolation.Reason.NOT_PERMITTED, null)
                .message());
      } else if (value.isNull()) {
        if (NOT_CLEARABLE.contains(name)) {
          refused.add(name + " cannot be cleared");
        }
      } else if (EDITABLE.get(name) == EntityProperty.ACCEPTANCE_CRITERIA) {
        EntitySchemas.listRefusal(name, value).ifPresent(refused::add);
      } else if (!value.isTextual()) {
        refused.add(name + " must be a string");
      } else if (name.equals("implementedAt") || name.equals("implementingAt")) {
        try {
          Instant.parse(value.textValue());
        } catch (DateTimeParseException e) {
          refused.add(name + " must be an ISO-8601 instant: " + value.textValue());
        }
      }
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }

    String implementedAt = patchText(body, "implementedAt");
    String implementingAt = patchText(body, "implementingAt");
    return new EntityWrite(
        patchText(body, "title"),
        patchText(body, "description"),
        cleared(body, "description"),
        patchText(body, "impetus"),
        cleared(body, "impetus"),
        patchText(body, "ticketType"),
        patchText(body, "assignee"),
        cleared(body, "assignee"),
        patchText(body, "repositoryId"),
        dependency.apply(patchText(body, "dependsOn")),
        cleared(body, "dependsOn"),
        implementedAt == null ? null : Instant.parse(implementedAt),
        cleared(body, "implementedAt"),
        implementingAt == null ? null : Instant.parse(implementingAt),
        criteria(body));
  }

  /**
   * The acceptance criteria as the patch states them (qits-887): null when the patch does not name
   * them (left alone), an empty list when it sends null (cleared), else the array's items.
   */
  private static List<String> criteria(JsonNode body) {
    JsonNode value = body.get("acceptanceCriteria");
    if (value == null) {
      return null;
    }
    return value.isNull() ? List.of() : EntitySchemas.strings(value).orElseThrow();
  }

  /** The property's value when it was sent as text; null when absent or sent as null. */
  private static String patchText(JsonNode body, String name) {
    JsonNode value = body.get(name);
    return value == null || value.isNull() ? null : value.textValue();
  }

  /** Whether the property was sent, and sent as null — RFC 7396's "remove". */
  private static boolean cleared(JsonNode body, String name) {
    JsonNode value = body.get(name);
    return value != null && value.isNull();
  }

  // --- the lifecycle move ----------------------------------------------------------------------

  /**
   * Moves {@code row} to {@code target}. The order is the doors': the epic's role (403), the binding
   * (403), the target (400), the move (409). The move goes through {@link EntityResolutions} — a
   * resolving move tears the row's refinement room down before the status lands — then the
   * archetype's hint, then {@link PhaseAdvance}, after the move is recorded and outside its
   * transaction: a transition that rolled back speaks to nobody, and a throw in the advance must not
   * touch a move that has already been answered for. {@code PhaseAdvance} returns at once for a
   * campaign.
   */
  public TransitionedEntity move(SecurityIdentity identity, WorkEntity row, String target) {
    Archetype archetype = row.archetype;
    // qits:admin-agent is admitted too (qits-628 follow-up); remove it here if this door must
    // stay human-only.
    if (archetype == Archetype.EPIC
        && !identity.hasRole(AgentAccess.ADMIN_ROLE)
        && !identity.hasRole(AgentAccess.ADMIN_AGENT_ROLE)) {
      throw new ForbiddenException(
          "Moving an epic's status is qits:admin alone; an agent's claim goes through the"
              + " transition_epic MCP tool.");
    }
    EntitiesAgentAccess.requireProject(identity, row.projectId);

    TransitionedEntity before = catalog.byIds(List.of(row.id)).get(row.id);
    // The mover's name is the audit's: a verified person's own, never a header (qits-887).
    Mover mover = movers.of(identity);
    WorkEntityService.Transition moved = resolutions.transition(archetype, row.id, target, mover);
    // A supersede spawns a second row in the same project, so one hint covers both.
    publisher.fire(moved.entity().projectId, ProjectChangeHint.Topic.of(archetype));
    try {
      phaseAdvance.afterTransition(moved.entity(), moved.statusBefore(), mover.name());
    } catch (RuntimeException e) {
      // It says it must not throw; a throw is a bug in it and must not touch a recorded move.
      LOG.warnf(
          e,
          "Could not start the phase %s %s just moved into",
          archetype.name().toLowerCase(Locale.ROOT),
          moved.entity().id);
    }
    return qualifiedIds.qualify(
        TransitionedEntity.moved(moved.entity(), before, moved.statusBefore(), mover.name()));
  }

  // --- the block -------------------------------------------------------------------------------

  /** Blocks or unblocks {@code entity}: the binding (403), then {@link EntityBlocks}' rules. */
  public EntityBlocks.Blocked block(
      SecurityIdentity identity, WorkEntity entity, boolean blocked, String reason) {
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    WorkEntity written =
        blocks.apply(entity, blocked, reason, EntitiesPrincipal.changedBy(identity));
    publisher.fire(written.projectId, ProjectChangeHint.Topic.of(written.archetype));
    return EntityBlocks.Blocked.of(written);
  }

  // --- the thread ------------------------------------------------------------------------------

  /** The entity's thread, oldest first. */
  public List<CommentDto> comments(WorkEntity entity) {
    return comments.listComments(entity.id).stream().map(commentMapper::toDto).toList();
  }

  /** A remark on the entity's thread, its author stamped from the caller. */
  public CommentDto addComment(SecurityIdentity identity, WorkEntity entity, String body) {
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    EntityComment comment =
        comments.addComment(entity.id, body, EntitiesPrincipal.changedBy(identity));
    publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
    return commentMapper.toDto(comment);
  }

  /** The comment {@code commentId}, or a 404. */
  public EntityComment comment(String commentId) {
    return comments.getComment(commentId);
  }

  /**
   * The comment {@code commentId} on {@code entity}'s thread: a comment on another entity's thread
   * is a 404 here, the same answer as one that does not exist, because the path named the pair.
   */
  public EntityComment comment(WorkEntity entity, String commentId) {
    EntityComment comment = comments.getComment(commentId);
    if (!entity.id.equals(comment.entityId)) {
      throw new NotFoundException("Comment not found on this entity: " + commentId);
    }
    return comment;
  }

  /** The entity a comment hangs on — any archetype — for its project and its topic. */
  public WorkEntity entityOf(EntityComment comment) {
    return entities.find(comment.entityId);
  }

  /**
   * Edits a comment's text by merge patch: the binding (403), the patch (400), the write. {@code
   * entity} is the comment's own.
   */
  public CommentDto editComment(
      SecurityIdentity identity, WorkEntity entity, EntityComment comment, JsonNode patch) {
    EntitiesAgentAccess.requireProject(identity, entity.projectId);
    String body = commentBodyOf(patch);
    EntityComment edited =
        comments.updateComment(comment.id, body, EntitiesPrincipal.changedBy(identity));
    publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
    return commentMapper.toDto(edited);
  }

  /** Deletes a comment; {@code entity} is the comment's own, resolved before the delete. */
  public void deleteComment(SecurityIdentity identity, WorkEntity entity, EntityComment comment) {
    comments.deleteComment(comment.id, EntitiesPrincipal.changedBy(identity));
    publisher.fire(entity.projectId, ProjectChangeHint.Topic.of(entity.archetype));
  }

  /** The new text, or one 400 carrying every complaint about the patch. */
  private static String commentBodyOf(JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw new BadRequestException("a merge patch must be a JSON object");
    }
    if (patch.isEmpty()) {
      throw new BadRequestException("a merge patch must name at least one property");
    }
    List<String> refused = new ArrayList<>();
    for (Iterator<String> names = patch.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      JsonNode value = patch.get(name);
      if (name.equals("body")) {
        if (value.isNull()) {
          refused.add("body cannot be cleared — delete the comment instead");
        } else if (!value.isTextual()) {
          refused.add("body must be a string");
        } else if (value.textValue().isBlank()) {
          refused.add("body must not be blank");
        }
      } else if (COMMENT_SERVER_OWNED.contains(name)) {
        refused.add(name + " is server-owned and never written");
      } else {
        refused.add("unknown property: " + name);
      }
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
    return patch.get("body").textValue();
  }

  // --- the transition --------------------------------------------------------------------------

  /**
   * Applies a stated post-state and answers what was written, keyed the way the request was. Every
   * id in the request may be a qualified id ({@link #toIds}); the answer
   * is keyed by the keys exactly as they were sent, so a caller reads its statement and the result
   * side by side whichever form it used.
   */
  public Map<String, TransitionedEntity> transition(
      SecurityIdentity identity, Map<String, EntityTransition> request) {
    Map<String, String> keys = new LinkedHashMap<>();
    Map<String, EntityTransition> stated = request;
    if (request != null) {
      stated = toIds(request, keys);
    }
    requireAgentProjects(identity, stated);
    Map<String, TransitionedEntity> written =
        transitions.transition(stated, EntitiesPrincipal.changedBy(identity));

    Set<String> projects = new LinkedHashSet<>();
    for (TransitionedEntity entity : written.values()) {
      projects.add(entity.projectId());
    }
    for (String projectId : projects) {
      epicHints.fire(projectId);
      ticketHints.fire(projectId);
    }
    // One slug lookup for the whole batch, and the map keeps its keys. The service leaves
    // qualifiedId null because epics cannot see domain; this is where it is filled.
    Map<String, TransitionedEntity> answer = qualifiedIds.qualifyEntities(written);
    if (keys.isEmpty()) {
      return answer;
    }
    Map<String, TransitionedEntity> asSent = new LinkedHashMap<>();
    answer.forEach((id, entity) -> asSent.put(keys.getOrDefault(id, id), entity));
    return asSent;
  }

  /**
   * One entity's whole post-state, stated at its own address — {@code PUT /work/{qualifiedId}}: the
   * transition of a one-entry map, keyed by the row's UUID.
   */
  public TransitionedEntity put(
      SecurityIdentity identity, WorkEntity row, EntityTransition state) {
    // The row's own project first, in the doors' refusal order; the batch binding below adds the
    // parent the state names.
    EntitiesAgentAccess.requireProject(identity, row.projectId);
    if (state == null) {
      throw new BadRequestException("a PUT must state the entity: a JSON object");
    }
    Map<String, EntityTransition> request = new LinkedHashMap<>();
    request.put(row.id, state);
    return transition(identity, request).get(row.id);
  }

  /**
   * The request with every entity id resolved to the UUID the transition compares: the keys, and
   * each entry's {@code parent}, {@code supersededBy} and {@code dependsOn}. A value that resolves
   * to nothing is handed on unchanged, for the transition's own 400 about an id naming nothing —
   * the contract the transition door has always kept. {@code keys} is filled with UUID → the key as
   * sent, for the answer.
   */
  private Map<String, EntityTransition> toIds(
      Map<String, EntityTransition> request, Map<String, String> keys) {
    Map<String, String> resolved = new HashMap<>();
    Map<String, EntityTransition> out = new LinkedHashMap<>();
    List<String> refused = new ArrayList<>();
    for (Map.Entry<String, EntityTransition> entry : request.entrySet()) {
      String id = resolveOrKeep(entry.getKey(), resolved);
      String earlier = keys.putIfAbsent(id, entry.getKey());
      if (earlier != null) {
        refused.add(earlier + " and " + entry.getKey() + " name the same entity");
        continue;
      }
      out.put(id, withIds(entry.getValue(), resolved));
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
    return out;
  }

  private EntityTransition withIds(EntityTransition entry, Map<String, String> resolved) {
    if (entry == null) {
      return null;
    }
    EntityTransition.Membership membership = entry.membership();
    if (membership != null && membership.parent() != null) {
      membership =
          new EntityTransition.Membership(
              resolveOrKeep(membership.parent(), resolved), membership.position());
    }
    return new EntityTransition(
        entry.archetype(),
        membership,
        entry.title(),
        entry.description(),
        entry.status(),
        entry.ticketType(),
        entry.impetus(),
        entry.assignee(),
        resolveOrKeep(entry.supersededBy(), resolved),
        entry.repositoryId(),
        entry.implementedAt(),
        resolveOrKeep(entry.dependsOn(), resolved),
        entry.acceptanceCriteria());
  }

  private String resolveOrKeep(String named, Map<String, String> resolved) {
    if (named == null || named.isBlank()) {
      return named;
    }
    return resolved.computeIfAbsent(named, this::entityId);
  }

  /**
   * An entity named either way, as the UUID a writer compares: a qualified id resolved, and a value
   * naming nothing handed on unchanged, for the writer's own refusal about it.
   */
  private String entityId(String named) {
    if (named == null) {
      return null;
    }
    try {
      return ids.resolve(named).id;
    } catch (NotFoundException unknown) {
      return named;
    }
  }

  /**
   * Refuses a bound agent the whole batch unless every project it touches is the token's.
   *
   * <p>The parents are collected as well as the keys because a membership edge is where a batch
   * reaches out of itself: an entry may hang a row it does own under a parent in somebody else's
   * project, which is a write to that project's tree whatever the map's keys say.
   */
  private void requireAgentProjects(
      SecurityIdentity identity, Map<String, EntityTransition> request) {
    if (request == null || request.isEmpty() || !EntitiesAgentAccess.bound(identity)) {
      return;
    }
    Set<String> named = new LinkedHashSet<>(request.keySet());
    for (EntityTransition entry : request.values()) {
      if (entry != null && entry.parent() != null) {
        named.add(entry.parent());
      }
    }
    for (TransitionedEntity resolved : catalog.byIds(named).values()) {
      EntitiesAgentAccess.requireProject(identity, resolved.projectId());
    }
  }

  // --- the delete ------------------------------------------------------------------------------

  /**
   * Deletes {@code row} and its subtree (qits-970), by the rules the per-archetype deletes kept: a
   * root's delete — an epic's, a ticket's — is {@code qits:admin} alone, as the deleted {@code DELETE
   * /epics/{id}} and {@code DELETE /tickets/{id}} were (deleting is on no agent's surface: an agent
   * that could delete what it disagrees with could erase the record of its own mistake); a feature's
   * and a task's admit the agent bound to its project, as {@code remove_feature}/{@code remove_task}
   * do over MCP. No door has ever deleted a campaign: it is dropped, through its status. The project
   * is read before the write — afterwards there is no row to read it from.
   */
  public void delete(SecurityIdentity identity, WorkEntity row) {
    Archetype archetype = row.archetype;
    if (archetype == Archetype.CAMPAIGN) {
      throw new ConflictException(
          "A campaign is not deleted: drop it — POST /projects/api/work/{qualifiedId}/status"
              + " {\"target\":\"DROPPED\"}.");
    }
    // qits:admin-agent is admitted too (qits-628 follow-up); remove it here if this door must
    // stay human-only.
    if ((archetype == Archetype.EPIC || archetype == Archetype.TICKET)
        && !identity.hasRole(AgentAccess.ADMIN_ROLE)
        && !identity.hasRole(AgentAccess.ADMIN_AGENT_ROLE)) {
      throw new ForbiddenException(
          "Deleting "
              + (archetype == Archetype.EPIC ? "an epic" : "a ticket")
              + " is qits:admin alone: deleting is on no agent's surface.");
    }
    EntitiesAgentAccess.requireProject(identity, row.projectId);
    entities.delete(archetype, row.id, EntitiesPrincipal.changedBy(identity));
    publisher.fire(row.projectId, ProjectChangeHint.Topic.of(archetype));
  }

  // --- the children ----------------------------------------------------------------------------

  /** The archetype whose rows hang under {@code parent}: an epic's features, a feature's tasks. */
  private static Archetype childKindOf(Archetype parent) {
    for (Archetype candidate : Archetype.values()) {
      if (WorkEntityService.parentKindOf(candidate) == parent) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * The direct children of {@code parent} in membership order, in the merged shape and qualified —
   * an epic's features, a feature's tasks (qits-970). A kind nothing hangs under has none: the empty
   * list is the true answer, not a refusal.
   */
  public List<TransitionedEntity> children(WorkEntity parent) {
    Archetype kind = childKindOf(parent.archetype);
    if (kind == null) {
      return List.of();
    }
    List<String> childIds =
        entities.listChildren(kind, parent.id).stream().map(child -> child.entity().id).toList();
    Map<String, TransitionedEntity> found = catalog.byIds(childIds);
    return qualifiedIds.qualifyEntities(
        childIds.stream().map(found::get).filter(Objects::nonNull).toList());
  }

  /**
   * A new child of {@code parent}: the create of its child kind with the parent the path names
   * (qits-970). The body is the child archetype's create schema minus {@code archetype}, {@code
   * parent} and {@code project}, which the path decides — so every rule is {@link #create}'s, and a
   * dependency takes a qualified id there too. A kind nothing hangs under is a 409.
   */
  public TransitionedEntity createChild(SecurityIdentity identity, WorkEntity parent, JsonNode body) {
    Archetype kind = childKindOf(parent.archetype);
    if (kind == null) {
      throw new ConflictException(
          "A "
              + WorkEntityService.nounOf(parent.archetype).toLowerCase(Locale.ROOT)
              + " has no children: only an epic (its features) and a feature (its tasks) do.");
    }
    if (body == null || !body.isObject()) {
      throw new BadRequestException("a child must be a JSON object");
    }
    List<String> named = new ArrayList<>();
    for (String decided : List.of("archetype", EntitySchemas.PARENT, EntitySchemas.PROJECT)) {
      if (body.has(decided)) {
        named.add(decided);
      }
    }
    if (!named.isEmpty()) {
      throw new BadRequestException(
          String.join(", ", named)
              + (named.size() == 1 ? " is" : " are")
              + " decided by the path: the parent is the entity it names, and the child's"
              + " archetype is "
              + kind);
    }
    com.fasterxml.jackson.databind.node.ObjectNode create =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    create.put("archetype", kind.name());
    create.put(EntitySchemas.PARENT, parent.id);
    create.setAll((com.fasterxml.jackson.databind.node.ObjectNode) body);
    return create(identity, create);
  }

  // --- the history -----------------------------------------------------------------------------

  /**
   * The change history behind {@code named}, newest first (qits-970). A root — an epic, a ticket, a
   * campaign — answers its whole subtree, every row stamped with its id as the subtree key (an epic's
   * features, tasks and every thread under it), which is what {@code GET /epics/{id}/audit} answers;
   * a feature or a task answers its own rows. The log outlives the rows, so a UUID that names no
   * entity any more is still read as a subtree key, exactly as the epic route reads it; a qualified
   * id naming nothing is the resolver's 404, since nothing is left to resolve it through.
   */
  public List<AuditEntryDto> audit(String named) {
    WorkEntity row;
    try {
      row = ids.resolve(named);
    } catch (NotFoundException unknown) {
      if (named == null
          || named.isBlank()
          || eu.wohlben.qits.projects.entitieshost.CommitSubjectEntities.parse(named).isPresent()) {
        throw unknown;
      }
      return auditEntries(audits.listForEpic(named.trim()));
    }
    if (Archetypes.mayBeRoot(row.archetype)) {
      return auditEntries(audits.listForEpic(row.id));
    }
    return auditEntries(
        audits.listForEntity(AuditEntityType.valueOf(row.archetype.name()), row.id));
  }

  private List<AuditEntryDto> auditEntries(List<AuditEntry> entries) {
    return entries.stream().map(auditMapper::toDto).toList();
  }

  // --- the project's listing -------------------------------------------------------------------

  /**
   * A project's entities, in tree order, optionally narrowed to one archetype, one status and the
   * direct children of one parent (UUID or qualified id). The project is its id or its slug.
   */
  public List<EntitySummary> list(
      String projectIdOrSlug, String archetype, String status, String parent) {
    Archetype kind = blank(archetype) ? null : listArchetype(archetype);
    String word = blank(status) ? null : listStatus(status);
    String project = ids.resolveProject(projectIdOrSlug).id;
    String parentId = blank(parent) ? null : ids.resolve(parent).id;
    List<TransitionedEntity> found =
        catalog.listByProjectWithoutDescription(project).stream()
            .filter(entity -> kind == null || entity.archetype() == kind)
            .filter(entity -> word == null || word.equals(entity.status()))
            .filter(entity -> parentId == null || parentId.equals(entity.parent()))
            .toList();
    return qualifiedIds.qualifyEntities(found).stream().map(EntitySummary::of).toList();
  }

  private static Archetype listArchetype(String word) {
    try {
      return Archetype.valueOf(word.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown archetype: " + word);
    }
  }

  private static String listStatus(String word) {
    try {
      return EntityStatus.valueOf(word.trim().toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown status: " + word);
    }
  }

  private static boolean blank(String value) {
    return Objects.requireNonNullElse(value, "").isBlank();
  }

  // --- the registry ----------------------------------------------------------------------------

  /** The archetype registry as it stands, every move naming its gates. */
  public ArchetypeRegistryDocument registry() {
    return ArchetypeRegistryDocument.describe(gates.stream().toList());
  }

  /** The JSON Schema of one write door's payload for one archetype, or a 404. */
  public Map<String, Object> schema(String archetype, String door) {
    Archetype kind;
    try {
      kind = Archetype.valueOf(archetype.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new NotFoundException("No such archetype: " + archetype);
    }
    EntitySchemas.Door named =
        EntitySchemas.Door.parse(door)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "No schema for door " + door + ": it is create, update or transition"));
    return EntitySchemas.of(kind, named);
  }
}
