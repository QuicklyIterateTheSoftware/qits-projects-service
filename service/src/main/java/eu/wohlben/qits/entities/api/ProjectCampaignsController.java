package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignSummaryDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.control.ProjectService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import java.util.List;

/**
 * A project's campaigns (qits-413): the listing, and the create a campaign is born through. {@code
 * projectId} is resolved against {@code domain} first, so a bad project is a clean 404.
 *
 * <p>Both admit {@code qits:agent}; the create is bound to the agent's own project ({@link
 * EntitiesAgentAccess}). Its hint redraws the {@code epics} topic, the channel the work desk
 * listens on.
 */
@Path("/projects/{projectId}/campaigns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class ProjectCampaignsController {

  @Inject CampaignService campaigns;

  @Inject CampaignViews views;

  @Inject ProjectService projectService;

  @Inject ProjectChangePublisher publisher;

  @Inject SecurityIdentity identity;

  public record CampaignsResponse(List<CampaignSummaryDto> campaigns) {}

  /** The project's campaigns, oldest first, each with whether it has started and how many members. */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @Operation(operationId = "listProjectCampaigns")
  public CampaignsResponse list(@PathParam("projectId") String projectId) {
    projectService.get(projectId);
    return new CampaignsResponse(views.summaries(campaigns.listByProject(projectId)));
  }

  public record CreateCampaignRequest(@NotBlank String title, String description) {}

  public record CreatedCampaignResponse(CampaignDto campaign) {}

  /** A new campaign, REPORTED and empty. */
  @POST
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CreatedCampaignResponse create(
      @PathParam("projectId") String projectId, @Valid CreateCampaignRequest request) {
    EntitiesAgentAccess.requireProject(identity, projectId);
    projectService.get(projectId);
    WorkEntity created =
        campaigns.create(
            projectId,
            request.title(),
            request.description(),
            EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return new CreatedCampaignResponse(views.campaign(campaigns.get(created.id)));
  }
}
