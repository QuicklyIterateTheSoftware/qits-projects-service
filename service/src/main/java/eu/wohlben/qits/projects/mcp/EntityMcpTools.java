package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.epics.control.EntityCatalogService;
import eu.wohlben.qits.epics.control.EntityTransition;
import eu.wohlben.qits.epics.control.EntityTransitionService;
import eu.wohlben.qits.epics.control.TransitionedEntity;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>unified-entity</b> half of the "repository" MCP server: one tool that restates part of a
 * project's plan as a whole, and one tool that reads the plan back in the vocabulary that tool
 * takes. Mounted on the same declared server as {@link RepositoryMcpTools}, {@link EpicMcpTools} and
 * {@link TicketMcpTools} ({@code /projects/mcp}) for the reason {@code EpicMcpTools} gives — the
 * server's name is its contract with qits-workspace-daemon and nothing about it moves.
 *
 * <h2>Why a class of its own, and not a method on {@code EpicMcpTools}</h2>
 *
 * <p>This is {@code EntityTransitionController}'s argument one layer up. <b>The unified entity is
 * the noun.</b> The four archetypes are one table discriminated by a column and the whole subject
 * here is a row changing which of them it is — so hanging the tool off the epic surface or the
 * ticket surface would file it under one of the two ends it moves between, and an agent looking for
 * "how do I restructure this plan" would have to already know the answer to find it. The REST
 * surface answers that question with a resource of its own ({@code /projects/api/entities}); the MCP
 * surface answers it with a tool class of its own, in the same package as its three neighbours and
 * on the same server.
 *
 * <p>It also keeps the promise the epic made: <b>the existing tools keep their names and their
 * shapes.</b> An agent mid-refinement cannot tell this shipped — {@code get_epic} still answers an
 * {@code EpicDetail} with the same fields in the same order, {@code list_tickets} still answers its
 * own summary — and {@code EpicMcpToolsTest}, {@code TicketMcpToolsTest} and {@code
 * DossierMcpToolsTest} pass with their assertions unchanged. What the read side needed in order to
 * keep up is <b>added</b> as {@code list_entities} rather than bolted onto one of those.
 *
 * <p>Scope comes from {@link ProjectScope} (the {@code X-QITS-Project} header), never from a tool
 * argument, and <b>every entity a request names</b> — every key of the map and every {@code
 * membership.parent} in it — is checked back to that project. One that belongs to another project
 * reads as not found, exactly as an epic or a ticket elsewhere does, so a transition cannot reach
 * across a project boundary even though the operation's whole subject is moving rows about.
 *
 * <p>{@link WrapBusinessError} turns what the transition throws — one {@code BadRequestException}
 * carrying every violation from all three validation layers — into a tool result with {@code
 * isError=true} carrying that message. That matters more here than anywhere else on this server:
 * the refusal <em>is</em> the instruction, and a model that reads "a TICKET requires ticket type" can
 * fix its statement inside the same turn, where a JSON-RPC protocol error would kill it.
 *
 * <p><b>No {@code @Transactional} here</b>, for the reason {@link EpicMcpTools} states: these tools
 * straddle two persistence units and Narayana enlists only one local resource per transaction. The
 * transition opens its own, inside {@code WritePatience}, exactly as it does for the REST route.
 */
@ApplicationScoped
@WrapBusinessError
public class EntityMcpTools {

  /**
   * What the audit log records for a write with no forwarded identity. An MCP session is a machine
   * caller; naming it beats a null {@code changed_by} that reads as "unknown human".
   */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject EntityCatalogService catalog;

  @Inject EntityTransitionService transitions;

  @Inject ProjectChangePublisher changePublisher;

  /**
   * Fills {@code qualifiedId} on everything this class answers. One slug lookup per tool call — see
   * {@link eu.wohlben.qits.projects.api.QualifiedEntityIds}, which is also where the reason the
   * {@code epics} module leaves the field null is written down.
   */
  @Inject eu.wohlben.qits.projects.api.QualifiedEntityIds qualifiedIds;

  @Inject SecurityIdentity identity;

