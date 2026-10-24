package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A source is stamped a feature when it is added</b> (epic qits-1580, task qits-1584): the work
 * item its branch names, read out of the real entities module, decides — an IMPROVEMENT ticket is a
 * feature, a BUG ticket is not, and neither is {@code main} or an automation's {@code
 * maintenance/…} branch. Every way a source is added is covered: the create (with its implied
 * {@code main}), a create converging on the open request, and the add-source verb.
 *
 * <p>The gate is held open ({@link FakeActiveBuilds} answering "a run is still active"), as in
 * {@link ReleaseRequestSourcesTest}, so nothing settles the fixture mid-test.
 */
@QuarkusTest
public class ReleaseRequestFeatureSourcesTest {

  @Inject ReleaseRequests releaseRequests;

  @Inject WorkEntityService entities;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    activeBuilds.answer(Optional.of(1));
    projectId = "feat-" + UUID.randomUUID().toString().substring(0, 8);
    repoId = "feat-repo-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
  }

  private String ticketBranch(String type) {
    long number =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("A " + type, "because", null, type, null),
                "seed")
            .entity()
            .number;
    return "ticket/" + projectId + "-" + number;
  }

  private Map<String, Boolean> featuresOf(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequestSource.<ReleaseRequestSource>list("requestId", requestId).stream()
                    .collect(Collectors.toMap(source -> source.name, source -> source.feature)));
  }

  @Test
  void anImprovementIsAFeatureAndABugMainAndAMaintenanceBranchAreNot() {
    String improvement = ticketBranch("IMPROVEMENT");
    String bug = ticketBranch("BUG");
    String automation = "maintenance/automations/entity-diagram/" + UUID.randomUUID();

    String id = releaseRequests.request(repoId, improvement, "a feature", "alice", null).id();
    // Converges on the open request: the converging arm stamps the row it adds as well.
    assertEquals(id, releaseRequests.request(repoId, bug, "a fix", "bob", null).id());
    releaseRequests.addSource(id, automation, "maintenance", null);

    assertEquals(
        Map.of("main", false, improvement, true, bug, false, automation, false), featuresOf(id));
  }
}
