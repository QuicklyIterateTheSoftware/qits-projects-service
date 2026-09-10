package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.epics.control.DossierService;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * The dossier half of the "repository" MCP server — the surface an agent writes the epic's long form
 * on, mounted on the same declared server as {@link EpicMcpTools} for the reason stated there.
 *
 * <p><strong>The epic is the pitch; the dossier is the breakdown.</strong> An epic's description
 * argues that the work is worth doing; a dossier page shows how, with examples, images from the
 * Sketch tab and designs framed inline. An agent refining an epic writes both, and the tool
 * descriptions say which is which so it does not put the breakdown in the pitch.
 *
 * <p><strong>Nobody accepts these writes.</strong> A page is live in the tab the moment it lands,
 * which is why every update carries a {@code version}: a stale one is a refusal to be re-read and
 * retried, never an error to paper over by sending the write again without one. The tool
 * descriptions carry that sentence, because it is the one thing a model gets wrong here.
 *
 * <p>Scope is {@link ProjectScope}'s, exactly as the epic tools resolve theirs: an epic in another
 * project reads as not found, so nothing here says what another project holds.
 *
 * <p>All five write tools are in {@link ReadOnlyRepositoryToolFilter}'s mutating set — on a
 * read-only session they are <em>absent</em>, not present-and-refusing.
 *
 * <p><strong>No {@code @Transactional}</strong>, for the reason {@link EpicMcpTools} gives: these
 * tools straddle two non-XA persistence units and one transaction cannot enlist both.
 */
@ApplicationScoped
@WrapBusinessError
public class DossierMcpTools {

  /** What the audit log records for a write with no forwarded identity. */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject EpicService epicService;

  @Inject DossierService dossier;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  @Inject eu.wohlben.qits.projects.refinementhost.DossierFigures figures;

  // --- Result shapes --------------------------------------------------------

  /** A page as it appears in a list: everything but the markdown itself. */
  public record PageSummary(
      String id, String slug, String title, int position, long version, int bodyBytes) {}

  /** One page with its markdown. */
  public record PageDetail(
      String id,
      String slug,
      String title,
      int position,
      long version,
      String body,
      Instant updatedAt) {}

  /** A copied figure, and the line that renders it. */
  public record DossierFigure(
      String id, String kind, String label, String url, String markdown) {}

