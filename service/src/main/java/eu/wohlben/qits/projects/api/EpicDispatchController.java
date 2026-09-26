package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.entities.control.EpicService;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.refinementhost.EpicResolutions;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * "Start implementation" on an epic — <b>a thin delegate onto the one dispatch path</b> ({@link
 * EntityDispatch}, qits-394) in {@link DispatchMode#PHASE}, matching this door's single-shot
 * behaviour: one phase runs and nothing continues on the next transition.
 *
 * <p><b>Retiring.</b> The deployed SPA still calls {@code POST /epics/{id}/dispatch-agent} from its
 * "Start" button, so the route keeps answering in this release, with its old response shape ({@link
 * EpicAgentDispatchDto}). It is removed — with its DTO and its tests — in a later release, once the
 * SPA calls {@code POST /entities/{id}/dispatch} instead.
 *
 * <p><b>The one thing it keeps of its own is the freeze the button has always meant.</b> The
 * deployed button is offered on a REPORTED epic and promises implementation, so a REPORTED epic is
 * first moved to REFINED — through {@link EpicResolutions}, the only way a door moves an epic —
 * and the phase REFINED starts is implement. Delegating a REPORTED epic straight through would start
 * the <em>refine</em> phase behind a button labelled "Start implementation". The move happens only
 * once the preconditions that need no attempt hold (an epic, a workspaces context, a wrapper), and it does not
 * call {@link PhaseAdvance}: the dispatch that follows is what starts the phase. Every other status
 * goes straight through, so an epic already REFINED is re-dispatched as it stands, and VERIFIED,
 * DONE and DROPPED are the one path's 409.
 */
@Path("/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EpicDispatchController {

  @Inject EpicService epics;

  @Inject EpicResolutions resolutions;

  @Inject EntityDispatch dispatch;

  @Inject SecurityIdentity identity;

  @Inject ProjectChangePublisher publisher;

  @Inject EntityWorkspaces workspaces;

  /** Read only to refuse before the freeze when nothing could be dispatched afterwards. */
  @Inject Instance<eu.wohlben.qits.projects.control.WorkspaceAgentDispatch> port;

  /** No body: everything the dispatch needs is derived from the epic it is about. */
  public record DispatchAgentRequest() {
    public record Response(EpicAgentDispatchDto dispatch) {}
  }

  @POST
  @Path("/{id}/dispatch-agent")
  public DispatchAgentRequest.Response dispatchAgent(@PathParam("id") String id) {
    WorkEntity epic = epics.get(id); // 404 if no EPIC has this id
    String changedBy = EntitiesPrincipal.changedBy(identity);
    if (EntityStatus.REPORTED.name().equals(epic.status)) {
      if (port.isUnsatisfied()) {
        // The freeze is a status change nothing asked for if no agent can follow it.
        throw new DomainException(
            503,
            "No workspaces context is configured, so no agent can be dispatched onto epic "
                + id
                + ".");
      }
      workspaces.require(epic); // the no-wrapper 409, before a status change nothing could follow
      epic = resolutions.transition(id, EntityStatus.REFINED.name(), changedBy).epic();
      publisher.fire(epic.projectId, ProjectChangeHint.Topic.EPICS);
    }
    EntityDispatch.Outcome outcome = dispatch.dispatch(epic, DispatchMode.PHASE, changedBy);
    return new DispatchAgentRequest.Response(
        EpicAgentDispatchDto.of(outcome.made(), outcome.repositoryId(), outcome.branch()));
  }
}
