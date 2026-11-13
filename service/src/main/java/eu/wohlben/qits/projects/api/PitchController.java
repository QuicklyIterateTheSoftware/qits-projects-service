package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.entities.api.EntitiesAgentAccess;
import eu.wohlben.qits.projects.agenthost.AgentControlSocketAccess;
import eu.wohlben.qits.projects.control.NamedAgentCaller;
import eu.wohlben.qits.projects.control.Pitches;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entity.Pitch;
import eu.wohlben.qits.projects.entity.PitchColorScheme;
import eu.wohlben.qits.projects.entity.PitchKind;
import eu.wohlben.qits.projects.entity.PitchVersion;
import eu.wohlben.qits.projects.entity.PitchViewport;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.PitchRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>Pitches</b> (qits-2155): a named agent's mock snapshots of the UI builder's screenshots, kept
 * per agent with every version of their HTML. The UI builder's agent pitches; a person compares the
 * pitch with the original (qits-2158) without committing to anything, and one they like becomes work
 * when the agent is asked to file a ticket or idea that links it.
 *
 * <ul>
 *   <li><b>Reads</b> are every project reader's — {@code qits:admin}, {@code qits:admin-agent},
 *       {@code qits:desk-admin}, {@code qits:desk}, {@code qits:agent} and {@code qits:system} — each
 *       bound to the pitch's project by {@link EntitiesAgentAccess#requireProject}.
 *   <li><b>Only a named agent creates a pitch</b>: a caller whose token subject {@link
 *       NamedAgentCaller} resolves to a named agent of the body's project, which becomes the pitch's
 *       owner. Anyone else is a 403 <i>Only a named agent pitches</i> — an admin included, because the
 *       owner decided pitches are per agent.
 *   <li><b>Only the pitch's agent adds a version</b>; everyone else is a 403. An archived pitch takes
 *       none (409).
 *   <li><b>The pitch's agent, {@code qits:admin} or {@code qits:desk-admin} archives it</b> ({@code
 *       qits:admin-agent} too, wherever {@code qits:admin} is named). Idempotent; nothing is deleted.
 * </ul>
 *
 * <p><b>No door here ever answers {@code text/html}.</b> The HTML is agent-authored and is not
 * sanitised on the server, as with refinement designs ({@link RefinementDesignController}): it
 * travels only as a JSON field, and a reader renders it in a sandboxed, script-free frame — that
 * frame is the safety, not this service. Served same-origin as a page it would be an XSS door into
 * the platform's own session, so there is deliberately no content route. A listing and a detail
 * leave the document out; only {@code GET /pitches/{id}/versions/{n}} carries it.
 */
@Path("/pitches")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  "qits:admin",
  "qits:admin-agent",
  "qits:desk-admin",
  "qits:desk",
  "qits:agent",
  "qits:system"
})
public class PitchController {

  /** The refusal of a create by anything but a named agent. */
  static final String ONLY_A_NAMED_AGENT = "Only a named agent pitches";

  /** The refusal of a version by anything but the pitch's agent. */
  static final String ONLY_THE_PITCH_AGENT = "Only the pitch's agent may change it";

  @Inject Pitches pitches;

  @Inject NamedAgentCaller callers;

  @Inject EntityIdResolver resolver;

  @Inject RepositoryNameRepository repositoryNames;

  @Inject SecurityIdentity identity;

  @Schema(name = "PitchSummary", description = "A pitch without its versions or its HTML.")
  public record PitchSummary(
      @Schema(required = true) String id,
      @Schema(description = "The named agent that pitched it, e.g. ui-builder", required = true)
          String agent,
      @Schema(required = true) String repositoryId,
      @Schema(description = "The repository's name, null when it answers to none")
          String repositoryName,
      @Schema(description = "The freeze's route", required = true) String route,
      @Schema(description = "The freeze's file key in manifest.json", required = true)
          String screenshot,
      @Schema(required = true) PitchKind kind,
      @Schema(required = true) PitchViewport viewport,
      @Schema(description = "Null for an app with no colour schemes") PitchColorScheme colorScheme,
      @Schema(description = "The @routes bundle version the screenshot came from", required = true)
          String manifestVersion,
      @Schema(required = true) String title,
      String note,
      @Schema(description = "The newest version number", required = true) int latestVersion,
      @Schema(description = "When it was archived; null while it is not") Instant archivedAt,
      @Schema(required = true) Instant createdAt,
      @Schema(required = true) Instant updatedAt) {}

