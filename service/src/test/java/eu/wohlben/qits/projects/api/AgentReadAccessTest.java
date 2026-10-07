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
 *   <li>a write admits it <b>if and only if</b> it is named in one of the three declared groups
 *       below — the release-request writes, the entity writes and the one catalogue write;
 *   <li>the writes that must stay {@code qits:admin} alone are named in {@link
 *       #ADMIN_ONLY_WRITES} and asserted <em>not</em> to admit the agent, so a later widening trips
 *       this test rather than sliding past it as one more line in an opt-out set.
 * </ul>
 *
 * <p>Named groups rather than one list, because they are different arguments and a single
 * twenty-three-line set stops expressing any of them. Each group's javadoc carries its own
 * reasoning; what the first two share is that a role list is only half the door, and the other half
 * — which rows the caller may reach — is asserted in {@code ReleaseRequestAgentBoundsTest} and
 * {@code eu.wohlben.qits.entities.api.EntityAgentBoundsTest}. The third has no second half by
 * design: a create names no existing row to be bound to, and the project it names is the whole of
 * what it touches.
 *
 * <p><b>The list of classes is explicit, so a controller is only checked if it is here</b>, and
 * every controller on this surface is. The per-class sanity check below asks for a <em>route</em>
 * rather than for a read: a class with neither is a name that no longer resolves to a door, and that
 * is the thing worth failing on. Since qits-976 the work-entity surface is the {@code /work} family
 * alone — the per-archetype and {@code /entities} controllers are deleted, and {@code
 * RetiredEntityDoorsTest} pins that their paths answer 404.
 */
class AgentReadAccessTest {

  private static final String AGENT = "qits:agent";
  private static final String ADMIN = "qits:admin";
  private static final String ADMIN_AGENT = "qits:admin-agent";

  private static final List<Class<?>> CLASSES =
      List.of(
          eu.wohlben.qits.entities.api.WorkController.class,
          eu.wohlben.qits.entities.api.WorkCommentController.class,
          eu.wohlben.qits.entities.api.WorkArchetypesController.class,
          eu.wohlben.qits.entities.api.ProjectWorkController.class,
          eu.wohlben.qits.entities.api.WorkDossierController.class,
          eu.wohlben.qits.entities.api.WorkDossierAssetController.class,
          eu.wohlben.qits.entities.api.WorkChildrenController.class,
          eu.wohlben.qits.entities.api.WorkAuditController.class,
          eu.wohlben.qits.entities.api.WorkProgressController.class,
          eu.wohlben.qits.entities.api.WorkMembersController.class,
          WorkDispatchController.class,
          WorkRefinementController.class,
          WorkWorkspacesController.class,
          AgentCapabilityController.class,
          AgentConfigurationController.class,
          AgentContainerController.class,
          AgentMcpCatalogController.class,
          AgentSurfaceConfigurationController.class,
          GcController.class,
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
   * project and {@code git_refs}; the last two, {@code rerunPhase} and {@code rerunAutomation}
   * (qits-978), are deliberately unbound — they decide nothing, so there is nothing to bind. {@code
   * approve}, {@code decline} and {@code waiveAutomations} are not here and must not arrive: those
   * are the sign-off, and that distinction is what this set is a list of.
   */
  private static final Set<String> RELEASE_REQUEST_AGENT_WRITES =
      Set.of(
          "ReleaseRequestController.create",
          "ReleaseRequestController.addSource",
          "ReleaseRequestController.setSourcePriority",
          "ReleaseRequestController.withdraw",
          "ReleaseRequestController.rerunPhase",
          "ReleaseRequestController.rerunAutomation");

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
          // The create, the patch, the status move and the block, the bulk transition and its
          // one-entity form (PUT, the PUT-shaped transition at the entity's address), and the
          // thread's comment and edit (qits-969). The status door admits the agent because a
          // ticket's and a campaign's moves are on its surface (transition_ticket, the campaign
          // tools); for an EPIC it refuses the agent in its body, as the epic's own transition door
          // did before qits-976 deleted it.
          "WorkController.create",
          "WorkController.put",
          "WorkController.patch",
          "WorkController.transition",
          "WorkController.setStatus",
          "WorkController.setBlocked",
          "WorkCommentController.add",
          "WorkCommentController.edit",
          // The sub-resources (qits-970): the dossier's four writes and the figure's inline (both
          // owners' and inline_figure's grants), a child's add (add_feature, add_task), and the
          // delete — which admits the agent for a feature or a task (remove_feature, remove_task)
          // and refuses it an epic or a ticket inside the door.
          "WorkDossierController.create",
          "WorkDossierController.put",
          "WorkDossierController.move",
          "WorkDossierController.delete",
          "WorkDossierAssetController.inline",
          "WorkChildrenController.create",
          "WorkController.delete");

  /**
   * <b>The catalogue write an agent reaches: adding a component to a project.</b> Its own group
   * because it belongs to neither argument above — there is no MCP tool behind it and it is not a
   * release request — and folding it into either would make that group's javadoc false.
   *
   * <p>It is granted because creating a repository is <em>additive</em>: it mints a blank on the
   * platform's git host (or attaches a url) and declares it in the wrapper, writing no existing row
   * and destroying nothing. The delete that is its opposite, {@code
   * RepositoryController.delete}, is not here and must not arrive — an agent may add to a project
   * and may not take anything out of it, which is the same line the four {@link #ADMIN_ONLY_WRITES}
   * draw. {@code ProjectController.adoptRepository} is not here either, for a different reason: it
   * is {@code qits:system} alone, because its caller supplies a git-host storage id.
   */
  private static final Set<String> CATALOGUE_AGENT_WRITES =
      Set.of("ProjectController.createRepository");

  /**
   * <b>The campaign build doors an agent reaches</b> (qits-413): the four membership writes — each bound to the agent's own project by {@code
   * EntitiesAgentAccess}, the campaign resolved first. A group of its own because the argument is
   * the epic's ruling rather than an existing MCP tool (the {@code repository} server's campaign
   * tools, qits-414, serve the same writes). {@code WorkMembersController.approve} is not here and must
   * not arrive: an approval is a person's sign-off, which is why it is in {@link #ADMIN_ONLY_WRITES}.
   */
  private static final Set<String> CAMPAIGN_AGENT_WRITES =
      Set.of(
          // The campaign's create is WorkController.create and its transition
          // WorkController.setStatus, both above.
          "WorkMembersController.add",
          "WorkMembersController.move",
          "WorkMembersController.remove",
          "WorkMembersController.setCondition");

  /** The union, which is what the per-class rule is read against. */
  private static final Set<String> AGENT_WRITES =
      union(
          union(union(RELEASE_REQUEST_AGENT_WRITES, ENTITY_AGENT_WRITES), CATALOGUE_AGENT_WRITES),
          CAMPAIGN_AGENT_WRITES);

  /**
   * <b>The writes that must stay {@code qits:admin} alone, asserted positively.</b> Deleting a
   * comment is on neither surface, for the reason the ticket section of {@code CLAUDE.md} gives: an
   * agent that could delete what it disagrees with could erase the record of its own mistake.
   *
   * <p>An epic's lifecycle move and an epic's or a ticket's delete stay a person's too, but inside
   * the door rather than in its role list — {@code WorkController.setStatus} and {@code
   * WorkController.delete} admit the agent for the kinds whose moves and deletes are tools, and
   * refuse it the rest in their bodies (the work suites pin those 403s).
   */
  private static final Set<String> ADMIN_ONLY_WRITES =
      Set.of(
          // Letting a fold through without its automations is a sign-off (qits-978).
          "ReleaseRequestController.waiveAutomations",
          "WorkCommentController.delete",
          // Approving a campaign criterion is the sign-off on a gated member (qits-413).
          "WorkMembersController.approve",
          // Standing a workspace or a refinement room up is a person's press, on either family.
          "WorkDispatchController.dispatch",
          "WorkRefinementController.open");

  /**
   * <b>The generic entity doors a platform service reaches</b> (qits-667): qits-maintenance files a
   * MAINTENANCE ticket, reads it back, comments on it, edits it and drops it, with its own client
   * token — whose one role is {@code qits:system}. Unbound: a machine carries no {@code project}
   * claim and {@code EntitiesAgentAccess} never asks it for one. Pinned by name so that a narrowing
   * of any of them is a red test rather than a silent 403 on the far side.
   */
  private static final Set<String> ENTITY_SYSTEM_ROUTES =
      Set.of(
          "WorkController.create",
          "WorkController.get",
          "WorkController.patch",
          "WorkController.setStatus",
          "WorkCommentController.list",
          "WorkCommentController.add");

  /**
   * <b>qits:admin-agent is admitted wherever qits:admin is</b> (qits-628 follow-up, owner's rule:
   * for now it may use everything {@code qits:admin} may use) — read off the annotations, exactly
   * as every other clause in this class is, so a route widened for {@code qits:admin} alone
   * without its sibling is caught here rather than slipping past as one more role on a list nobody
   * is checking.
   */
  @TestFactory
  Stream<DynamicTest> everyRouteAdmittingAdminAlsoAdmitsAdminAgent() {
    return CLASSES.stream()
        .map(
            type ->
                DynamicTest.dynamicTest(
                    type.getSimpleName(),
                    () -> {
                      for (Method method : type.getDeclaredMethods()) {
                        if (!isRead(method) && !isWrite(method)) {
                          continue;
                        }
                        List<String> roles = roles(type, method);
                        if (roles.contains(ADMIN)) {
                          assertTrue(
                              roles.contains(ADMIN_AGENT),
                              type.getSimpleName()
                                  + "."
                                  + method.getName()
                                  + " admits qits:admin and must admit qits:admin-agent too");
                        }
                      }
                    }));
  }

  @Test
  void aPlatformServiceReachesTheGenericEntityDoors() {
    for (String name : ENTITY_SYSTEM_ROUTES) {
      Method method = declared(name);
      assertTrue(isRead(method) || isWrite(method), name + " must be a route");
      assertTrue(
          roles(method.getDeclaringClass(), method).contains("qits:system"),
          name + " must admit qits:system");
    }
  }

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
   * The admin-only writes, checked by name rather than by absence from a set: a widening that
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
