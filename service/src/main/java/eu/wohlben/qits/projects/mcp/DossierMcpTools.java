package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.epics.control.DossierService;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.entity.DossierOwner;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.epics.error.BadRequestException;
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
 * <p><strong>The epic is the pitch; the dossier is the implementation reference.</strong> An epic's
 * description argues that the work is worth doing; a dossier page is read by the agent implementing
 * the epic — {@code EpicDispatchController.instruction(...)} sends it here for the detail the epic
 * leaves out — so a page records what changes and how it works: paths, names, commands, file lists,
 * exact values, with examples, images from the Sketch tab and designs framed inline. An agent
 * refining an epic writes both, and the tool descriptions say which is which so the argument stays
 * in the pitch and the page stays something an implementer can build from.
 *
 * <p><strong>Nobody accepts these writes.</strong> A page is live in the tab the moment it lands,
 * which is why every update carries a {@code version}: a stale one is a refusal to be re-read and
 * retried, never an error to paper over by sending the write again without one. The tool
 * descriptions carry that sentence, because it is the one thing a model gets wrong here.
 *
 * <p><strong>A page belongs to an epic OR to a ticket, and the five tools stay five.</strong> Every
 * tool takes {@code epicId} and {@code ticketId}, both optional and <b>exactly one required</b>; a
 * call naming both or neither is refused with a sentence saying which. A parallel set of five ticket
 * tools would double a surface the model has to choose from and would write the same rules twice,
 * and the rules genuinely are the same — only the freeze differs, which is the service's to apply:
 * a ticket commits to no scope, so its pages are writable at every status, while an epic's dossier
 * freezes with the plan. A ticket's dossier is what the refine phase writes when <b>the ticket's own
 * body cannot hold it</b>, and the descriptions say so because the refine prompt relies on it.
 *
 * <p><strong>{@code inline_figure} is the one tool that stays epic-only</strong>, and by absence of
 * an argument rather than by a refusal: {@code dossier_asset} copies the refining route's sketches
 * and designs, a route a ticket has not got.
 *
 * <p>Scope is {@link ProjectScope}'s, exactly as the epic tools resolve theirs: an epic — or a
 * ticket — in another project reads as not found, so nothing here says what another project holds.
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

  @Inject TicketService ticketService;

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
          "List the pages of an epic's or a ticket's dossier in reading order, without their text."
              + " Give exactly one of epicId and ticketId. The owner's own prose is the short form —"
              + " an epic's description is the pitch, a ticket's is what refinement concluded — and"
              + " the dossier is what the agent doing the work builds from, one page per part,"
              + " recording what changes and how it works: paths, names, exact values, examples and"
              + " figures. A DOSSIER PAGE IS FOR WHAT THE TICKET'S OWN BODY CANNOT HOLD. Start here"
              + " to see what the dossier already covers before adding a page that repeats it, then"
              + " read the one you mean with get_dossier_page.")
  public List<PageSummary> listDossierPages(
      @ToolArg(required = false, description = "id of an epic in this project") String epicId,
      @ToolArg(required = false, description = "id of a ticket in this project") String ticketId) {
    Owner owner = requireOwnerInProject(epicId, ticketId);
    return dossier.listByOwner(owner.owner()).stream().map(DossierMcpTools::summarize).toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_dossier_page",
      description =
          "Read one dossier page in full: its markdown, and the version you must send back if you"
              + " intend to rewrite it. Give exactly one of epicId and ticketId — whichever owns the"
              + " page. Read before you write — a page is somebody's prose and the last write wins,"
              + " so writing without reading is how an argument gets flattened.")
  public PageDetail getDossierPage(
      @ToolArg(description = "id of a page of that dossier") String pageId,
      @ToolArg(required = false, description = "id of an epic in this project") String epicId,
      @ToolArg(required = false, description = "id of a ticket in this project") String ticketId) {
    Owner owner = requireOwnerInProject(epicId, ticketId);
    return detail(requireOfOwner(owner.owner(), pageId));
  }

  @McpServer("repository")
  @Tool(
      name = "put_dossier_page",
      description =
          "Write a dossier page onto an epic or onto a ticket: give exactly one of epicId and"
              + " ticketId, and leave pageId out to add a page at the end or give it to rewrite that"
              + " page. The page is read by the agent doing the work, so write what changes and how"
              + " it works: paths, names, commands, file lists, exact values. A DOSSIER PAGE IS FOR"
              + " WHAT THE TICKET'S OWN BODY CANNOT HOLD — an error scenario crossing several"
              + " services, a sequence worth a figure; anything that fits in the ticket's"
              + " description belongs there instead. On an epic the same split is the pitch against"
              + " the breakdown: the problem, the argument for the approach and why it is worth"
              + " doing go in the epic's description, and a page that restates them gives the"
              + " implementer nothing to build from. The body is markdown; on an EPIC's dossier"
              + " inline a sketch or a design with inline_figure rather than writing a URL by hand,"
              + " and note that a ticket's dossier has no figures at all. NOBODY ACCEPTS THIS"
              + " WRITE — the page is live in the Dossier tab the moment it lands, which is why an"
              + " update must carry the version get_dossier_page gave you. A version that is no"
              + " longer current means a person or another agent wrote to that page after you read"
              + " it: re-read the page, fold your change into what is there now, and write again."
              + " It is a refusal to be retried, never an error to work around by sending the write"
              + " without a version. On an epic it fails with a message when the scope is frozen —"
              + " an epic's dossier is refined while the epic is; a ticket's dossier is writable at"
              + " every status it has.")
  public PageSummary putDossierPage(
      @ToolArg(description = "the page's heading, which also mints its slug at create") String title,
      @ToolArg(description = "the page's markdown") String body,
      @ToolArg(required = false, description = "id of an epic in this project") String epicId,
      @ToolArg(required = false, description = "id of a ticket in this project") String ticketId,
      @ToolArg(required = false, description = "id of the page to rewrite; omit to add a new one")
          String pageId,
      @ToolArg(
              required = false,
              description = "the version you last read of that page; required when rewriting")
          Long version) {
    Owner owner = requireOwnerInProject(epicId, ticketId);
    DossierPage page;
    if (pageId == null || pageId.isBlank()) {
      page = dossier.create(owner.owner(), title, body, changedBy());
    } else {
      requireOfOwner(owner.owner(), pageId);
      page = dossier.update(pageId, title, body, version, changedBy());
    }
    changePublisher.fire(owner.projectId(), owner.topic());
    return summarize(page);
  }

  @McpServer("repository")
  @Tool(
      name = "move_dossier_page",
      description =
          "Put a page at a position in the dossier's reading order, zero-based; the pages between"
              + " where it was and where it lands shift to close the gap. Give exactly one of"
              + " epicId and ticketId — whichever owns the page. Reading order is the only"
              + " structure a dossier has — there is no nesting, and a page's own headings are its"
              + " second level.")
  public PageSummary moveDossierPage(
      @ToolArg(description = "id of a page of that dossier") String pageId,
      @ToolArg(description = "zero-based position to put it at") int position,
      @ToolArg(required = false, description = "id of an epic in this project") String epicId,
      @ToolArg(required = false, description = "id of a ticket in this project") String ticketId) {
    Owner owner = requireOwnerInProject(epicId, ticketId);
    requireOfOwner(owner.owner(), pageId);
    PageSummary moved = summarize(dossier.move(pageId, position, changedBy()));
    changePublisher.fire(owner.projectId(), owner.topic());
    return moved;
  }

  @McpServer("repository")
  @Tool(
      name = "remove_dossier_page",
      description =
          "Delete a page of an epic's or a ticket's dossier; give exactly one of epicId and"
              + " ticketId. Removing somebody's page is not a way to disagree with it — rewrite the"
              + " part that is wrong instead, and delete only what you added and no longer mean. On"
              + " an epic's dossier, a figure nothing else inlines goes with the page.")
  public String removeDossierPage(
      @ToolArg(description = "id of a page of that dossier") String pageId,
      @ToolArg(required = false, description = "id of an epic in this project") String epicId,
      @ToolArg(required = false, description = "id of a ticket in this project") String ticketId) {
    Owner owner = requireOwnerInProject(epicId, ticketId);
    requireOfOwner(owner.owner(), pageId);
    dossier.delete(pageId, changedBy());
    changePublisher.fire(owner.projectId(), owner.topic());
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
    WorkEntity epic = requireEpicInProject(epicId);
    var inlined = figures.inline(epic.id, sourceId, kind);
    return new DossierFigure(
        inlined.id(), inlined.kind(), inlined.label(), inlined.url(), inlined.markdown());
  }

  // --- Scoping --------------------------------------------------------------

  /** The epic, if it is in the scoped project. One in another project reads as absent. */
  private WorkEntity requireEpicInProject(String epicId) {
    WorkEntity epic = epicService.get(epicId);
    if (!scope.requireProjectId().equals(epic.projectId)) {
      throw new NotFoundException("Epic not found: " + epicId);
    }
    return epic;
  }

  /** The ticket, checked back to the session's project the same way, and absent otherwise. */
  private WorkEntity requireTicketInProject(String ticketId) {
    WorkEntity ticket = ticketService.get(ticketId);
    if (!scope.requireProjectId().equals(ticket.projectId)) {
      throw new NotFoundException("Ticket not found: " + ticketId);
    }
    return ticket;
  }

  /** A resolved owner, with what a change hint needs: whose channel, and which topic. */
  private record Owner(DossierOwner owner, String projectId, ProjectChangeHint.Topic topic) {}

  /**
   * Exactly one of the two ids, resolved and checked back to the session's project.
   *
   * <p>Both and neither are refused rather than guessed, and the two refusals say different things
   * on purpose: a model that named two owners has to be told to drop one, and a model that named
   * none has to be told the tool cannot pick. A silent default — "epic if you gave one" — would put
   * a ticket's refinement on an epic's dossier with nothing reporting it.
   */
  private Owner requireOwnerInProject(String epicId, String ticketId) {
    boolean hasEpic = epicId != null && !epicId.isBlank();
    boolean hasTicket = ticketId != null && !ticketId.isBlank();
    if (hasEpic && hasTicket) {
      throw new BadRequestException(
          "Give either epicId or ticketId, never both: a dossier page belongs to one epic or to one"
              + " ticket, and this call names two owners.");
    }
    if (!hasEpic && !hasTicket) {
      throw new BadRequestException(
          "Give either epicId or ticketId: a dossier page belongs to an epic or to a ticket, and"
              + " this call names neither.");
    }
    if (hasEpic) {
      WorkEntity epic = requireEpicInProject(epicId);
      return new Owner(
          DossierOwner.epic(epic.id), epic.projectId, ProjectChangeHint.Topic.EPICS);
    }
    WorkEntity ticket = requireTicketInProject(ticketId);
    return new Owner(
        DossierOwner.ticket(ticket.id), ticket.projectId, ProjectChangeHint.Topic.TICKETS);
  }

  /** The page, if that owner owns it. One of another owner's reads as absent, never as forbidden. */
  private DossierPage requireOfOwner(DossierOwner owner, String pageId) {
    DossierPage page = dossier.get(pageId);
    if (!owner.equals(DossierOwner.of(page))) {
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
