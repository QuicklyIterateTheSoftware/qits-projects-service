package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.AgentCapabilityCatalogueService;
import eu.wohlben.qits.projects.dto.AgentCapabilityCatalogueDto;
import eu.wohlben.qits.projects.dto.AgentHarnessCapabilityDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>The capability catalogue</b>: written by a container reporting what its harness binaries can
 * do, read by the editor to fill its model and effort dropdowns.
 *
 * <p><b>Two doors on one noun, and they face opposite directions.</b> The PUT is a daemon's report,
 * made once when a container starts, and it is the only way a row is ever written — there is no
 * editor door onto this store and there must not be one, because a hand-typed model list is exactly
 * the hardcoded catalogue this feature exists to remove. The GET is the editor's, and it must not
 * spawn a process or wait on a container: the editor is a platform-wide route with no container in
 * front of it, so the answer is one query against what was written earlier.
 *
 * <p><b>The report's shape is the daemons' contract</b>, and it is deliberately the same body they
 * already answer on {@code GET /agents/available} with two members added. A daemon probes its
 * harnesses at boot (one process spawn each, off the request path), answers
 *
 * <pre>
 *   GET /agents/available
 *   -&gt; { "agents": ["CLAUDE","KIMI"], "defaultAgent": "CLAUDE",
 *        "imageVersion": "2026.908.211422",
 *        "capabilities": [ { "harness": "CLAUDE", … }, … ] }
 * </pre>
 *
 * and whoever holds that daemon connection — this service for its own agent container, qits-
 * workspaces for a workspace's — passes the body through to this PUT. Pass-through rather than a
 * translated shape on purpose: a relay that reshapes is a third place the contract can drift, and
 * the extra {@code agents}/{@code defaultAgent} members this door simply ignores.
 *
 * <p>It is the daemon that reports rather than pushing from the container itself because a daemon
 * already knows where the host is and already answers this route; nothing new has to be credentialed
 * inside a workspace.
 *
 * <p><b>Roles.</b> {@code qits:system} for the relaying peer, {@code qits:admin} so an operator can
 * read the same catalogue the editor reads. The report carries no credential — models, effort levels
 * and an auth <em>boolean</em>, never a token — which is what lets the read side be this open.
 */
@Path("/agent-capabilities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:system"})
public class AgentCapabilityController {

  @Inject AgentCapabilityCatalogueService capabilities;

  /**
   * One harness's report, as the daemon produces it.
   *
   * <p>Every field is what the probing side learned, and the three booleans are each distinct from an
   * empty list — see {@code AgentHarnessCapabilityDto}. A daemon that could not probe still reports:
   * it fills the lists from its shipped fallback and sets {@code probeFailed}.
   */
  public record HarnessCapabilityReport(
      @Schema(description = "CLAUDE or KIMI") String harness,
      @Schema(description = "What the binary answers for --version. Empty when unread.")
          String harnessVersion,
      @Schema(description = "Model ids or aliases, in the order the harness enumerated them.")
          List<String> models,
      @Schema(
              description =
                  "False when the harness has no listing command and `models` is a shipped alias"
                      + " set — Claude Code. The editor then leads with the free-text escape.")
          boolean modelsEnumerated,
      @Schema(
              description =
                  "False when the harness has no effort flag at all — Kimi. The editor shows no"
                      + " effort control, not a disabled one carrying the other harness's values.")
          boolean effortSupported,
      @Schema(description = "Effort levels. Ignored when effortSupported is false.")
          List<String> effortLevels,
      @Schema(description = "Whether anybody is signed in on this container's credential volume.")
          boolean authenticated,
      @Schema(description = "What the harness said about that. Never a credential.")
          String authDetail,
      @Schema(description = "True when the probe fell back rather than reading the binary.")
          boolean probeFailed,
      @Schema(description = "Why it fell back. Empty when it did not.") String probeDetail) {}

  /**
   * The whole report from one container.
   *
   * <p>{@code agents} and {@code defaultAgent} may be present and are ignored: this is the {@code
   * GET /agents/available} body passed through unchanged, and a relay that stripped them would be a
   * relay with an opinion.
   */
  public record CapabilityReportRequest(
      @Schema(
              description =
                  "Free text naming the reporting container — a daemon name, a workspace id."
                      + " Display only.")
          String reportedBy,
      @Schema(
              description =
                  "The image build the container runs. Blank is allowed and means the reporter"
                      + " could not name it; reports then share one row per harness.")
          String imageVersion,
      List<HarnessCapabilityReport> capabilities) {

    /**
     * This report as the rows the catalogue stores.
     *
     * <p><b>On the record rather than in the resource method, and that placement is the point.</b>
     * The ingest body <em>is</em> the daemons' contract, and this door's whole argument is that a
     * relay which reshapes it is a third place the contract can drift. There are two relays — this
     * service's own {@code agenthost/AgentCapabilityRelay} and qits-workspaces' — and the in-process
     * one cannot go through {@link #report} at all: the class is {@code @RolesAllowed} and the relay
     * runs on a background thread with no identity, so the interceptor refuses it. A pure function on
     * the body is what lets both arrive at the same rows without a second translation and without
     * either of them holding a credential to call itself with.
     */
    public List<AgentHarnessCapabilityDto> reports() {
      if (capabilities == null) {
        return List.of();
      }
      return capabilities.stream()
          .map(
              r ->
                  new AgentHarnessCapabilityDto(
                      r.harness(),
                      // The image version is one per report, not one per harness: every binary in a
                      // container came out of the same image.
                      imageVersion,
                      r.harnessVersion(),
                      r.models(),
                      r.modelsEnumerated(),
                      r.effortSupported(),
                      r.effortLevels(),
                      r.authenticated(),
                      r.authDetail(),
                      r.probeFailed(),
                      r.probeDetail(),
                      reportedBy,
                      null,
                      false,
                      List.of()))
          .toList();
    }
  }

  /** What was recorded. */
  public record CapabilityReportResponse(
      @Schema(description = "How many (harness, image version) rows the report wrote.")
          int recorded) {}

  /**
   * The catalogue behind the editor's dropdowns: one entry per harness, newest report each, shipped
   * fallback where nothing has reported.
   *
   * <p>Never empty and never a 404. A fresh estate has started no container, and the editor still
   * has to let somebody pick a model, so every harness answers something — flagged {@code shipped}
   * when it is the library's fallback rather than a binary's own answer.
   */
  @GET
  public AgentCapabilityCatalogueDto catalogue() {
    return capabilities.catalogue();
  }

  /**
   * Record one container's report.
   *
   * <p>Upsert per {@code (harness, image version)}: a container restarting on the same build replaces
   * its own row, so the table grows with image builds rather than with container starts. An unknown
   * harness is a 400 naming the known ones — a report this service cannot key is a report it would
   * silently drop.
   */
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  public CapabilityReportResponse report(CapabilityReportRequest request) {
    return new CapabilityReportResponse(
        capabilities.record(request.reportedBy(), request.imageVersion(), request.reports()));
  }
}
