package eu.wohlben.qits.projects.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.deskhost.DeskRunnerAddresses;
import eu.wohlben.qits.projects.deskhost.DeskRunnerInstallScript;
import eu.wohlben.qits.projects.deskhost.DeskRunnerSessions;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import eu.wohlben.qits.projects.dto.DeskRunnerHealthDto;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.DeskRunnerCapabilities;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.idphost.IdpTokens;
import eu.wohlben.qits.projects.mapper.DeskRunnerMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The front-desk runners' surface (qits-767), qits-workspaces-service's {@code
 * WorkspaceRunnerController} in the desk spelling: the verbs an operator uses to declare, read,
 * tune, re-key and decommission a runner, three for its standing — greenlight, a health check and a
 * login check on demand — and the one door a runner itself knocks on to register.
 *
 * <p><b>Roles, per method and never on the class.</b> The reads take {@code qits:admin}, {@code
 * qits:admin-agent}, {@code qits:system} and {@code qits:agent}. Create, patch, a registration
 * token rotation and delete take {@code {qits:admin, qits:admin-agent, qits:system}}, because the
 * cold bootstrap's own service client creates and rotates a runner with nobody at a keyboard;
 * greenlight and login check are {@code qits:admin} (and {@code qits:admin-agent}) alone. The health
 * check is open to all four: it reads and self-tests, and moves a runner's standing only as its own
 * result does. {@code qits:desk-runner-registration} — what a registration token carries through the
 * edge — opens exactly two routes: the register door, for the runner the token was minted for only,
 * and {@code GET /runners/install.sh}, which carries no secret and names no runner.
 *
 * <p><b>Every credential here is handed out exactly once and logged never.</b> The registration
 * token is in the install line that answers create (and a rotation) and nowhere else; the runner's
 * client secret is in the register door's answer and nowhere else.
 *
 * <p><b>qits-idp is called from here</b>, and {@link DeskRunners} offers the check before each call
 * and the write after it, so no transaction is held across the network. A credential minted for a
 * write that then loses a race is given back at once; one whose give-back cannot reach qits-idp is
 * reaped by {@code DeskRunnerCommissionReconcile}.
 *
 * <p><b>The live half is a port.</b> Whether a runner is connected, and the frames a door sends one,
 * are {@link DeskRunnerSessions}' — the runner socket's, which is the next task; until it lands no
 * runner is connected and the health and login checks answer 409 {@code RUNNER_UNAVAILABLE}.
 *
 * <p><b>The machine gate.</b> The register door reads the bearer's {@code sub} and nothing else:
 * with the gate ({@code qits.auth.machine.required}) off there is no subject, and every registration
 * is a 403.
 */
@Path("/runners")
@Produces(MediaType.APPLICATION_JSON)
// create, healthcheck and health answer through a bare Response, so their records are on no
// signature the native build indexes.
@RegisterForReflection(
    targets = {
      DeskRunnerController.RunnerRegistrationDto.class,
      DeskRunnerDto.class,
      DeskRunnerDto.DeskRunnerDesk.class,
      DeskRunnerDto.DeskRunnerHealth.class,
      DeskRunnerDto.DeskRunnerCheck.class,
      DeskRunnerController.HealthCheckRequested.class,
      DeskRunnerHealthDto.class,
      DeskRunnerHealthDto.DeskRunnerCheckReport.class
    })
public class DeskRunnerController {

  private static final Logger LOG = Logger.getLogger(DeskRunnerController.class);

  static final String ADMIN_ROLE = "qits:admin";

  /** An admin workspace's agent: admitted wherever {@link #ADMIN_ROLE} is. */
  static final String ADMIN_AGENT_ROLE = "qits:admin-agent";

  /** The bootstrap's own service client: the four lifecycle writes take it beside the admin. */
  static final String SYSTEM_ROLE = "qits:system";

  static final String AGENT_ROLE = "qits:agent";

