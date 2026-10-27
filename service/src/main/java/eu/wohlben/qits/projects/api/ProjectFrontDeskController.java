package eu.wohlben.qits.projects.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.DaemonApiClient;
import eu.wohlben.qits.projects.agenthost.FrontDeskPrompter;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>Talk to a project's front desk</b> (qits-1572): hand its kept desk command a prompt, and read
 * the last answer it gave. Both doors proxy to the desk's daemon down the project's reverse tunnel
 * ({@link DaemonApiClient}, the hop {@code AgentCapabilityRelay} makes), to the daemon's loopback
 * routes {@code POST front-desk/prompt} and {@code GET front-desk/last-answer} under its proxied base
 * path. The daemon's answer — 200, 400, 404, 409 or 429, each with its body — passes through
 * verbatim; this class adds only what the daemon cannot say (the reading of the daemon's answer is
 * {@link FrontDeskPrompter}'s, shared with the desk trigger sweep since epic qits-1604):
 *
 * <ul>
 *   <li><b>400</b> for a blank {@code text}, before anything is proxied;
 *   <li><b>404</b> for a project this service does not know;
 *   <li><b>503</b> when no daemon holds the project's control socket, the hop fails, or the daemon
 *       answers something outside its contract (a 5xx — its agent surface still booting — or an
 *       empty body);
 *   <li><b>409</b> for a daemon <b>too old to serve the route</b>. Such a daemon's router answers
 *       404 {@code {"message":"No such endpoint"}}, which carries no {@code reason}; the daemon's own
 *       404 ("no kept command", last-answer only) always does, and that is how the two are told
 *       apart. 409 rather than 503 because nothing is down and retrying will not help: the desk is
 *       running a build that predates the doors, and it is the spec roll onto the pinned daemon that
 *       resolves it — the same kind of answer as the daemon's own 409 for a desk with no kept
 *       command.
 * </ul>
 *
 * <p>A person's doors, and an admin agent's: the class admits {@code qits:admin}, {@code
 * qits:admin-agent} and, since epic qits-1621, {@code qits:desk-admin} — bound to its own project
 * ({@link AgentAccess#isDesk}/{@link AgentAccess#requireDeskProject}, in {@link #unknownProject}:
 * the project is resolved first, so an unknown id stays a 404 before another project's becomes a
 * 403). A plain {@code qits:agent} — and the delivery desk's own {@code qits:desk} token without
 * {@code qits:desk-admin} — is refused, the read included: the last answer is the desk's
 * conversation, and the desk prompting itself through this door would be a loop nobody asked for.
 * {@code AgentReadAccessTest} names both routes as admin-only on purpose.
 */
@Path("/projects/{projectId}/front-desk")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin"})
public class ProjectFrontDeskController {

  /** The daemon's routes, under its proxied base path (see {@code ContainerProxyPath}). */
  static final String PROMPT_ROUTE = FrontDeskPrompter.PROMPT_ROUTE;

  static final String LAST_ANSWER_ROUTE = "front-desk/last-answer";

  @Inject ProjectService projects;

  @Inject DaemonApiClient daemon;

  @Inject SecurityIdentity identity;

  /** What the daemon's answer means — shared with the desk trigger sweep (epic qits-1604). */
  @Inject FrontDeskPrompter prompter;

  @Inject ObjectMapper json;

  /**
   * How long one proxied call may take. The daemon only queues or reads — it never waits on a turn
   * — so this bounds a loopback hop, not an agent.
   */
  @ConfigProperty(name = "qits.projects.front-desk.relay-timeout-ms", defaultValue = "10000")
  long timeoutMs;

  /** What a person types to the desk. */
  @Schema(name = "FrontDeskPromptRequest")
  public record PromptRequest(
      @Schema(description = "The prompt; must not be blank", required = true) String text) {}

  /** The daemon's answer to a prompt, passed through verbatim. */
  @Schema(name = "FrontDeskPromptResponse")
  public record PromptResponse(
      @Schema(description = "The kept desk command the prompt was given to") String commandId,
      @Schema(description = "That command's state, as the daemon reports it") String state,
      @Schema(description = "Whether the prompt was handed to the session at once") boolean delivered,
      @Schema(description = "Whether it waits behind a running turn instead") boolean queued,
      @Schema(description = "Its place in the queue when queued, else null", nullable = true)
          Integer position) {}

  /** The desk's last answer, passed through verbatim. */
  @Schema(name = "FrontDeskLastAnswer")
  public record LastAnswerResponse(
      @Schema(description = "The kept desk command") String commandId,
      @Schema(description = "Its agent session") String sessionId,
      @Schema(description = "That command's state, as the daemon reports it") String state,
      @Schema(description = "The last answer's text; null before the first", nullable = true)
          String text,
      @Schema(description = "When it was given; null before the first", nullable = true)
          String at) {}

  /** Why the door, or the daemon behind it, refused. */
  @Schema(name = "FrontDeskRefusal")
  public record Refusal(String reason) {}

  @POST
  @Path("/prompt")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "promptFrontDesk",
      summary = "Hand the project's front desk a prompt: delivered at once, or queued")
  @APIResponse(
      responseCode = "200",
      description = "Delivered or queued",
      content = @Content(schema = @Schema(implementation = PromptResponse.class)))
  @APIResponse(
      responseCode = "400",
      description = "Blank text",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such project",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "409",
      description = "No kept desk command is running, or the desk's daemon predates this door",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "429",
      description = "The desk's prompt queue is full",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "503",
      description = "No daemon is connected for the project, or it could not be reached",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  public Response prompt(@PathParam("projectId") String projectId, PromptRequest request) {
    if (request == null || request.text() == null || request.text().isBlank()) {
      return refusal(400, "A prompt needs text.");
    }
    Response unknown = unknownProject(projectId);
    if (unknown != null) {
      return unknown;
    }
    String body;
    try {
      body = json.writeValueAsString(Map.of("text", request.text()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    try {
      return respond(
          prompter.classify(
              projectId,
              PROMPT_ROUTE,
              false,
              daemon.post(projectId, PROMPT_ROUTE, body, timeoutMs)));
    } catch (DaemonApiClient.Unreachable e) {
      return respond(prompter.unreachable(projectId, e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return refusal(503, "Interrupted while asking the project's front desk.");
    }
  }

  @GET
  @Path("/last-answer")
  @Operation(
      operationId = "getFrontDeskLastAnswer",
      summary = "The last answer the project's front desk gave")
  @APIResponse(
      responseCode = "200",
      description = "The kept desk command's last answer; text and at are null before the first",
      content = @Content(schema = @Schema(implementation = LastAnswerResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such project, or the desk keeps no command",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "409",
      description = "The desk's daemon predates this door",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "503",
      description = "No daemon is connected for the project, or it could not be reached",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  public Response lastAnswer(@PathParam("projectId") String projectId) {
    Response unknown = unknownProject(projectId);
    if (unknown != null) {
      return unknown;
    }
    try {
      return respond(
          prompter.classify(
              projectId,
              LAST_ANSWER_ROUTE,
              true,
              daemon.get(projectId, LAST_ANSWER_ROUTE, timeoutMs)));
    } catch (DaemonApiClient.Unreachable e) {
      return respond(prompter.unreachable(projectId, e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return refusal(503, "Interrupted while asking the project's front desk.");
    }
  }

  /**
   * 404 for a project this service does not know, else null. Also binds the delivery desk ({@code
   * qits:desk}, carried by {@code qits:desk-admin} too, epic qits-1621) to its own project: the
   * project is resolved first, so an unknown id stays a 404, and only then is another project's a
   * 403. Every other caller passes untouched.
   */
  private Response unknownProject(String projectId) {
    try {
      projects.get(projectId);
    } catch (NotFoundException e) {
      return refusal(404, "No project " + projectId + ".");
    }
    if (AgentAccess.isDesk(identity)) {
      AgentAccess.requireDeskProject(identity, projectId);
    }
    return null;
  }

  /** A door's reply as a response: the daemon's body verbatim, or a {@link Refusal} of ours. */
  private static Response respond(FrontDeskPrompter.Reply reply) {
    if (reply.verbatim() != null) {
      return Response.status(reply.status())
          .entity(reply.verbatim())
          .type(MediaType.APPLICATION_JSON)
          .build();
    }
    return refusal(reply.status(), reply.reason());
  }

  private static Response refusal(int status, String reason) {
    return Response.status(status)
        .entity(new Refusal(reason))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
