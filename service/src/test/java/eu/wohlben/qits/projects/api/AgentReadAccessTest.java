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
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The user's ruling, read off the annotations: an agent keeps every read and gains no write.
 *
 * <p>For each class below, one case: every GET or HEAD route admits {@code qits:agent}, and no other
 * route does — except the four release-request writes, which bind the agent to its own work (see
 * {@code ReleaseRequestAgentBoundsTest}). A method-level {@code @RolesAllowed} replaces the class
 * list, so the effective list is the method's when it has one.
 */
class AgentReadAccessTest {

  private static final String AGENT = "qits:agent";

  private static final List<Class<?>> CLASSES =
      List.of(
          eu.wohlben.qits.epics.api.DossierAssetController.class,
          eu.wohlben.qits.epics.api.DossierController.class,
          eu.wohlben.qits.epics.api.EpicController.class,
          eu.wohlben.qits.epics.api.FeatureController.class,
          eu.wohlben.qits.epics.api.ProjectEpicsController.class,
          eu.wohlben.qits.epics.api.ProjectTicketsController.class,
          eu.wohlben.qits.epics.api.TaskController.class,
          eu.wohlben.qits.epics.api.TicketController.class,
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

  /** The writes an agent reaches, each bound to the agent's project and git_refs. */
  private static final Set<String> BOUND_WRITES =
      Set.of(
          "ReleaseRequestController.create",
          "ReleaseRequestController.addSource",
          "ReleaseRequestController.setSourcePriority",
          "ReleaseRequestController.withdraw");

  @TestFactory
  Stream<DynamicTest> anAgentReadsEverythingAndWritesNothingElse() {
    return CLASSES.stream()
        .map(
            type ->
                DynamicTest.dynamicTest(
                    type.getSimpleName(),
                    () -> {
                      boolean anyRead = false;
                      for (Method method : type.getDeclaredMethods()) {
                        String name = type.getSimpleName() + "." + method.getName();
                        if (isRead(method)) {
                          anyRead = true;
                          assertTrue(roles(type, method).contains(AGENT), name + " is a read");
                        } else if (isWrite(method) && !BOUND_WRITES.contains(name)) {
                          assertFalse(roles(type, method).contains(AGENT), name + " is a write");
                        }
                      }
                      assertTrue(anyRead, type.getSimpleName() + " has no read route");
                    }));
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
}
