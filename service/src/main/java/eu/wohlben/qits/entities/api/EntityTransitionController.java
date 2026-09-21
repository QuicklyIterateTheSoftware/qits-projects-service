package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntityTransition;
import eu.wohlben.qits.entities.control.EntityTransitionService;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>The write surface of the merged model: {@code POST /projects/api/entities/transition}.</b>
 *
 * <p>One request, a map of entity id to the full state that entity is to have afterwards. It is
 * validated as one post-state, applied in one transaction and announced once — see {@code
 * EntityTransitionService} for every rule behind that sentence, including why a feature becoming an
 * epic while its tasks are rescoped has no legal expression as a sequence of single-entity writes.
 *
 * <h2>Why {@code /entities} and not under an epic or a ticket</h2>
 *
 * <p><b>The unified entity is the noun.</b> The four archetypes are one table discriminated by a
 * column, and the whole subject of this endpoint is a row changing which of them it is — so putting
 * it under {@code /epics} or {@code /tickets} would file the operation under one of the two ends it
 * moves between, and a reader looking for the write surface of the merged model would have to know
 * the answer before finding it. {@code /entities} is where that reader looks, and it is the segment
 * the rest of the merged model's surface grows under as it arrives.
 *
 * <p>It is under {@code /projects} like every other machine surface here, so {@code
 * quarkus.quinoa.ignored-path-prefixes} needs no line: that key already carries the one prefix, and
 * the SPA fallback cannot swallow a path a real route answers.
 *
 * <p><b>{@code qits:admin} at class level; the transition itself also takes {@code qits:agent},
 * bound to the agent's own project.</b> The test for admitting an agent to a write on this surface
 * is whether the {@code repository} MCP server already exposes a tool performing it — and it does:
 * {@code transition_entities} on {@code EntityMcpTools} serves this very write to the very same
 * agent with no credential at all. Refusing it at the REST door while handing it over one package
 * away was an inconsistency, not a boundary, and this is the door that had it. What binds it is the
 * token's {@code project} claim, against every project the batch touches — see {@link
 * EntitiesAgentAccess} and {@link #transition} for the all-or-nothing resolution.
 *
 * <p>The grant changes nothing about what a transition <em>is</em>: it still does not run the epic's
 * or the ticket's adjacency rules, so it remains the one door a status may move sideways through,
 * for an admin and an agent alike.
 *
 * <p><b>The change hints are fired here, after the service returns</b>, never inside the write. That
 * write is a {@code WritePatience} body whose content re-runs on a retry, so an SSE hint in it would
 * be sent twice. Both topics go out per affected project because a batch may well have moved a
 * ticket and an epic tree at once, and a client subscribed to one of the two channels would
 * otherwise draw a stale board.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityTransitionController {

  @Inject EntityTransitionService transitions;

  /**
   * The read side of the transition, used here for one thing only: resolving the projects of the
   * entities a request names, before the write, so a bound agent can be refused a batch that reaches
   * outside its own project. One bulk read, never one per entry.
   */
  @Inject EntityCatalogService catalog;

  @Inject SecurityIdentity identity;

  @Inject EpicsTopicHints epicHints;

  @Inject TicketsTopicHints ticketHints;

  /**
   * The qualified id {@code <project-slug>-<number>} every answer here carries. One batched slug
   * lookup per listing; see {@link eu.wohlben.qits.projects.api.QualifiedEntityIds}, and
   * {@code DispatchedWorkspaces} for why the crossing into {@code domain} lives in that package.
   */
  @Inject eu.wohlben.qits.projects.api.QualifiedEntityIds qualifiedIds;

  /**
   * Applies the stated post-state and answers what was written.
   *
   * <p><b>The answer is keyed the way the request is</b> — a map of entity id to that entity's whole
   * post-state — so a caller can put its statement and the result side by side and read off exactly
   * what became of each entry. That symmetry is the reason it is not wrapped in an envelope object
   * the way the single-entity routes' responses are: those answer one named thing ({@code
   * {"ticket": …}}) and this answers the same collection it was handed.
   *
   * <p>A refused post-state is a <b>400 carrying every violation</b>, from all three validation
   * layers, joined with {@code "; "} through the module's ordinary {@code BadRequestException} →
   * {@link EntitiesExceptionMapper} path. An id that names nothing — in the map or as a parent — is one
   * of those violations and deliberately not a 404: a caller with three wrong ids should be told
   * about three wrong ids once.
   *
   * <p><b>A bound agent is judged on the whole batch, before any of it is written.</b> Every key of
   * the map, and every parent it names, is resolved in ONE {@link EntityCatalogService#byIds} read,
   * and a project outside the token's claim refuses the request entirely — all or nothing, because a
   * batch is one post-state and half of one is not a smaller version of it. <b>An id that resolves
   * to nothing is not a refusal here</b>: it is left to the write, which reports it beside every other
   * violation in the 400 described above — the documented contract for an id naming nothing, and a
   * 403 in its place would answer a different question than the one that was asked. The resolution
   * runs only for a caller the binding applies to, so an admin pays no extra query.
   */
  @POST
  @Path("/transition")
  @RolesAllowed({"qits:admin", "qits:agent"})
  public Map<String, TransitionedEntity> transition(Map<String, EntityTransition> request) {
    requireAgentProjects(request);
    Map<String, TransitionedEntity> written =
        transitions.transition(request, EntitiesPrincipal.changedBy(identity));

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
    return qualifiedIds.qualifyEntities(written);
  }

  /**
   * Refuses a bound agent the whole batch unless every project it touches is the token's.
   *
   * <p>The parents are collected as well as the keys because a membership edge is where a batch
   * reaches out of itself: an entry may hang a row it does own under a parent in somebody else's
   * project, which is a write to that project's tree whatever the map's keys say.
   */
  private void requireAgentProjects(Map<String, EntityTransition> request) {
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
}