  // --- Tools ----------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_dossier_pages",
      description =
          "List the pages of this epic's dossier in reading order, without their text. The epic's"
              + " own description is the pitch — why the work is worth doing; the dossier is the"
              + " breakdown, one page per part, with examples and figures. Start here to see what"
              + " the dossier already covers before adding a page that repeats it, then read the"
              + " one you mean with get_dossier_page.")
  public List<PageSummary> listDossierPages(
      @ToolArg(description = "id of an epic in this project") String epicId) {
    Epic epic = requireEpicInProject(epicId);
    return dossier.listByEpic(epic.id).stream().map(DossierMcpTools::summarize).toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_dossier_page",
      description =
          "Read one dossier page in full: its markdown, and the version you must send back if you"
              + " intend to rewrite it. Read before you write — a page is somebody's prose and the"
              + " last write wins, so writing without reading is how an argument gets flattened.")
  public PageDetail getDossierPage(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "id of a page of this epic's dossier") String pageId) {
    Epic epic = requireEpicInProject(epicId);
    return detail(requireOfEpic(epic.id, pageId));
  }

  @McpServer("repository")
  @Tool(
      name = "put_dossier_page",
      description =
          "Write a dossier page: leave pageId out to add one at the end, or give it to rewrite that"
              + " page. The body is markdown; inline a sketch or a design with inline_figure rather"
              + " than writing a URL by hand. NOBODY ACCEPTS THIS WRITE — the page is live in the"
              + " Dossier tab the moment it lands, which is why an update must carry the version"
              + " get_dossier_page gave you. A version that is no longer current means a person or"
              + " another agent wrote to that page after you read it: re-read the page, fold your"
              + " change into what is there now, and write again. It is a refusal to be retried,"
              + " never an error to work around by sending the write without a version. Fails with"
              + " a message when the epic's scope is frozen — a dossier is refined while the epic"
              + " is still being refined.")
  public PageSummary putDossierPage(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "the page's heading, which also mints its slug at create") String title,
      @ToolArg(description = "the page's markdown") String body,
      @ToolArg(required = false, description = "id of the page to rewrite; omit to add a new one")
          String pageId,
      @ToolArg(
              required = false,
              description = "the version you last read of that page; required when rewriting")
          Long version) {
    Epic epic = requireEpicInProject(epicId);
    DossierPage page;
    if (pageId == null || pageId.isBlank()) {
      page = dossier.create(epic.id, title, body, changedBy());
    } else {
      requireOfEpic(epic.id, pageId);
      page = dossier.update(pageId, title, body, version, changedBy());
    }
    changePublisher.fire(epic.projectId, ProjectChangeHint.Topic.EPICS);
    return summarize(page);
  }

  @McpServer("repository")
  @Tool(
      name = "move_dossier_page",
      description =
          "Put a page at a position in the dossier's reading order, zero-based; the pages between"
              + " where it was and where it lands shift to close the gap. Reading order is the only"
              + " structure a dossier has — there is no nesting, and a page's own headings are its"
              + " second level.")
  public PageSummary moveDossierPage(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "id of a page of this epic's dossier") String pageId,
      @ToolArg(description = "zero-based position to put it at") int position) {
    Epic epic = requireEpicInProject(epicId);
    requireOfEpic(epic.id, pageId);
    PageSummary moved = summarize(dossier.move(pageId, position, changedBy()));
    changePublisher.fire(epic.projectId, ProjectChangeHint.Topic.EPICS);
    return moved;
  }

  @McpServer("repository")
  @Tool(
      name = "remove_dossier_page",
      description =
          "Delete a page of this epic's dossier. Removing somebody's page is not a way to disagree"
              + " with it — rewrite the part that is wrong instead, and delete only what you added"
              + " and no longer mean. A figure nothing else inlines goes with the page.")
  public String removeDossierPage(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "id of a page of this epic's dossier") String pageId) {
    Epic epic = requireEpicInProject(epicId);
    requireOfEpic(epic.id, pageId);
    dossier.delete(pageId, changedBy());
    changePublisher.fire(epic.projectId, ProjectChangeHint.Topic.EPICS);
    return "Removed dossier page " + pageId;
  }

  @McpServer("repository")
  @Tool(
      name = "inline_figure",
      description =
          "Put a sketch or a design into this epic's dossier and get back THE EXACT MARKDOWN LINE"
              + " to paste into a page. Use kind=IMAGE for an image from the Sketch tab (list them"
              + " with the refinement's attachments) and kind=DESIGN for a design (list_designs);"
              + " the line renders as a picture or as a framed document accordingly. Never write a"
              + " figure URL by hand — a hand-written one eventually names something that does not"
              + " exist, or a figure belonging to another epic, and both render as a broken page"
              + " nobody notices until somebody reads it. Inlining copies the figure into the epic,"
              + " so the page keeps rendering after the refinement it came from is discarded, and"
              + " inlining the same figure twice is the same line rather than a second copy.")
  public DossierFigure inlineFigure(
      @ToolArg(description = "id of an epic in this project") String epicId,
      @ToolArg(description = "id of the attachment or design to inline") String sourceId,
      @ToolArg(description = "IMAGE for a sketch, DESIGN for a design") String kind) {
    Epic epic = requireEpicInProject(epicId);
    var inlined = figures.inline(epic.id, sourceId, kind);
    return new DossierFigure(
        inlined.id(), inlined.kind(), inlined.label(), inlined.url(), inlined.markdown());
  }

  // --- Scoping --------------------------------------------------------------

  /** The epic, if it is in the scoped project. One in another project reads as absent. */
  private Epic requireEpicInProject(String epicId) {
    Epic epic = epicService.get(epicId);
    if (!scope.requireProjectId().equals(epic.projectId)) {
      throw new NotFoundException("Epic not found: " + epicId);
    }
    return epic;
  }

  private DossierPage requireOfEpic(String epicId, String pageId) {
    DossierPage page = dossier.get(pageId);
    if (!page.epicId.equals(epicId)) {
      throw new NotFoundException("Dossier page not found: " + pageId);
    }
    return page;
  }

  // --- Plumbing -------------------------------------------------------------

  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  private static PageSummary summarize(DossierPage page) {
    return new PageSummary(
        page.id,
        page.slug,
        page.title,
        page.position,
        page.version,
        page.body == null ? 0 : page.body.getBytes(StandardCharsets.UTF_8).length);
  }

  private static PageDetail detail(DossierPage page) {
    return new PageDetail(
        page.id, page.slug, page.title, page.position, page.version, page.body, page.updatedAt);
  }
}
