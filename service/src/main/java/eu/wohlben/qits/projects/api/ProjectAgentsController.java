package eu.wohlben.qits.projects.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.DaemonApiClient;
import eu.wohlben.qits.projects.agenthost.FrontDeskPrompter;
import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import eu.wohlben.qits.projects.control.NamedAgents;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.deskhost.NamedAgentHost;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.security.AgentAccess;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>Talk to a project's named agents</b> (qits-2154): the kept Claude sessions in its front desk
 * container, by name — {@code desk} (the front desk itself) and {@code ui-builder} today, every
 * project having every one ({@link NamedAgents}). List them, hand one a prompt, read its last
 * answer. The terminal has no door of its own: the listing carries each running agent's {@code
 * commandId}, which the existing proxy {@code /projects/container/{projectId}/terminal/commands/
 * {commandId}} serves.
 *
 * <ul>
 *   <li><b>{@code desk}</b> is the front desk's own paths exactly — {@code front-desk/prompt} with
 *       the brief pointer before the text, {@code front-desk/last-answer}, {@link
 *       FrontDeskPrompter}'s reading — plus one thing the {@code front-desk/*} doors do not do: the
 *       prompt <b>first ensures the desk</b> ({@link FrontDesks#ensure}, the agent-container ensure
 *       door's path: demand stamped, a stopped container started). A desk whose daemon is not
 *       connected yet still answers the desk door's 503; the container is on its way.
 *   <li><b>Any other name</b> is {@link NamedAgentHost}'s: its own row, token, surface and session;
 *       its prompt never carries the desk's brief pointer. A prompt while the container is stopped
 *       or starting is held and answered <b>202 {@code STARTING}</b>, delivered once the agent's
 *       session is asked for; a second one meanwhile is a 409.
 * </ul>
 *
 * <p>{@code ProjectFrontDeskController}'s roles exactly: {@code qits:admin}, {@code
 * qits:admin-agent} and {@code qits:desk-admin} bound to its own project — a plain {@code
 * qits:agent} and a desk holding only {@code qits:desk} are 403, the reads included (a named
 * agent's answers are its conversation, as the desk's are). An unknown project is a 404 before
 * another project's is a 403; an unknown name is a 404; blank text a 400 before anything moves.
 */
@Path("/projects/{projectId}/agents")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin"})
public class ProjectAgentsController {

  @Inject ProjectService projects;

  @Inject SecurityIdentity identity;

  @Inject NamedAgentHost agents;

  @Inject FrontDesks frontDesks;

  @Inject DaemonApiClient daemon;

  @Inject FrontDeskPrompter prompter;

  @Inject DevelopmentGuideService guide;

  @Inject ObjectMapper json;

  /** The front desk's own bound on one proxied call, the same key its doors read. */
  @ConfigProperty(name = "qits.projects.front-desk.relay-timeout-ms", defaultValue = "10000")
  long timeoutMs;

  /** One named agent, as the listing answers it. */
  @Schema(name = "NamedAgent")
  public record AgentView(
      @Schema(description = "The agent's name: desk, ui-builder", required = true) String name,
      @Schema(description = "The agent surface its session launches as", required = true)
          String surface,
      @Schema(
              description =
                  "STOPPED (no session, none asked for), STARTING (asked for, not running yet),"
                      + " IDLE (running, between turns), BUSY (running, in a turn) or FAILED (the"
                      + " daemon could not run it)",
              required = true,
              enumeration = {"STOPPED", "STARTING", "IDLE", "BUSY", "FAILED"})
          String state,
      @Schema(
              description = "Its harness session, which the next launch resumes; null before it ran",
              nullable = true)
          String sessionId,
      @Schema(
              description =
                  "The daemon's command running it now — the terminal is"
                      + " /projects/container/{projectId}/terminal/commands/{commandId}; null while"
                      + " none runs",
              nullable = true)
          String commandId) {

    static AgentView of(NamedAgentHost.AgentState state) {
      return new AgentView(
          state.name(), state.surface(), state.state(), state.sessionId(), state.commandId());
    }
  }

  /** The listing. */
  @Schema(name = "NamedAgents")
  public record AgentsResponse(
      @Schema(description = "Every named agent of the project, the desk first", required = true)
          List<AgentView> agents) {}

  /** What a person types to a named agent. */
  @Schema(name = "NamedAgentPromptRequest")
  public record PromptRequest(
      @Schema(description = "The prompt; must not be blank", required = true) String text) {}

  /** The daemon's answer to a prompt, passed through verbatim, or the 202 of a held one. */
  @Schema(name = "NamedAgentPromptResponse")
  public record PromptResponse(
      @Schema(description = "The command the prompt was given to; null while it is held",
              nullable = true)
          String commandId,
      @Schema(description = "That command's state as the daemon reports it, or STARTING when held")
          String state,
      @Schema(description = "Whether the prompt was handed to the session at once") boolean delivered,
      @Schema(description = "Whether it waits instead: behind a running turn, or for the session")
          boolean queued,
      @Schema(description = "Its place in the daemon's queue when queued there, else null",
              nullable = true)
          Integer position) {}

  /** A named agent's last answer, passed through verbatim. */
  @Schema(name = "NamedAgentLastAnswer")
  public record LastAnswerResponse(
      @Schema(description = "The command that gave it", nullable = true) String commandId,
      @Schema(description = "Its agent session", nullable = true) String sessionId,
      @Schema(description = "That command's state, as the daemon reports it", nullable = true)
          String state,
      @Schema(description = "The last answer's text; null before the first", nullable = true)
          String text,
      @Schema(description = "When it was given; null before the first", nullable = true)
          String at) {}

  /** Why the door, or the daemon behind it, refused. */
  @Schema(name = "NamedAgentRefusal")
  public record Refusal(String reason) {}

  @GET
  @Operation(
      operationId = "listAgents",
      summary = "The project's named agents: the desk, then every other, prompted or not")
  @APIResponse(
      responseCode = "200",
      description = "Every named agent, in the registry's order",
      content = @Content(schema = @Schema(implementation = AgentsResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such project",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  public AgentsResponse list(@PathParam("projectId") String projectId) {
    projects.get(projectId); // 404 for an unknown project, before another project's 403
    if (AgentAccess.isDesk(identity)) {
      AgentAccess.requireDeskProject(identity, projectId);
    }
    return new AgentsResponse(agents.list(projectId).stream().map(AgentView::of).toList());
  }

  @POST
  @Path("/{name}/prompt")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "promptAgent",
      summary = "Hand a named agent a prompt; a stopped desk container is started for it",
      description =
          "The desk receives the text behind the brief pointer, as on the front desk's own door;"
              + " any other agent receives the text alone. A prompt that cannot reach the agent's"
              + " session yet (the container stopped or starting) is held and answered 202"
              + " STARTING, and delivered once the session is up.")
  @APIResponse(
      responseCode = "200",
      description = "Delivered or queued by the agent's session",
      content = @Content(schema = @Schema(implementation = PromptResponse.class)))
  @APIResponse(
      responseCode = "202",
      description = "Held until the agent's session is up: state STARTING, queued true",
      content = @Content(schema = @Schema(implementation = PromptResponse.class)))
  @APIResponse(
      responseCode = "400",
      description = "Blank text",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such project, or no named agent of that name",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "A prompt already waits for the agent, the agent is not running, or the desk's daemon"
              + " predates the door",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "429",
      description = "The agent's prompt queue is full",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "503",
      description = "The desk's daemon could not be reached (for the desk: is not connected yet)",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  public Response prompt(
      @PathParam("projectId") String projectId,
      @PathParam("name") String name,
      PromptRequest request) {
    if (request == null || request.text() == null || request.text().isBlank()) {
      return refusal(400, "A prompt needs text.");
    }
    Response unknown = unknownProject(projectId);
    if (unknown != null) {
      return unknown;
    }
    Optional<NamedAgents.Agent> agent = NamedAgents.find(name);
    if (agent.isEmpty()) {
      return unknownAgent(name);
    }
    if (!agent.get().desk()) {
      return respond(agents.prompt(projectId, agent.get(), request.text()));
    }
    // The desk: the agent-container ensure door's path first, so a stopped desk is started, then
    // the front desk's own prompt exactly as its door makes it.
    frontDesks.ensure(projectId);
    String body;
    try {
      body =
          json.writeValueAsString(
              Map.of(
                  "text",
                  DeskBrief.pointer(guide.renderHash(DevelopmentGuideDefaults.DESK))
                      + "\n\n"
                      + request.text()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    try {
      return respond(
          prompter.classify(
              projectId,
              FrontDeskPrompter.PROMPT_ROUTE,
              false,
              daemon.post(projectId, FrontDeskPrompter.PROMPT_ROUTE, body, timeoutMs)));
    } catch (DaemonApiClient.Unreachable e) {
      return respond(prompter.unreachable(projectId, e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return refusal(503, "Interrupted while asking the project's front desk.");
    }
  }

  @GET
  @Path("/{name}/last-answer")
  @Operation(operationId = "getAgentLastAnswer", summary = "The last answer a named agent gave")
  @APIResponse(
      responseCode = "200",
      description = "Its last answer; text and at are null before the first",
      content = @Content(schema = @Schema(implementation = LastAnswerResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such project, no named agent of that name, or the daemon keeps none",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "409",
      description = "The desk's daemon predates the door",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  @APIResponse(
      responseCode = "503",
      description = "No daemon is connected for the project, or it could not be reached",
      content = @Content(schema = @Schema(implementation = Refusal.class)))
  public Response lastAnswer(
      @PathParam("projectId") String projectId, @PathParam("name") String name) {
    Response unknown = unknownProject(projectId);
    if (unknown != null) {
      return unknown;
    }
    Optional<NamedAgents.Agent> agent = NamedAgents.find(name);
    if (agent.isEmpty()) {
      return unknownAgent(name);
    }
    if (!agent.get().desk()) {
      return respond(agents.lastAnswer(projectId, agent.get()));
    }
    String route = "front-desk/last-answer";
    try {
      return respond(
          prompter.classify(projectId, route, true, daemon.get(projectId, route, timeoutMs)));
    } catch (DaemonApiClient.Unreachable e) {
      return respond(prompter.unreachable(projectId, e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return refusal(503, "Interrupted while asking the project's front desk.");
    }
  }

  /**
   * 404 for a project this service does not know, else null — and the delivery desk ({@code
   * qits:desk}, carried by {@code qits:desk-admin}) bound to its own project, after the 404, as on
   * {@code ProjectFrontDeskController}.
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

  private static Response unknownAgent(String name) {
    return refusal(
        404,
        "No named agent "
            + name
            + " (known: "
            + String.join(", ", NamedAgents.ALL.stream().map(NamedAgents.Agent::name).toList())
            + ").");
  }

  /** A reply as a response: the daemon's body verbatim, or a {@link Refusal} of ours. */
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
