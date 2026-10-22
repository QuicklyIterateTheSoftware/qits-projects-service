package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesAgentAccess;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A project's refinements, as a list — the find-without-create read (the refining page matches its
 * epic here before offering to start one) and the activity bar's "who needs me next" row.
 *
 * <p><b>The light projection, deliberately.</b> Each row carries its live halves (runtime status,
 * agent activity, daemon presence) but no git drift — ahead/behind/conflicts cost a mirror refresh
 * and a merge preview per row, which a list that redraws on every agent-activity hint must not pay.
 * The single-row read is where the drift lives.
 *
 * <p><b>A bearer bound to one entity sees only that entity's row</b> (qits-1482): a refinement
 * room's own token carries an {@code entity} claim, and the listing is filtered to it rather than
 * refused — a list answers what the caller may see, an empty one is an honest answer for a room
 * not in this project, and a 403 here would break a client whose only question is "is my room
 * listed". Every other caller sees the whole list, as before.
 */
@Path("/projects/{projectId}/refinements")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class ProjectRefinementsController {

  @Inject RefinementService refinements;

  @Inject SecurityIdentity identity;

  public record ListResponse(List<RefinementDto> refinements) {}

  @GET
  public ListResponse list(@PathParam("projectId") String projectId) {
    return new ListResponse(
        refinements.listByProject(projectId).stream()
            .filter(view -> EntitiesAgentAccess.seesEntity(identity, view.refinement().entityId))
            .map(RefinementDto::of)
            .toList());
  }
}