  @Schema(name = "PitchVersionSummary", description = "One version of a pitch, without its HTML.")
  public record PitchVersionSummary(
      @Schema(description = "From 1", required = true) int number,
      @Schema(description = "The HTML's UTF-8 length", required = true) int htmlBytes,
      @Schema(description = "What changed") String note,
      @Schema(required = true) Instant createdAt) {}

  @Schema(name = "PitchDetail", description = "A pitch with every version, oldest first, no HTML.")
  public record PitchDetail(
      @Schema(required = true) String id,
      @Schema(required = true) String agent,
      @Schema(required = true) String repositoryId,
      String repositoryName,
      @Schema(required = true) String route,
      @Schema(required = true) String screenshot,
      @Schema(required = true) PitchKind kind,
      @Schema(required = true) PitchViewport viewport,
      PitchColorScheme colorScheme,
      @Schema(required = true) String manifestVersion,
      @Schema(required = true) String title,
      String note,
      @Schema(required = true) int latestVersion,
      Instant archivedAt,
      @Schema(required = true) Instant createdAt,
      @Schema(required = true) Instant updatedAt,
      @Schema(required = true) List<PitchVersionSummary> versions) {}

  @Schema(
      name = "PitchVersion",
      description =
          "One version of a pitch with its HTML — agent-authored, unsanitised, for a sandboxed"
              + " script-free frame only.")
  public record PitchVersionDto(
      @Schema(required = true) int number,
      @Schema(required = true) String html,
      @Schema(required = true) int htmlBytes,
      String note,
      @Schema(required = true) Instant createdAt) {}

  @Schema(name = "PitchList")
  public record PitchList(@Schema(required = true) List<PitchSummary> pitches) {}

  @Schema(name = "NewPitch", description = "A new pitch and its version 1.")
  public record NewPitch(
      @Schema(description = "The project's id or slug", required = true) String project,
      @Schema(description = "A repository of the project, by id or name", required = true)
          String repository,
      @Schema(required = true) String route,
      @Schema(required = true) String screenshot,
      @Schema(required = true, enumeration = {"DATA", "NO_DATA", "PENDING", "ERROR"}) String kind,
      @Schema(required = true, enumeration = {"PHONE", "TABLET", "DESKTOP"}) String viewport,
      @Schema(enumeration = {"LIGHT", "DARK"}) String colorScheme,
      @Schema(required = true) String manifestVersion,
      @Schema(required = true) String title,
      String note,
      @Schema(description = "The whole document, styles inline", required = true) String html,
      @Schema(description = "Version 1's note") String versionNote) {}

  @Schema(name = "NewPitchVersion", description = "The next version of a pitch.")
  public record NewPitchVersion(
      @Schema(description = "The whole document, styles inline", required = true) String html,
      @Schema(description = "What changed") String note) {}

  @GET
  @Operation(
      operationId = "listPitches",
      summary = "A project's pitches, newest change first, without their HTML",
      description =
          "Narrowed by agent, repository (id or name), route and screenshot; archived pitches only"
              + " with archived=true.")
  public PitchList list(
      @QueryParam("project") String project,
      @QueryParam("agent") String agent,
      @QueryParam("repository") String repository,
      @QueryParam("route") String route,
      @QueryParam("screenshot") String screenshot,
      @QueryParam("archived") @DefaultValue("false") boolean archived) {
    Project row = project(project);
    EntitiesAgentAccess.requireProject(identity, row.id);
    String repositoryId =
        blank(repository) ? null : repository(row.id, repository).id;
    List<Pitches.Listed> listed =
        pitches.list(
            new PitchRepository.Filter(
                row.id,
                blankToNull(agent),
                repositoryId,
                blankToNull(route),
                blankToNull(screenshot),
                archived));
    Map<String, String> names =
        QuarkusTransaction.requiringNew().call(() -> repositoryNames.namesByRepository(row.id));
    return new PitchList(
        listed.stream()
            .map(l -> summary(l.pitch(), l.latestVersion(), names.get(l.pitch().repositoryId)))
            .toList());
  }

