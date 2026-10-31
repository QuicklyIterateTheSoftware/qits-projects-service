package eu.wohlben.qits.projects.desktriggers;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditEntry;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleasePriority;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
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

  /** The project's {@code max_running} and {@code delivery_enabled_at} (null: no clamp). */
  static void delivery(String projectId, int maxRunning, Instant enabledAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = Project.findById(projectId);
              project.deliveryMaxRunning = maxRunning;
              project.deliveryEnabledAt = enabledAt;
            });
  }

  /**
   * A ticket of the project numbered {@code number} at {@code status}, with an assignee, so an
   * ACTIVE agent ({@link #activeAgent}) makes it count as running (qits-1778).
   */
  static WorkEntity ticket(String projectId, long number, String status) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkEntity row = new WorkEntity();
              row.id = UUID.randomUUID().toString();
              row.projectId = projectId;
              row.archetype = Archetype.TICKET;
              row.number = number;
              row.title = "Ticket " + number;
              row.slug = "slug-" + row.id;
              row.slugScope = projectId;
              row.status = status;
              row.assignee = "ws-agent-" + number;
              row.persist();
              return row;
            });
  }

  /** The ACTIVE agent of {@code item}, for {@link RecordingWorkspaceAgentDispatch#willFind}. */
  static WorkspaceAgentDispatch.Agent activeAgent(WorkEntity item) {
    return RecordingWorkspaceAgentDispatch.active(
        "agent-" + item.id, 7L, "wrapper", item.id, "ticket/" + item.slug);
  }

  /** An audit row of {@code item} whose snapshot says {@code status}, written at {@code at}. */
  static void audited(WorkEntity item, String status, Instant at) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AuditEntry entry = new AuditEntry();
              entry.id = id;
              entry.entityType = AuditEntityType.TICKET;
              entry.entityId = item.id;
              entry.epicId = item.id;
              entry.operation = AuditOperation.UPDATE;
              entry.changedBy = "dana";
              entry.snapshot = "{\"id\":\"" + item.id + "\",\"status\":\"" + status + "\"}";
              entry.persist();
            });
    // changed_at is a creation timestamp: the insert stamps now, so the instant is set after it.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                AuditEntry.getEntityManager()
                    .createNativeQuery("update auditentry set changed_at = ?1 where id = ?2")
                    .setParameter(1, at)
                    .setParameter(2, id)
                    .executeUpdate());
  }

  /**
   * A release request of a fresh repository called {@code repoName} in the project, at {@code
   * state}, whose one named source is {@code branch}; answers its qualified id.
   */
  static String request(
      ReleaseRequestNumbers numbers,
      String projectId,
      String repoName,
      ReleaseRequest.State state,
      String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Repository repository = new Repository();
              repository.id = "desk-trigger-repo-" + UUID.randomUUID();
              repository.project = Project.findById(projectId);
              repository.mainBranch = "main";
              repository.persist();
              ReleaseRequest row = new ReleaseRequest();
              row.id = UUID.randomUUID().toString();
              row.repoId = repository.id;
              row.projectId = projectId;
              row.repoName = repoName;
              row.state = state;
              row.retryable = false;
              row.armedAt = Instant.now();
              row.createdAt = Instant.now();
              row.updatedAt = Instant.now();
              row.name(numbers.next(row.repoId));
              row.persist();
              ReleaseRequestSource source = new ReleaseRequestSource();
              source.id = UUID.randomUUID().toString();
              source.requestId = row.id;
              source.kind = ReleaseRequestSource.Kind.BRANCH;
              source.name = branch;
              source.priority = ReleasePriority.DEFAULT;
              source.addedAt = Instant.now();
              source.persist();
              return row.qualifiedId;
            });
  }

  /** Drops the work items of the projects and their audit rows, in the epics datasource. */
  static void dropWork(List<String> projectIds) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String id : projectIds) {
                for (WorkEntity row : WorkEntity.<WorkEntity>list("projectId", id)) {
                  AuditEntry.delete("entityId", row.id);
                }
                WorkEntity.delete("projectId", id);
              }
            });
  }

  /**
   * Drops the projects; the windows and their lines cascade, and the desks, release requests and
   * repositories a suite seeded are deleted here.
   */
  static void drop(List<String> projectIds) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String id : projectIds) {
                FrontDesk.deleteById(id);
                ReleaseRequest.delete("projectId", id);
                Repository.delete("project.id", id);
                Project.deleteById(id);
              }
            });
  }
}
