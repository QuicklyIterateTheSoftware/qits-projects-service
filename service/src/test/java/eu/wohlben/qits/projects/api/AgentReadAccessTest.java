package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The user's ruling, read off the annotations: <b>an agent reads everywhere, and writes where the
 * MCP tool surface already serves the same write</b> — bound, at the door itself, to the agent's own
 * project or its own work. Signing off, moving an epic through its lifecycle and every delete but
 * one stay a person's.
 *
 * <p>So the rule this class asserts has three clauses and each is checked positively:
 *
 * <ul>
 *   <li>every GET and HEAD on every listed controller admits {@code qits:agent};
 *   <li>a write admits it <b>if and only if</b> it is named in one of the two declared groups below
 *       — the release-request writes and the entity writes;
 *   <li>the four writes that must stay {@code qits:admin} alone are named in {@link
 *       #ADMIN_ONLY_WRITES} and asserted <em>not</em> to admit the agent, so a later widening trips
 *       this test rather than sliding past it as one more line in an opt-out set.
 * </ul>
 *
 * <p>Two named groups rather than one list, because they are two different arguments and a single
 * twenty-two-line set stops expressing either. Each group's javadoc carries its own reasoning; what
 * they share is that a role list is only half the door, and the other half — which rows the caller
 * may reach — is asserted in {@code ReleaseRequestAgentBoundsTest} and {@code
 * eu.wohlben.qits.entities.api.EntityAgentBoundsTest}.
 *
 * <p><b>The list of classes is explicit, so a controller is only checked if it is here</b>, and
 * every controller on this surface is. Note that two of them declare no read at all ({@code
 * TicketCommentController}, {@code EntityTransitionController}), which is why the per-class sanity
 * check below asks for a <em>route</em> rather than for a read: a class with neither is a name that
 * no longer resolves to a door, and that is the thing worth failing on.
 */
class AgentReadAccessTest {

  private static final String AGENT = "qits:agent";

  private static final List<Class<?>> CLASSES =
      List.of(
          eu.wohlben.qits.entities.api.DossierAssetController.class,
          eu.wohlben.qits.entities.api.DossierController.class,
          eu.wohlben.qits.entities.api.EntityArchetypesController.class,
          eu.wohlben.qits.entities.api.EntityTransitionController.class,
          eu.wohlben.qits.entities.api.EpicController.class,
          eu.wohlben.qits.entities.api.FeatureController.class,
          eu.wohlben.qits.entities.api.ProjectEpicsController.class,
          eu.wohlben.qits.entities.api.ProjectTicketsController.class,
          eu.wohlben.qits.entities.api.TaskController.class,
          eu.wohlben.qits.entities.api.TicketCommentController.class,
          eu.wohlben.qits.entities.api.TicketController.class,
          eu.wohlben.qits.entities.api.TicketDossierController.class,
          AgentCapabilityController.class,
          AgentConfigurationController.class,
          AgentContainerController.class,
          AgentMcpCatalogController.class,
          AgentSurfaceConfigurationController.class,
          PinsController.class,
          ProjectController.class,
          ProjectEventsController.class,
          ProjectRefinementsController.class,
          ProjectReleaseRequestsController.class,
          RefinementController.class,
          RefinementDesignController.class,
          RefinementEventsController.class,
          RefinementPromptAttachmentController.class,
          RefinementPromptDraftController.class,
          ReleaseRequestController.class,
          RepositoryController.class,
          TechnicalProcessEventsController.class);

  /**
   * <b>The release-request writes an agent reaches.</b> The first four are bound to the agent's
   * project and {@code git_refs}; the fifth, {@code rerunPhase}, is deliberately unbound — it
   * decides nothing, so there is nothing to bind. {@code approve} and {@code decline} are not here
   * and must not arrive: those are the sign-off, and that distinction is what this set is a list of.
   */
  private static final Set<String> RELEASE_REQUEST_AGENT_WRITES =
      Set.of(
          "ReleaseRequestController.create",
          "ReleaseRequestController.addSource",
          "ReleaseRequestController.setSourcePriority",
          "ReleaseRequestController.withdraw",
          "ReleaseRequestController.rerunPhase");

  /**
   * <b>The entity writes an agent reaches, and the MCP tool surface is the test for membership.</b>
   * Each one of these is a write the {@code repository} MCP server already performs for an agent
   * holding no credential at all — {@code propose_epic}, {@code update_epic}, {@code add_feature},
   * {@code update_feature}, {@code remove_feature}, {@code add_task}, {@code update_task}, {@code
   * mark_task_implemented}, {@code remove_task}, {@code create_ticket}, {@code update_ticket},
   * {@code transition_ticket}, {@code add_ticket_comment}, {@code update_ticket_comment}, the three
   * owner-agnostic dossier tools, {@code inline_figure} and {@code transition_entities} — so
   * refusing it at the REST door was an inconsistency rather than a boundary.
   *
   * <p>Every one of them is bound to the agent's own project by {@code EntitiesAgentAccess}, before
   * the write, and the id resolution that binding rides on is what keeps an id naming nothing a 404.
   * A name added here without that binding is the defect this set cannot see; {@code
   * EntityAgentBoundsTest} is where it would be caught.
   */
  private static final Set<String> ENTITY_AGENT_WRITES =
      Set.of(
          "ProjectEpicsController.create",
          "EpicController.update",
          "EpicController.createFeature",
          "FeatureController.update",
          "FeatureController.delete",
          "FeatureController.createTask",
          "TaskController.update",
          "TaskController.delete",
          "ProjectTicketsController.create",
          "TicketController.update",
          "TicketController.transition",
          // The block door, granted by the same rule and reached the same way: block_ticket and
          // unblock_ticket are on the repository MCP server, which serves an agent with no
          // credential at all, so refusing at the REST door what is handed over one package away
          // would be the inconsistency this set exists to prevent. It is also the agent working
          // the phase that knows the phase is stuck.
          "TicketController.setBlocked",
          "TicketController.createComment",
          "TicketCommentController.update",
          "DossierController.create",
          "DossierController.write",
          "DossierController.move",
          "DossierController.delete",
          "TicketDossierController.create",
          "TicketDossierController.write",
          "TicketDossierController.move",
          "TicketDossierController.delete",
          "DossierAssetController.inline",
          "EntityTransitionController.transition");

  /** The union, which is what the per-class rule is read against. */
  private static final Set<String> AGENT_WRITES = union(RELEASE_REQUEST_AGENT_WRITES, ENTITY_AGENT_WRITES);

  /**
   * <b>The writes that must stay {@code qits:admin} alone, asserted positively.</b> An epic's
   * lifecycle move and its delete are decisions about scope — freezing a plan or resolving one — and
   * {@code EpicMcpTools} deliberately exposes no lifecycle move at all. Deleting a ticket or a
   * comment is on neither surface, for the reason the ticket section of {@code CLAUDE.md} gives: an
   * agent that could delete what it disagrees with could erase the record of its own mistake.
   *
   * <p>{@code FeatureController.delete} and {@code TaskController.delete} are deliberately NOT here
   * — {@code remove_feature} and {@code remove_task} are tools, so those two deletes are granted by
   * the very rule that refuses these four.
   */
  private static final Set<String> ADMIN_ONLY_WRITES =
      Set.of(
          "EpicController.transition",
          "EpicController.delete",
          "TicketController.delete",
          "TicketCommentController.delete");

  @TestFactory
  Stream<DynamicTest> anAgentReadsEverythingAndWritesOnlyTheDeclaredSet() {
    return CLASSES.stream()
        .map(
            type ->
                DynamicTest.dynamicTest(
                    type.getSimpleName(),
                    () -> {
                      boolean anyRoute = false;
                      for (Method method : type.getDeclaredMethods()) {
                        String name = type.getSimpleName() + "." + method.getName();
                        if (isRead(method)) {
                          anyRoute = true;
                          assertTrue(roles(type, method).contains(AGENT), name + " is a read");
                        } else if (isWrite(method)) {
                          anyRoute = true;
                          if (AGENT_WRITES.contains(name)) {
                            assertTrue(
                                roles(type, method).contains(AGENT),
                                name + " is a declared agent write");
                          } else {
                            assertFalse(roles(type, method).contains(AGENT), name + " is a write");
                          }
                        }
                      }
                      assertTrue(anyRoute, type.getSimpleName() + " declares no route at all");
                    }));
  }

  /**
   * The four admin-only writes, checked by name rather than by absence from a set: a widening that
   * added {@code qits:agent} to one of them would otherwise only have to be added to {@link
   * #AGENT_WRITES} beside it and nothing would object.
   */
  @Test
  void theSignOffAndTheDeletesStayAPersons() {
    for (String name : ADMIN_ONLY_WRITES) {
      assertFalse(
          AGENT_WRITES.contains(name), name + " is admin-only and must not be a declared agent write");
      Method method = declared(name);
      assertTrue(isWrite(method), name + " must be a write route");
      assertFalse(
          roles(method.getDeclaringClass(), method).contains(AGENT),
          name + " must not admit " + AGENT);
    }
  }

  /**
   * Every declared agent write must exist and be a write. A renamed or deleted method would
   * otherwise leave a name in the set above that reads as a grant and guards nothing — qits-arch-
   * rules' own lesson about a rule matched by string.
   */
  @Test
  void everyDeclaredAgentWriteResolves() {
    for (String name : AGENT_WRITES) {
      Method method = declared(name);
      assertTrue(isWrite(method), name + " must be a write route");
      assertTrue(roles(method.getDeclaringClass(), method).contains(AGENT), name + " must admit " + AGENT);
    }
  }

  private static Method declared(String name) {
    String simpleName = name.substring(0, name.indexOf('.'));
    String methodName = name.substring(name.indexOf('.') + 1);
    for (Class<?> type : CLASSES) {
      if (!type.getSimpleName().equals(simpleName)) {
        continue;
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.getName().equals(methodName)) {
          return method;
        }
      }
    }
    throw new AssertionError(name + " names no method on any listed controller");
  }

  private static boolean isRead(Method method) {
    return method.isAnnotationPresent(GET.class) || method.isAnnotationPresent(HEAD.class);
  }

  private static boolean isWrite(Method method) {
    return method.isAnnotationPresent(POST.class)
        || method.isAnnotationPresent(PUT.class)
        || method.isAnnotationPresent(DELETE.class)
        || method.isAnnotationPresent(PATCH.class);
  }

  private static List<String> roles(Class<?> type, Method method) {
    RolesAllowed allowed =
        method.isAnnotationPresent(RolesAllowed.class)
            ? method.getAnnotation(RolesAllowed.class)
            : type.getAnnotation(RolesAllowed.class);
    return allowed == null ? List.of() : Arrays.asList(allowed.value());
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> all = new LinkedHashSet<>(first);
    all.addAll(second);
    return all;
  }
}