  @GET
  @Path("/{id}")
  @Operation(
      operationId = "getPitch",
      summary = "One pitch with every version, oldest first, without their HTML")
  public PitchDetail get(@PathParam("id") String id) {
    Pitches.Detail detail = pitches.detail(pitchId(id));
    EntitiesAgentAccess.requireProject(identity, detail.pitch().projectId);
    return detail(detail);
  }

  @GET
  @Path("/{id}/versions/{number}")
  @Operation(
      operationId = "getPitchVersion",
      summary = "One version of a pitch with its HTML, as a JSON field",
      description =
          "number is a version from 1, or latest. The HTML is agent-authored and unsanitised: render"
              + " it only in a sandboxed, script-free frame.")
  public PitchVersionDto version(@PathParam("id") String id, @PathParam("number") String number) {
    UUID pitchId = pitchId(id);
    EntitiesAgentAccess.requireProject(identity, pitches.get(pitchId).projectId);
    return version(pitches.version(pitchId, number));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "createPitch",
      summary = "Pitch a mock of one screenshot: the pitch and its version 1",
      description =
          "Only a named agent of the project pitches, and the pitch is that agent's; anyone else is"
              + " a 403. The HTML is at most qits.projects.pitch-max-bytes (413), and not empty"
              + " (400).")
  public Response create(NewPitch request) {
    if (request == null) {
      throw new DomainException(400, "A pitch needs a body.");
    }
    if (blank(request.project())) {
      throw new DomainException(400, "project is required.");
    }
    Project project = project(request.project());
    EntitiesAgentAccess.requireProject(identity, project.id);
    NamedAgentCaller.Caller caller =
        caller()
            .filter(c -> c.projectId().equals(project.id))
            .orElseThrow(() -> new DomainException(403, ONLY_A_NAMED_AGENT + "."));
    if (blank(request.repository())) {
      throw new DomainException(400, "repository is required.");
    }
    Repository repository = repository(project.id, request.repository());
    Pitches.Detail created =
        pitches.create(
            new Pitches.NewPitch(
                project.id,
                caller.name(),
                repository.id,
                request.route(),
                request.screenshot(),
                request.kind(),
                request.viewport(),
                request.colorScheme(),
                request.manifestVersion(),
                request.title(),
                request.note(),
                request.html(),
                request.versionNote(),
                AgentControlSocketAccess.subjectOf(identity)));
    return Response.status(Response.Status.CREATED).entity(detail(created)).build();
  }

