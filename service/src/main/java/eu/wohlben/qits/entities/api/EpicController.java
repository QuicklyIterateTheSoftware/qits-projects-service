package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.AuditService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.dto.AuditEntryDto;
import eu.wohlben.qits.entities.dto.EpicDto;
import eu.wohlben.qits.entities.dto.FeatureDto;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.mapper.AuditEntryMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * A single epic (the spine), its feature collection, and its audit subtree.
 *
 * <p>A thin resource over {@link EntityRoutes}, which is the one implementation behind every
 * per-archetype route (qits-399): this class holds what the wire names — the paths, the request
 * records, the {@code {"epic": …}} envelopes and the role lists — and each body is one call with the
 * archetype handed over as a view. The epic's words are edited through {@code POST
 * /entities/transition} (or the {@code update_epic} tool); the per-epic {@code PUT} went in qits-399.
 */
@Path("/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class EpicController {

  @Inject EntityRoutes routes;

  @Inject AuditService auditService;

  @Inject AuditEntryMapper auditEntryMapper;

  @Inject SecurityIdentity identity;

  // --- Epic ---

  public record GetEpicRequest() {
    public record Response(EpicDto epic) {}
  }

  /** The detail read, and the one place a single epic carries its workspaces. */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetEpicRequest.Response get(@PathParam("id") String id) {
    return new GetEpicRequest.Response(routes.get(routes.epics(), id));
  }

  /**
   * A lifecycle move. {@code target} is a status name of the one entity lifecycle — {@code
   * REFINED} (the scope freeze), {@code IMPLEMENTED} (shipped: stamps every feature and task still
   * unimplemented), {@code VERIFIED}, {@code DONE}, {@code DROPPED}, or back along the walk ({@code
   * REPORTED} reopens a frozen scope) — or {@code SUPERSEDED}, which is not a status but the
   * supersede operation: the epic lands {@code DROPPED} pointing at the successor draft it spawned
   * (see {@code WorkEntityService.SUPERSEDE}). A move the lifecycle does not allow, and a target
   * naming no status, both answer 409 with a message.
   *
   * <p>{@code qits:admin} alone, so it binds no agent: moving a plan through its lifecycle is a
   * person's decision on this surface.
   */
  public record TransitionEpicRequest(String target) {
    /** The epic in its new status, plus the successor draft a supersede spawned (null otherwise). */
    public record Response(EpicDto epic, EpicDto successor) {}
  }

  @POST
  @Path("/{id}/transition")
  public TransitionEpicRequest.Response transition(
      @PathParam("id") String id, @Valid TransitionEpicRequest request) {
    var moved = routes.transition(routes.epics(), id, request.target(), false, identity);
    return new TransitionEpicRequest.Response(moved.entity(), moved.successor());
  }

  public record DeleteEpicRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteEpicRequest.Response delete(@PathParam("id") String id) {
    routes.delete(routes.epics(), id, false, identity);
    return new DeleteEpicRequest.Response(true);
  }

  // --- Features under an epic ---

  public record ListFeaturesRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(FeatureDto feature) {}
    }
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{epicId}/features")
  public ListFeaturesRequest.Response listFeatures(@PathParam("epicId") String epicId) {
    return new ListFeaturesRequest.Response(
        routes.listChildren(routes.features(), Archetype.EPIC, epicId).stream()
            .map(ListFeaturesRequest.Response.Entry::new)
            .toList());
  }

  public record CreateFeatureRequest(
      @NotBlank String title, String description, String dependsOnFeatureId) {
    public record Response(FeatureDto feature) {}
  }

  /**
   * Adding a feature takes {@code qits:agent}, bound to the agent's own project: the {@code
   * add_feature} MCP tool already performs this write for an agent. The epic's project is resolved
   * before the write — see {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{epicId}/features")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CreateFeatureRequest.Response createFeature(
      @PathParam("epicId") String epicId, @Valid CreateFeatureRequest request) {
    return new CreateFeatureRequest.Response(
        routes.createChild(
            routes.features(),
            Archetype.EPIC,
            epicId,
            EntityWrite.feature(
                request.title(), request.description(), request.dependsOnFeatureId()),
            identity));
  }

  // --- Audit subtree ---

  public record EpicAuditRequest() {
    public record Response(List<AuditEntryDto> entries) {}
  }

  /**
   * Full change history for the epic subtree, newest first. Queried by the {@code epicId} column
   * stamped on every audit row, so it includes rows for features/tasks already deleted and remains
   * readable after the epic itself is deleted (the audit log is the git replacement — it must
   * outlive the rows). Deliberately does NOT require the epic to still exist. The column is the
   * subtree key, so a ticket's id answers the ticket's history here too.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}/audit")
  public EpicAuditRequest.Response audit(@PathParam("id") String id) {
    var entries = auditService.listForEpic(id).stream().map(auditEntryMapper::toDto).toList();
    return new EpicAuditRequest.Response(entries);
  }
}
