package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementDesigns;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * The design half of the "repository" MCP server — the surface a refinement agent reads and writes
 * the epic's HTML designs on, mounted on the same declared server as {@link EpicMcpTools} for the
 * reason stated there.
 *
 * <p><strong>A design is a document, not a proposal.</strong> There is no acceptance step and no
 * ACTIVE row: the agent and the person write and rewrite the same rows, the way they both write the
 * epic's description, and what freezes the draft is the epic's own {@code REFINING →
 * IMPLEMENTATION} transition. {@code version} carries the whole of the safety — a write composed
 * against an older read is refused rather than merged, and the tool description says so, because an
 * agent that papers over that refusal overwrites somebody's edit.
 *
 * <p>Scope is the epic's refinement, resolved from {@link ProjectScope} exactly as the epic tools
 * resolve theirs: an epic in another project, and an epic with no refinement open, are the same
 * answer — nothing here says what another project holds.
 *
 * <p><strong>No {@code @Transactional}</strong>, for the identical reason {@link EpicMcpTools}
 * carries none: these tools straddle two non-XA persistence units, and one transaction cannot
 * enlist both.
 */
@ApplicationScoped
@WrapBusinessError
public class RefinementDesignMcpTools {

  /** What a write with no forwarded identity records as its author. */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject RefinementRepository refinements;

  @Inject RefinementDesigns designs;

  @Inject SecurityIdentity identity;

  // --- Result shapes --------------------------------------------------------

  /** A design as it appears in a list: everything but the document itself. */
  public record DesignSummary(
      String id,
      String title,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  /** One design with its whole document. */
  public record DesignDetail(
      String id,
      String title,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      String html) {}

  // --- Tools ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_designs",
      description =
          "List the HTML designs kept with this epic's refinement, oldest first, without their"
              + " documents. These are the documents the person sees in the Design tab; there is no"
              + " draft state and nothing here is waiting to be accepted. Read a design with"
              + " get_design before rewriting it, so what you send back is a rewrite of what"
              + " actually exists and carries the version you read.")
  public List<DesignSummary> listDesigns(
      @ToolArg(description = "id of an epic in this project") String epicId) {
    Refinement refinement = requireRefinementOfEpicInProject(epicId);
    return designs.list(refinement.id).stream()
        .map(RefinementDesignMcpTools::summarize)
        .toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_design",
      description =
          "Read one design in full: the complete self-contained HTML document, styles inline and"
              + " no scripts. This is the shape a design has — send the same shape back when you"
              + " rewrite it, together with the version this read gave you.")
  public DesignDetail getDesign(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "id of a design of this epic's refinement") String designId) {
    Refinement refinement = requireRefinementOfEpicInProject(epicId);
    RefinementDesign row = designs.get(refinement.id, designId);
    return new DesignDetail(
        row.id,
        row.title,
        row.sourceRoute,
        row.htmlBytes,
        row.truncated,
        row.version,
        row.createdBy,
        row.createdAt,
        row.updatedAt,
        row.html);
  }

  @McpServer("repository")
  @Tool(
      name = "put_design",
      description =
          "Write a design of this epic's refinement: leave designId out to add one, or give it to"
              + " rewrite that design in place. Send a COMPLETE HTML document with inline styles,"
              + " the same shape get_design returns. Nobody accepts this — it is live in the Design"
              + " tab the moment it lands, and a person may rename or delete it afterwards; the"
              + " gate on the plan is the epic's own move from refining to implementation, not"
              + " anything you do here. When rewriting, pass the version get_design gave you: a"
              + " version that is no longer current means somebody wrote to that design after you"
              + " read it, and the refusal is to be re-read and retried, never worked around by"
              + " sending it again without one. Fails with a message when the epic has no open"
              + " refinement.")
  public DesignSummary putDesign(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "short label for the Design tab's list") String title,
      @ToolArg(description = "the complete HTML document, styles inline, no scripts") String html,
      @ToolArg(
              required = false,
              description = "id of the design to rewrite; omit to add a new one")
          String designId,
      @ToolArg(
              required = false,
              description = "the version you last read of that design; required when rewriting")
          Long version) {
    Refinement refinement = requireRefinementOfEpicInProject(epicId);
    return summarize(
        designs.put(refinement.id, designId, title, html, version, null, false, changedBy()));
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * The refinement of {@code epicId} within the scoped project. An epic in another project and an
   * epic with no refinement open answer the same way — the model is told nothing about what other
   * projects hold.
   */
  private Refinement requireRefinementOfEpicInProject(String epicId) {
    Refinement refinement =
        QuarkusTransaction.requiringNew()
            .call(() -> refinements.findByEpic(epicId))
            .orElseThrow(() -> noRefinement(epicId));
    if (!scope.requireProjectId().equals(refinement.projectId)) {
      throw noRefinement(epicId);
    }
    return refinement;
  }

  private static NotFoundException noRefinement(String epicId) {
    return new NotFoundException("No refinement is open for epic " + epicId);
  }

  // --- Plumbing -------------------------------------------------------------

  /** The row's {@code created_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  private static DesignSummary summarize(RefinementDesign row) {
    return new DesignSummary(
        row.id,
        row.title,
        row.sourceRoute,
        row.htmlBytes,
        row.truncated,
        row.version,
        row.createdBy,
        row.createdAt,
        row.updatedAt);
  }
}