  @POST
  @Path("/{id}/versions")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "addPitchVersion",
      summary = "Append the next version of a pitch",
      description =
          "Only the pitch's agent; an archived pitch is a 409. Answers the new version without its"
              + " HTML.")
  public Response addVersion(@PathParam("id") String id, NewPitchVersion request) {
    UUID pitchId = pitchId(id);
    Pitch row = pitches.get(pitchId);
    EntitiesAgentAccess.requireProject(identity, row.projectId);
    if (!isOwner(row)) {
      throw new DomainException(403, ONLY_THE_PITCH_AGENT + ".");
    }
    if (request == null) {
      throw new DomainException(400, "A version needs a body.");
    }
    PitchVersion version = pitches.addVersion(pitchId, request.html(), request.note());
    return Response.status(Response.Status.CREATED).entity(versionSummary(version)).build();
  }

  @POST
  @Path("/{id}/archive")
  @Operation(
      operationId = "archivePitch",
      summary = "Archive a pitch: hidden from the default listing, nothing deleted",
      description = "The pitch's agent, qits:admin or qits:desk-admin. Idempotent.")
  public PitchDetail archive(@PathParam("id") String id) {
    UUID pitchId = pitchId(id);
    Pitch row = pitches.get(pitchId);
    EntitiesAgentAccess.requireProject(identity, row.projectId);
    boolean admin =
        identity.hasRole(AgentAccess.ADMIN_ROLE)
            || identity.hasRole(AgentAccess.ADMIN_AGENT_ROLE)
            || identity.hasRole(AgentAccess.DESK_ADMIN_ROLE);
    if (!admin && !isOwner(row)) {
      throw new DomainException(
          403, "Only the pitch's agent, an admin or the desk-admin may archive it.");
    }
    return detail(pitches.archive(pitchId));
  }

  // --- the caller --------------------------------------------------------------------------------

  /** The named agent this caller's token is, in the token's own project, or empty. */
  private Optional<NamedAgentCaller.Caller> caller() {
    String subject = AgentControlSocketAccess.subjectOf(identity);
    if (subject == null) {
      return Optional.empty();
    }
    return MachineIdentity.claim(identity, QitsClaims.PROJECT)
        .flatMap(project -> callers.resolve(project, subject));
  }

  /** Whether the caller is the named agent that owns {@code pitch}. */
  private boolean isOwner(Pitch pitch) {
    return caller()
        .filter(c -> c.projectId().equals(pitch.projectId) && c.name().equals(pitch.agentName))
        .isPresent();
  }

  // --- resolution --------------------------------------------------------------------------------

  private Project project(String idOrSlug) {
    if (blank(idOrSlug)) {
      throw new DomainException(400, "project is required.");
    }
    return resolver.resolveProject(idOrSlug);
  }

  /** A repository of {@code projectId}, by name or by id; 404 for one the project does not have. */
  private Repository repository(String projectId, String idOrName) {
    String named = idOrName.trim();
    Repository found =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    repositoryNames
                        .findRepositoryByProjectAndName(projectId, named)
                        .orElseGet(
                            () -> {
                              Repository byId = Repository.findById(named);
                              return byId != null && projectId.equals(byId.project.id)
                                  ? byId
                                  : null;
                            }));
    if (found == null) {
      throw new NotFoundException("The project has no repository " + named + ".");
    }
    return found;
  }

  private static UUID pitchId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new NotFoundException("No such pitch: " + id);
    }
  }

  // --- the wire ----------------------------------------------------------------------------------

  private String repositoryName(String repositoryId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository repository = Repository.findById(repositoryId);
              return repository == null ? null : repositoryNames.nameFor(repository).orElse(null);
            });
  }

  private static PitchSummary summary(Pitch row, int latestVersion, String repositoryName) {
    return new PitchSummary(
        row.id.toString(),
        row.agentName,
        row.repositoryId,
        repositoryName,
        row.route,
        row.screenshot,
        row.kind,
        row.viewport,
        row.colorScheme,
        row.manifestVersion,
        row.title,
        row.note,
        latestVersion,
        row.archivedAt,
        row.createdAt,
        row.updatedAt);
  }

  private PitchDetail detail(Pitches.Detail detail) {
    Pitch row = detail.pitch();
    return new PitchDetail(
        row.id.toString(),
        row.agentName,
        row.repositoryId,
        repositoryName(row.repositoryId),
        row.route,
        row.screenshot,
        row.kind,
        row.viewport,
        row.colorScheme,
        row.manifestVersion,
        row.title,
        row.note,
        detail.latestVersion(),
        row.archivedAt,
        row.createdAt,
        row.updatedAt,
        detail.versions().stream()
            .map(v -> new PitchVersionSummary(v.number(), v.htmlBytes(), v.note(), v.createdAt()))
            .toList());
  }

  private static PitchVersionSummary versionSummary(PitchVersion version) {
    return new PitchVersionSummary(
        version.number, version.htmlBytes, version.note, version.createdAt);
  }

  private static PitchVersionDto version(PitchVersion version) {
    return new PitchVersionDto(
        version.number, version.html, version.htmlBytes, version.note, version.createdAt);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankToNull(String value) {
    return blank(value) ? null : value.trim();
  }
}
