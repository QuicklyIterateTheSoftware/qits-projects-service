package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.AuditService;
import eu.wohlben.qits.entities.control.EpicService;
import eu.wohlben.qits.entities.control.FeatureService;
import eu.wohlben.qits.entities.dto.AuditEntryDto;
import eu.wohlben.qits.entities.dto.EpicDto;
import eu.wohlben.qits.entities.dto.FeatureDto;
import eu.wohlben.qits.entities.mapper.AuditEntryMapper;
import eu.wohlben.qits.entities.mapper.WorkEntityMapper;
import eu.wohlben.qits.projects.refinementhost.EpicResolutions;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** A single epic (the spine), its feature collection, and its audit subtree. */
@Path("/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class EpicController {

  @Inject EpicService epicService;

  @Inject EpicResolutions epicResolutions;

  @Inject FeatureService featureService;

  @Inject AuditService auditService;

  /** One mapper where there were four — this route answers an epic shape and a feature shape. */
  @Inject WorkEntityMapper workEntityMapper;

  @Inject AuditEntryMapper auditEntryMapper;

  @Inject SecurityIdentity identity;

  @Inject EpicsTopicHints hints;

  /**
   * Which live workspaces are on this epic — derived per read; see {@code DispatchedWorkspaces}.
   * Only the detail read carries them: the writes below answer the row they changed, and an edit is
   * not the question "who is working on this".
   */
  @Inject eu.wohlben.qits.projects.api.DispatchedWorkspaces dispatchedWorkspaces;

  /**
   * The qualified id {@code <project-slug>-<number>} every answer here carries. One batched slug
   * lookup per listing; see {@link eu.wohlben.qits.projects.api.QualifiedEntityIds}, and
   * {@code DispatchedWorkspaces} for why the crossing into {@code domain} lives in that package.
   */
  @Inject eu.wohlben.qits.projects.api.QualifiedEntityIds qualifiedIds;

  // --- Epic ---

  public record GetEpicRequest() {
    public record Response(EpicDto epic) {}
  }

  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}")
  public GetEpicRequest.Response get(@PathParam("id") String id) {
    return new GetEpicRequest.Response(
        qualifiedIds.qualify(
            dispatchedWorkspaces.decorate(workEntityMapper.toEpicDto(epicService.get(id)))));
  }

  public record UpdateEpicRequest(@NotBlank String title, String description) {
    public record Response(EpicDto epic) {}
  }

  /**
   * Editing an epic's words takes {@code qits:agent}, bound to the agent's own project: the {@code
   * update_epic} MCP tool already performs this write for an agent. The project is resolved from the
   * epic before the write, so an id naming nothing is the 404 it always was — see {@link
   * EntitiesAgentAccess}.
   */
  @PUT
  @Path("/{id}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public UpdateEpicRequest.Response update(
      @PathParam("id") String id, @Valid UpdateEpicRequest request) {
    EntitiesAgentAccess.requireProject(identity, hints.projectOfEpic(id));
    var epic =
        epicService.update(
            id, request.title(), request.description(), EntitiesPrincipal.changedBy(identity));
    hints.fire(epic.projectId);
    return new UpdateEpicRequest.Response(qualifiedIds.qualify(workEntityMapper.toEpicDto(epic)));
  }

  /**
   * A lifecycle move. {@code target} is the status name — {@code IMPLEMENTATION} (the scope
   * freeze), {@code IMPLEMENTED} (shipped: stamps every feature and task still unimplemented),
   * {@code SUPERSEDED} or {@code ABANDONED}. A move the lifecycle does not allow, and a target
   * naming no status, both answer 409 with a message.
   *
   * <p>It goes through {@link EpicResolutions} rather than straight to {@code EpicService}, because
   * a move that resolves the epic has to tear its refinement down first — see that class for the
   * order and for what the browser-side version of it used to leak.
   */
  public record TransitionEpicRequest(String target) {
    /** The epic in its new status, plus the successor draft a supersede spawned (null otherwise). */
    public record Response(EpicDto epic, EpicDto successor) {}
  }

  @POST
  @Path("/{id}/transition")
  public TransitionEpicRequest.Response transition(
      @PathParam("id") String id, @Valid TransitionEpicRequest request) {
    var result =
        epicResolutions.transition(id, request.target(), EntitiesPrincipal.changedBy(identity));
    // A supersede spawns a second epic in the same project, so one hint still covers both rows.
    hints.fire(result.epic().projectId);
    return new TransitionEpicRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toEpicDto(result.epic())),
        result.successor() == null
            ? null
            : qualifiedIds.qualify(workEntityMapper.toEpicDto(result.successor())));
  }

  public record DeleteEpicRequest() {
    public record Response(boolean success) {}
  }

  @DELETE
  @Path("/{id}")
  public DeleteEpicRequest.Response delete(@PathParam("id") String id) {
    // Resolved before the delete — afterwards there is no row to walk up from.
    String projectId = hints.projectOfEpic(id);
    epicService.delete(id, EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
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
    epicService.get(epicId); // 404 if the epic does not exist
    // Mapped first, then qualified in one call: the project-slug lookup is asked once about the
    // whole list, never once per feature.
    var entries =
        qualifiedIds
            .qualifyFeatures(
                featureService.listByEpic(epicId).stream()
                    .map(f -> workEntityMapper.toFeatureDto(f.entity(), f.parentId()))
                    .toList())
            .stream()
            .map(ListFeaturesRequest.Response.Entry::new)
            .toList();
    return new ListFeaturesRequest.Response(entries);
  }

  public record CreateFeatureRequest(
      @NotBlank String title, String description, String dependsOnFeatureId) {
    public record Response(FeatureDto feature) {}
  }

  /**
   * Adding a feature takes {@code qits:agent}, bound to the agent's own project: the {@code
   * add_feature} MCP tool already performs this write for an agent. The epic's project is resolved
   * before the write and reused for the hint — see {@link EntitiesAgentAccess}.
   */
  @POST
  @Path("/{epicId}/features")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CreateFeatureRequest.Response createFeature(
      @PathParam("epicId") String epicId, @Valid CreateFeatureRequest request) {
    String projectId = hints.projectOfEpic(epicId); // 404 if the epic does not exist
    EntitiesAgentAccess.requireProject(identity, projectId);
    var feature =
        featureService.create(
            epicId,
            request.title(),
            request.description(),
            request.dependsOnFeatureId(),
            EntitiesPrincipal.changedBy(identity));
    hints.fire(projectId);
    return new CreateFeatureRequest.Response(
        qualifiedIds.qualify(workEntityMapper.toFeatureDto(feature.entity(), feature.parentId())));
  }

  // --- Audit subtree ---

  public record EpicAuditRequest() {
    public record Response(List<AuditEntryDto> entries) {}
  }

  /**
   * Full change history for the epic subtree, newest first. Queried by the {@code epicId} column
   * stamped on every audit row, so it includes rows for features/tasks already deleted and remains
   * readable after the epic itself is deleted (the audit log is the git replacement — it must
   * outlive the rows). Deliberately does NOT require the epic to still exist.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{id}/audit")
  public EpicAuditRequest.Response audit(@PathParam("id") String id) {
    var entries = auditService.listForEpic(id).stream().map(auditEntryMapper::toDto).toList();
    return new EpicAuditRequest.Response(entries);
  }
}
