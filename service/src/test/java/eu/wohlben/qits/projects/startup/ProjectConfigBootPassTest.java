package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
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

  @BeforeEach
  void clean() {
    projectService.list().stream()
        .filter(p -> p.slug != null && p.slug.startsWith("bootcfg"))
        .toList()
        .forEach(p -> projectService.delete(p.id));
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
}
