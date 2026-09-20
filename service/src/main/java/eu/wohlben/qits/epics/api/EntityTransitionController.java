package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.control.EntityTransition;
import eu.wohlben.qits.epics.control.EntityTransitionService;
import eu.wohlben.qits.epics.control.TransitionedEntity;
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
 * <p><b>{@code qits:admin} at class level and nothing else.</b> A transition is a write, and the
 * user's ruling is that an agent keeps every read and gains no write; the five writes an agent does
 * reach are the release-request ones, each bound to the agent's own work. There is nothing here to
 * bind a re-shaping of a project's whole plan to.
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

  @Inject SecurityIdentity identity;

  @Inject EpicChangeHints epicHints;

  @Inject TicketChangeHints ticketHints;

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
   * {@link EpicsExceptionMapper} path. An id that names nothing — in the map or as a parent — is one
   * of those violations and deliberately not a 404: a caller with three wrong ids should be told
   * about three wrong ids once.
   */
  @POST
  @Path("/transition")
  public Map<String, TransitionedEntity> transition(Map<String, EntityTransition> request) {
    Map<String, TransitionedEntity> written =
        transitions.transition(request, EpicsPrincipal.changedBy(identity));

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
}