  // --- The read side --------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_entities",
      description =
          "Read this project's whole planning tree as it really is: one flat list of entries, each"
              + " carrying its archetype (EPIC, TICKET, FEATURE or TASK), its parent and its"
              + " position among that parent's children. Roots come first, each followed by its"
              + " descendants in order. THIS IS THE READ THAT GOES WITH transition_entities: an"
              + " entry there is judged against its target archetype and names its parent, so you"
              + " cannot state a correct one without seeing the current archetype and the current"
              + " membership — which get_epic, get_ticket, list_epics and list_tickets do not"
              + " report. Use those for an epic's or a ticket's ordinary detail; use this one before"
              + " you move, promote, demote or re-parent anything. The fields it answers are the"
              + " same ones transition_entities takes, so an entry you read here is an entry you can"
              + " restate there.")
  public List<TransitionedEntity> listEntities(
      @ToolArg(
              required = false,
              description =
                  "keep only entries of this archetype: EPIC, TICKET, FEATURE or TASK. Omit for the"
                      + " whole tree, which is what you want before a restructuring — the entries"
                      + " you are not moving are the ones that say what is already taken.")
          String archetype) {
    List<TransitionedEntity> all = catalog.listByProject(scope.requireProjectId());
    if (archetype != null && !archetype.isBlank()) {
      String wanted = archetype.trim().toUpperCase(java.util.Locale.ROOT);
      all = all.stream().filter(entity -> entity.archetype().name().equals(wanted)).toList();
    }
    // Filtered first, then qualified: one slug lookup for whatever survives, never one per entry.
    return qualifiedIds.qualifyEntities(all);
  }

  // --- The write ------------------------------------------------------------

  /**
   * <b>One call, a map of entries, atomic — and it has to be one call.</b>
   *
   * <p>An agent restructuring a refinement through several single-entity tool calls is precisely the
   * sequence of illegal intermediate states this design rules out: re-archetype a feature first and
   * there is an epic under an epic, reparent it first and there is a feature at the root, move its
   * tasks first and they hang under something still shaped as a feature. Every one of those is
   * refused by a rule that is correct, and the whole is correct. A tool that could only move one
   * entity would reintroduce at the agent surface the exact problem {@code EntityTransitionService}
   * exists to prevent, so there is deliberately no single-entity spelling of this operation.
   */
  @McpServer("repository")
  @Tool(
      name = "transition_entities",
      description =
          "Restate part of this project's plan as a whole: one call carrying a map of entity id to"
              + " the FULL state that entity is to have afterwards. Everything in the map is judged"
              + " together and written in one transaction, so a move that has no legal order —"
              + " promoting a feature to an epic while its tasks become features under it — is one"
              + " call here and is impossible as a sequence of separate edits. Read list_entities"
              + " first; the entries it answers are the entries this takes.\n"
              + "\n"
              + "THE VALUE IS THE ENTITY IN FULL, NOT A DIFF. Every property you want the entity to"
              + " have afterwards must be in the entry, including the ones you are not changing."
              + " AN OMITTED PROPERTY IS CLEARED. That is most expensive on a demotion: turning a"
              + " TASK into a FEATURE and not restating its description clears the description, and"
              + " turning a TICKET into a FEATURE clears its impetus, its assignee and its type"
              + " because a FEATURE has no slot for them — which is intended, but only if you meant"
              + " it. Two properties are the server's and are never stated: the slug is kept as it"
              + " is (it names branches and URLs that already exist), and the reporter is kept where"
              + " the new archetype has a place for one and dropped where it has not.\n"
              + "\n"
              + "EVERY ID MUST ALREADY EXIST. Nothing is created and nothing is deleted here — not"
              + " by an id in the map and not by a membership.parent. Create first with propose_epic"
              + " / add_feature / add_task / create_ticket, then transition. An id that names no"
              + " entity is refused, never invented.\n"
              + "\n"
              + "A PLAIN EDIT IS A MAP OF ONE. Restating one entity's full state is an ordinary use"
              + " of this tool and needs no second thought — retitle an epic, move one task between"
              + " features, change one entity's kind. The per-entity tools (update_epic,"
              + " update_feature, update_task, update_ticket) are still there for a partial edit"
              + " that changes nothing structural, but anything touching an archetype or a"
              + " membership belongs here even when it touches exactly one row.\n"
              + "\n"
              + "IF IT IS REFUSED, READ THE REFUSAL. A rejection naming missing properties is the"
              + " archetype gate: each kind declares what it requires — an EPIC a title and a"
              + " status, a TICKET a title, a type and a status, a FEATURE a title, a TASK a title"
              + " and a repository — and a property the target kind has no slot for is refused"
              + " rather than quietly dropped. The fix is to supply what it names and call again"
              + " with a corrected map; retrying the same map will be refused the same way. Every"
              + " complaint about the whole request comes back at once, so fix all of them"
              + " together. Nothing is written when anything is refused.")
  public Map<String, TransitionedEntity> transitionEntities(
      @ToolArg(
              description =
                  "entity id -> that entity's full target state. Each value takes: archetype (EPIC,"
                      + " TICKET, FEATURE or TASK — required, and what the entry is judged as);"
                      + " membership ({\"parent\": \"<id>\", \"position\": 0} — omit it or state a"
                      + " null parent to make the entity a root, and a parent may be another entity"
                      + " in this same map); title; description; status (required for an EPIC or a"
                      + " TICKET, which are the kinds that have one — REFINING/IMPLEMENTATION/"
                      + "IMPLEMENTED/SUPERSEDED/ABANDONED for an epic, REPORTED/REFINED/IMPLEMENTED/"
                      + "VERIFIED/DONE for a ticket); ticketType (BUG or IMPROVEMENT); impetus;"
                      + " assignee; repositoryId (a TASK's repository); implementedAt; dependsOn (a"
                      + " sibling to do first, never nesting). Position is clamped to the legal"
                      + " range rather than refused, and an entry with a parent and no position is"
                      + " appended.")
          Map<String, EntityTransition> entities) {
    String projectId = scope.requireProjectId();
    requireEveryNamedEntityInProject(projectId, entities);

    Map<String, TransitionedEntity> written = transitions.transition(entities, changedBy());
    announce(projectId);
    // The map keeps its keys; one slug lookup for the whole batch.
    return qualifiedIds.qualifyEntities(written);
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * <b>Every entity the request names must be in the caller's project</b> — each key of the map, and
   * each {@code membership.parent} it points at, whether or not that parent is itself an entry.
   *
   * <p>An entity of another project reads as not found rather than as forbidden, the rule the epic
   * and ticket surfaces already apply: the model is told nothing about what other projects hold. A
   * parent is checked for the same reason the keys are — a reparent onto somebody else's epic is a
   * cross-project reach expressed as a membership rather than as a key.
   *
   * <p><b>An id that names nothing at all is deliberately NOT refused here.</b> It falls through to
   * {@code EntityTransitionService}, which collects it with every other complaint about the request
   * and answers one refusal naming all of them. Refusing it here would hand the model one wrong id
   * per round trip, which is the failure mode the collected violations exist to avoid — and it is
   * worse here than anywhere else, because the fixes are moves.
   */
  private void requireEveryNamedEntityInProject(
      String projectId, Map<String, EntityTransition> requested) {
    if (requested == null || requested.isEmpty()) {
      return; // the service refuses an empty transition, and says so better than this could
    }
    Set<String> named = new LinkedHashSet<>();
    for (Map.Entry<String, EntityTransition> entry : requested.entrySet()) {
      if (entry.getKey() != null && !entry.getKey().isBlank()) {
        named.add(entry.getKey());
      }
      if (entry.getValue() != null && entry.getValue().parent() != null) {
        named.add(entry.getValue().parent());
      }
    }

    Map<String, TransitionedEntity> known = catalog.byIds(named);
    for (String id : named) {
      TransitionedEntity entity = known.get(id);
      if (entity != null && !projectId.equals(entity.projectId())) {
        throw new NotFoundException("Entity not found in this project: " + id);
      }
    }
  }

  // --- Plumbing -------------------------------------------------------------

  /**
   * Tell the project's browsers to re-read. <b>Both topics</b>, exactly as {@code
   * EntityTransitionController} fires them: one batch may well have moved a ticket and an epic tree
   * at once, and a client subscribed to one of the two channels would otherwise draw a stale board.
   * One project, because every entity the request named was just checked into this one.
   */
  private void announce(String projectId) {
    changePublisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    changePublisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }
}
