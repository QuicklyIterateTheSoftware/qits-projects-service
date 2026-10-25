package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Seeds and reads for the desk trigger suites: projects, their desks, their windows. */
final class DeskTriggerFixtures {

  private DeskTriggerFixtures() {}

  /** A project with a random id and slug, delivery as asked; answers its id. */
  static String project(boolean deliveryEnabled) {
    String id = "desk-trigger-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = id;
              project.name = "desk triggers";
              project.slug = "dt" + UUID.randomUUID().toString().substring(0, 8);
              project.deliveryEnabled = deliveryEnabled;
              project.persist();
            });
    return id;
  }

  static String slugOf(String projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> Project.<Project>findById(projectId).slug);
  }

  /** The project's desk row, holding the three subjects a desk-caused event would carry. */
  static void desk(String projectId, String token, String delivery, String session) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              FrontDesk desk = new FrontDesk();
              desk.projectId = projectId;
              desk.tokenSubject = token;
              desk.deliveryTokenSubject = delivery;
              desk.sessionTokenSubject = session;
              desk.createdAt = Instant.now();
              desk.persist();
            });
  }

  /** Every window of the project, oldest first. */
  static List<DeskTriggerWindow> windows(String projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> DeskTriggerWindow.list("projectId = ?1 order by openedAt", projectId));
  }

  static DeskTriggerWindow window(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> DeskTriggerWindow.findById(id));
  }

  /** Every line of the window, in admission order. */
  static List<DeskTriggerEvent> events(UUID windowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> DeskTriggerEvent.list("windowId = ?1 order by admittedAt, itemRef", windowId));
  }

  /** Every line of every window of the project. */
  static List<DeskTriggerEvent> eventsOf(String projectId) {
    return windows(projectId).stream().flatMap(w -> events(w.id).stream()).toList();
  }

  /** Drops the projects; the windows and their lines cascade, the desks are deleted here. */
  static void drop(List<String> projectIds) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String id : projectIds) {
                FrontDesk.deleteById(id);
                Project.deleteById(id);
              }
            });
  }
}
