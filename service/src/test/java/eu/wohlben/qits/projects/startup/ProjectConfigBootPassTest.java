package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.testsupport.RecordingProjectAnnouncer;
import eu.wohlben.qits.projects.testsupport.WrapperProjectYml;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The boot pass over every project's {@code project.yml} (qits-767): packaged runs only, and it
 * reaches every project, not merely the first or the self-seeded one. Driven through {@link
 * ProjectConfigBootPass#reconcileAll()} directly, the arrangement {@link StartupSelfSeedGateTest}
 * and {@link ProjectAnnounceBackfillTest} make with theirs.
 */
@QuarkusTest
public class ProjectConfigBootPassTest {

  @Inject ProjectConfigBootPass bootPass;
  @Inject ProjectService projectService;
  @Inject WrapperProjectYml projectYml;
  @Inject RecordingProjectAnnouncer announcer;

  @BeforeEach
  void clean() {
    projectService.list().stream()
        .filter(p -> p.slug != null && p.slug.startsWith("bootcfg"))
        .toList()
        .forEach(p -> projectService.delete(p.id));
    announcer.clear();
  }

  @Test
  public void runsOnlyOnAPackagedLaunch() {
    assertTrue(ProjectConfigBootPass.shouldRun(LaunchMode.NORMAL));
    assertFalse(ProjectConfigBootPass.shouldRun(LaunchMode.DEVELOPMENT));
    assertFalse(ProjectConfigBootPass.shouldRun(LaunchMode.TEST));
  }

  @Test
  public void theBootPassCoversEveryProject() throws Exception {
    Project first = projectService.create("Boot Config One", "bootcfg-one", null);
    Project second = projectService.create("Boot Config Two", "bootcfg-two", null);
    for (Project project : new Project[] {first, second}) {
      projectYml.commit(
          projectService.findWrapper(project.id).orElseThrow(),
          "front_desk:\n  lifecycle: ALWAYS_ON\n");
    }

    bootPass.reconcileAll();

    assertEquals(FrontDeskLifecycle.ALWAYS_ON, projectService.get(first.id).frontDeskLifecycle);
    assertEquals(FrontDeskLifecycle.ALWAYS_ON, projectService.get(second.id).frontDeskLifecycle);
  }

  /**
   * qits-386: a project whose stored values already agree with its wrapper is still restated at
   * boot — once — so a {@code ProjectChanged} lost before the deploy reaches the edge after it. A
   * project whose value moved in the same pass is announced once too, never twice.
   */
  @Test
  public void theBootPassRestatesEveryProjectOnce() throws Exception {
    Project unmoved = projectService.create("Boot Config Still", "bootcfg-still", null);
    Project moved = projectService.create("Boot Config Moved", "bootcfg-moved", null);
    projectYml.commit(
        projectService.findWrapper(moved.id).orElseThrow(), "front_desk:\n  lifecycle: ALWAYS_ON\n");

    bootPass.reconcileAll();

    var restated = announcer.changedOf(unmoved.id);
    assertEquals(1, restated.size(), "nothing moved, and it is restated anyway");
    assertTrue(restated.get(0).supportsEnvironments());
    assertEquals(FrontDeskLifecycle.ON_DEMAND, restated.get(0).frontDeskLifecycle());
    var announced = announcer.changedOf(moved.id);
    assertEquals(1, announced.size(), "the move's own frame is the restatement");
    assertEquals(FrontDeskLifecycle.ALWAYS_ON, announced.get(0).frontDeskLifecycle());
  }
}