  /** What a registration token carries, and the only role the register door admits. */
  static final String REGISTRATION_ROLE = "qits:desk-runner-registration";

  /** The refusal of a door that needs a connected runner. */
  static final String RUNNER_UNAVAILABLE = "RUNNER_UNAVAILABLE";

  @Inject DeskRunners runners;

  @Inject DeskRunnerSessions sessions;

  @Inject IdpRunnerCommissioner idp;

  @Inject DeskRunnerAddresses addresses;

  @Inject DeskRunnerInstallScript installScript;

  @Inject MachineAuth machineAuth;

  @Inject SecurityIdentity identity;

  @Schema(name = "CreateDeskRunnerRequest")
  public record CreateRunnerRequest(
      @Schema(description = "[a-z][a-z0-9-]{0,63}, unique", required = true) String name,
      @Schema(description = "Free text, at most 1024 characters") String description,
      @Schema(description = "How many desks it may hold at once, at least 1. Default 8")
          Integer slots) {}

  @Schema(name = "PatchDeskRunnerRequest")
  public record PatchRunnerRequest(
      @Schema(description = "[a-z][a-z0-9-]{0,63}, unique; absent leaves it") String name,
      @Schema(description = "Blank clears it; absent leaves it") String description,
      @Schema(description = "At least 1; absent leaves it") Integer slots) {}

  /**
   * A runner with the secret-bearing answer only the request that minted it gets: the registration
   * token, and the install line that carries it. Returned once; never readable again.
   */
  @Schema(name = "DeskRunnerRegistrationDto")
  public record RunnerRegistrationDto(
      DeskRunnerDto runner,
      @Schema(description = "The runner's one-use registration token. Returned once")
          String registrationToken,
      @Schema(
              description =
                  "The one line that installs the runner on a docker host: it fetches the generic"
                      + " install script with the registration token and pipes it into sudo sh"
                      + " with this runner's values. Returned once")
          String installLine) {
    @Override
    public String toString() {
      return "RunnerRegistrationDto[runner=" + (runner == null ? null : runner.id()) + "]";
    }
  }

  @Schema(name = "RegisterDeskRunnerRequest")
  public record RegisterRunnerRequest(
      @Schema(description = "What the runner says about itself — a JSON object, at most 16 KiB")
          JsonNode capabilities) {}

  /** What a health check request answers: the {@code requestId} its {@code healthChecked} echoes. */
  @Schema(name = "DeskRunnerHealthCheckRequested")
  public record HealthCheckRequested(String requestId) {}

  /** What the register door answers, once: the runner's own client and where to use it. */
  @Schema(name = "DeskRunnerRegistered")
  public record RegisteredRunner(
      String clientId, String secret, String tokenUrl, String audience, String socketUrl) {
    @Override
    public String toString() {
      return "RegisteredRunner[clientId=" + clientId + ", socketUrl=" + socketUrl + "]";
    }
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE})
  @Operation(summary = "Declare a front-desk runner; answers its install line, once")
  @APIResponse(
      responseCode = "201",
      description = "The runner, its registration token and the install line carrying it",
      content = @Content(schema = @Schema(implementation = RunnerRegistrationDto.class)))
  @APIResponse(responseCode = "400", description = "A malformed name, slots or description")
  @APIResponse(responseCode = "409", description = "The name is taken")
  @APIResponse(responseCode = "502", description = "qits-idp refused the registration token")
  @APIResponse(
      responseCode = "503",
      description =
          "This deployment commissions nothing, or RUNNER_PLANE_UNCONFIGURED: it knows no public"
              + " domain to address a runner by")
  public Response create(CreateRunnerRequest request) {
    requireMachineAudience();
    if (request == null) {
      throw new BadRequestException("A runner needs a name");
    }
    runners.requireCreatable(request.name(), request.description(), request.slots());
    requireCommissioning();
    installScript.requireRenderable();
    UUID id = UUID.randomUUID();
    IdpTokens.Issued token = commissionRegistrationToken(id);
    DeskRunner runner;
    try {
      runner =
          runners.create(
              id,
              request.name(),
              request.description(),
              request.slots(),
              token.tokenId(),
              token.subject());
    } catch (RuntimeException refused) {
      // The name went in between the check and the write: the token names a runner that does not
      // exist, so it goes back now rather than at the next reconcile.
      idp.deleteToken(token.tokenId());
      throw refused;
    }
    LOG.infof(
        "Front-desk runner %s (%s) declared; its registration token is %s",
        runner.name, id, token.tokenId());
    return Response.status(Response.Status.CREATED)
        .entity(registration(runner, token.token()))
        .build();
  }

  @GET
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE, AGENT_ROLE})
  @Operation(summary = "Every front-desk runner, by name, with its connection, login and desks")
  @APIResponse(responseCode = "200", description = "Every runner, by name")
  public List<DeskRunnerDto> list() {
    return runners.list().stream().map(this::view).toList();
  }

  /**
   * The generic install script the install line pipes into {@code sh}: this deployment's registry
   * and the pinned runner image, and no secret — the runner's values reach it from the line's
   * {@code env}. Read with the registration token, which is what a host holds before anything else.
   */
  @GET
  @Path("/install.sh")
  @Produces(MediaType.TEXT_PLAIN)
  @RolesAllowed({REGISTRATION_ROLE, ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE, AGENT_ROLE})
  @Operation(summary = "The generic front-desk runner install script the install line pipes to sh")
  @APIResponse(responseCode = "200", description = "A POSIX sh script, carrying no secret")
  @APIResponse(
      responseCode = "503",
      description = "RUNNER_PLANE_UNCONFIGURED, or the script cannot be rendered")
  public String installScript() {
    installScript.requireRenderable();
    return installScript.script();
  }

  @GET
  @Path("/{id}")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE, AGENT_ROLE})
  @Operation(summary = "One front-desk runner")
  @APIResponse(responseCode = "200", description = "The runner")
  @APIResponse(responseCode = "404", description = "No such runner")
  public DeskRunnerDto get(@PathParam("id") String id) {
    return view(runners.get(runnerId(id)));
  }

  @PATCH
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE})
  @Operation(summary = "Change a front-desk runner's name, description or slots")
  @APIResponse(responseCode = "200", description = "The runner as it now is")
  @APIResponse(
      responseCode = "400",
      description = "A malformed name, slots below 1, or an overlong description")
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "The name is taken")
  public DeskRunnerDto patch(@PathParam("id") String id, PatchRunnerRequest request) {
    requireMachineAudience();
    PatchRunnerRequest change = request == null ? new PatchRunnerRequest(null, null, null) : request;
    UUID runnerId = runnerId(id);
    DeskRunner patched =
        runners.patch(runnerId, change.name(), change.description(), change.slots());
    if (change.slots() != null) {
      // A connected runner learns its slots only from an ack.
      sessions.slotsChanged(runnerId);
    }
    return view(patched);
  }

  /**
   * A fresh registration token, for a runner whose first one was lost or leaked. The old one is
   * deleted at qits-idp once the new one is on the row, so between the two both open the door —
   * never neither. A registered runner is 409: it has spent its registration.
   */
  @POST
  @Path("/{id}/registration-token")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE})
  @Operation(
      summary = "Replace a front-desk runner's registration token; answers a new install line")
  @APIResponse(
      responseCode = "200",
      description = "The runner, its new registration token and the install line carrying it",
      content = @Content(schema = @Schema(implementation = RunnerRegistrationDto.class)))
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "The runner is already registered")
  @APIResponse(responseCode = "502", description = "qits-idp refused the registration token")
  @APIResponse(
      responseCode = "503",
      description = "This deployment commissions nothing, or RUNNER_PLANE_UNCONFIGURED")
  public RunnerRegistrationDto rotateRegistrationToken(@PathParam("id") String id) {
    requireMachineAudience();
    UUID runnerId = runnerId(id);
    runners.requireUnregistered(runnerId);
    requireCommissioning();
    installScript.requireRenderable();
    IdpTokens.Issued token = commissionRegistrationToken(runnerId);
    String previous;
    try {
      previous = runners.replaceRegistrationToken(runnerId, token.tokenId(), token.subject());
    } catch (RuntimeException refused) {
      idp.deleteToken(token.tokenId());
      throw refused;
    }
    if (previous != null && !idp.deleteToken(previous)) {
      LOG.warnf(
          "Front-desk runner %s's replaced registration token %s is still live at qits-idp; the"
              + " commission reconcile reaps it",
          runnerId, previous);
    }
    return registration(runners.get(runnerId), token.token());
  }

  /**
   * Decommission a runner. The row goes first — 409 {@code RUNNER_OWNS_DESKS} while a project's
   * front desk is placed on it — then a connected runner is retired as {@code DELETED}, and its
   * client and registration token are given back at qits-idp.
   */
  @DELETE
  @Path("/{id}")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE})
  @Operation(summary = "Decommission a front-desk runner and give its credentials back")
  @APIResponse(responseCode = "204", description = "Gone")
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(
      responseCode = "409",
      description = "RUNNER_OWNS_DESKS: a project's front desk is placed on it")
  public Response delete(@PathParam("id") String id) {
    requireMachineAudience();
    DeskRunner gone = runners.delete(runnerId(id));
    sessions.deleted(gone.id);
    if (gone.clientId != null) {
      idp.decommissionClient(gone.clientId);
    }
    if (gone.registrationTokenId != null) {
      idp.deleteToken(gone.registrationTokenId);
    }
    LOG.infof("Front-desk runner %s (%s) decommissioned", gone.name, gone.id);
    return Response.noContent().build();
  }

  /**
   * Lift a runner's quarantine: it takes desks again up to its slots. A runner in service is
   * answered as it is — the door states an outcome, so pressing it twice is the same as once.
   */
  @POST
  @Path("/{id}/greenlight")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE})
  @Operation(summary = "Lift a front-desk runner's quarantine")
  @APIResponse(responseCode = "200", description = "The runner as it now is")
  @APIResponse(responseCode = "404", description = "No such runner")
  public DeskRunnerDto greenlight(@PathParam("id") String id) {
    UUID runnerId = runnerId(id);
    boolean wasQuarantined = runners.get(runnerId).quarantined();
    DeskRunner lifted = runners.greenlight(runnerId);
    if (wasQuarantined) {
      sessions.reinstated(runnerId, by());
    }
    return view(lifted);
  }

  /**
   * Ask a connected runner to run its health check now. Its {@code healthChecked} is recorded on the
   * row; a pass lifts a quarantine and a failure begins one. A runner with a check pending is
   * answered that one's {@code requestId} and asked nothing more.
   */
  @POST
  @Path("/{id}/healthcheck")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE, AGENT_ROLE})
  @Operation(summary = "Ask a connected front-desk runner for a health check")
  @APIResponse(
      responseCode = "202",
      description = "Sent, or one is pending already; its requestId",
      content = @Content(schema = @Schema(implementation = HealthCheckRequested.class)))
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "RUNNER_UNAVAILABLE: the runner is not connected")
  public Response healthcheck(@PathParam("id") String id) {
    UUID runnerId = runnerId(id);
    DeskRunner runner = runners.get(runnerId);
    String requestId =
        sessions
            .requestHealthCheck(runnerId)
            .orElseThrow(() -> unavailable(runner, "run a health check"));
    return Response.accepted(new HealthCheckRequested(requestId)).build();
  }

  /**
   * The runner's newest health check in full: the verdict, the request it answered and every named
   * check with its data. 204 with no body while no check has settled yet — the runner exists, so it
   * is not a 404.
   */
  @GET
  @Path("/{id}/health")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE, SYSTEM_ROLE, AGENT_ROLE})
  @Operation(summary = "A front-desk runner's newest health check, every check's data included")
  @APIResponse(
      responseCode = "200",
      description = "The newest health check",
      content = @Content(schema = @Schema(implementation = DeskRunnerHealthDto.class)))
  @APIResponse(responseCode = "204", description = "No health check has settled yet")
  @APIResponse(responseCode = "404", description = "No such runner")
  public Response health(@PathParam("id") String id) {
    DeskRunnerHealthDto report = runners.health(runnerId(id));
    return report == null ? Response.noContent().build() : Response.ok(report).build();
  }

  /** Ask a connected runner to probe its node's agent login now; {@code loginState} records it. */
  @POST
  @Path("/{id}/login-check")
  @RolesAllowed({ADMIN_ROLE, ADMIN_AGENT_ROLE})
  @Operation(summary = "Ask a connected front-desk runner to re-check its node's agent login")
  @APIResponse(responseCode = "202", description = "Sent")
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "RUNNER_UNAVAILABLE: the runner is not connected")
  public Response loginCheck(@PathParam("id") String id) {
    UUID runnerId = runnerId(id);
    DeskRunner runner = runners.get(runnerId);
    if (!sessions.probeLogin(runnerId)) {
      throw unavailable(runner, "check its login");
    }
    return Response.accepted().build();
  }

  /**
   * The register door. A runner presents its registration token to the platform edge, which
   * introspects it and forwards the short JWT it mints for it — role {@code
   * qits:desk-runner-registration}, {@code sub} the token's subject — with what the runner says about
   * itself, and is answered its own client, once.
   *
   * <p><b>The refusals, in order</b>: a bearer that is no machine token or not addressed to this
   * platform ({@code MachineAuth}); capabilities that are not a JSON object (400); no such runner
   * (404); a {@code sub} that is not this runner's registration token subject (403); a runner
   * already registered (409, which is also what a replay answers); a deployment that commissions
   * nothing or knows no public domain (503); qits-idp refusing the client (502).
   *
   * <p>The spent token is deleted at qits-idp, and a failure to is logged rather than fatal: once
   * the row carries a client the token opens nothing here, and the reconcile reaps it. The runner is
   * quarantined from here until its first health check passes.
   */
  @POST
  @Path("/{id}/register")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(REGISTRATION_ROLE)
  @Operation(
      summary = "Register a front-desk runner with its registration token; answers its client")
  @APIResponse(
      responseCode = "200",
      description = "The runner's own client and where to use it",
      content = @Content(schema = @Schema(implementation = RegisteredRunner.class)))
  @APIResponse(responseCode = "400", description = "Capabilities that are not a JSON object")
  @APIResponse(responseCode = "403", description = "The token is not this runner's")
  @APIResponse(responseCode = "404", description = "No such runner")
  @APIResponse(responseCode = "409", description = "The runner is already registered")
  @APIResponse(responseCode = "502", description = "qits-idp refused the runner's client")
  @APIResponse(
      responseCode = "503",
      description = "This deployment commissions nothing, or RUNNER_PLANE_UNCONFIGURED")
  public RegisteredRunner register(@PathParam("id") String id, RegisterRunnerRequest request) {
    machineAuth.require();
    UUID runnerId = runnerId(id);
    JsonNode capabilities = request == null ? null : request.capabilities();
    try {
      DeskRunnerCapabilities.merge(null, capabilities);
    } catch (IllegalArgumentException malformed) {
      throw new BadRequestException(malformed.getMessage());
    }
    String subject = MachineIdentity.claim(identity, "sub").orElse(null);
    DeskRunner runner = runners.requireRegistrable(runnerId, subject);
    requireCommissioning();
    // Before the client is minted: a runner that could not be told where to use it would hold a
    // client nobody can reach anything with.
    String tokenUrl = addresses.tokenUrl();
    String socketUrl = addresses.socketUrl();
    IdpRunnerCommissioner.RunnerClient client;
    try {
      client = idp.runnerClient(runnerId);
    } catch (IdpRunnerCommissioner.CommissionFailedException failed) {
      throw new DomainException(
          502, "qits-idp did not commission the runner's client: " + failed.getMessage());
    }
    try {
      runners.markRegistered(runnerId, client.clientId(), capabilities);
    } catch (RuntimeException refused) {
      // Two registrations raced and the other won: this client was never recorded.
      idp.decommissionClient(client.clientId());
      throw refused;
    }
    if (runner.registrationTokenId != null && !idp.deleteToken(runner.registrationTokenId)) {
      LOG.warnf(
          "Front-desk runner %s registered, and its spent registration token %s is still live at"
              + " qits-idp; it opens nothing now, and the reconcile reaps it",
          runnerId, runner.registrationTokenId);
    }
    LOG.infof(
        "Front-desk runner %s (%s) registered as %s; quarantined until its first health check",
        runner.name, runnerId, client.clientId());
    return new RegisteredRunner(
        client.clientId(), client.secret(), tokenUrl, addresses.audience(), socketUrl);
  }

  private DeskRunnerDto view(DeskRunner runner) {
    return runners.view(
        runner,
        new DeskRunnerMapper.Live(
            sessions.connected(runner.id),
            sessions.connectedSince(runner.id),
            sessions.pinnedVersion(),
            sessions.loginCommand(runner),
            runners.desksOn(runner.id)));
  }

  private RunnerRegistrationDto registration(DeskRunner runner, String token) {
    return new RunnerRegistrationDto(view(runner), token, installScript.line(runner, token));
  }

  private IdpTokens.Issued commissionRegistrationToken(UUID runnerId) {
    IdpTokens.Issued token;
    try {
      token = idp.registrationToken(runnerId);
    } catch (IdpRunnerCommissioner.CommissionFailedException failed) {
      throw new DomainException(
          502, "qits-idp did not commission the registration token: " + failed.getMessage());
    }
    try {
      DeskRunnerInstallScript.requireCarriable(token.token());
    } catch (IllegalArgumentException uncarriable) {
      // Checked before the row is written, so the token has nowhere to be but qits-idp.
      idp.deleteToken(token.tokenId());
      throw new DomainException(502, uncarriable.getMessage());
    }
    return token;
  }

  private static CodedRefusalException unavailable(DeskRunner runner, String what) {
    return new CodedRefusalException(
        409,
        RUNNER_UNAVAILABLE,
        "Runner " + runner.name + " is not connected, so it cannot " + what + " now");
  }

  /** Who lifted a quarantine, as {@code reinstated{by}} tells the runner. */
  private String by() {
    return identity == null || identity.isAnonymous()
        ? "an administrator"
        : identity.getPrincipal().getName();
  }

  /**
   * The machine arm of the four lifecycle writes: a {@code qits:system} caller presenting a machine
   * token must present one addressed to this platform. A {@code qits:admin} caller — a forwarded
   * session carries no token at all — is judged by its role, and so is a {@code qits:admin-agent}
   * one.
   */
  private void requireMachineAudience() {
    if (MachineIdentity.isMachine(identity)
        && !identity.hasRole(ADMIN_ROLE)
        && !identity.hasRole(ADMIN_AGENT_ROLE)) {
      machineAuth.require();
    }
  }

  /** 503 when this deployment cannot commission: a runner is nothing without its credentials. */
  private void requireCommissioning() {
    if (!idp.enabled()) {
      throw new DomainException(
          503,
          "This qits-projects commissions no credentials (quarkus.oidc-client.qits.client-enabled"
              + " is off, or no client secret is set), so it cannot mint a runner's");
    }
  }

  /** A path id that is not a uuid names no runner: 404, the answer for any unknown id. */
  private static UUID runnerId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAUuid) {
      throw new NotFoundException("No front-desk runner " + id);
    }
  }
}
