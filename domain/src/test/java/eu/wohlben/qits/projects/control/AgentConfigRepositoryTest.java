package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceLifecycle;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A project's {@code qits-agent-config} (qits-1382): created on the git host with the agent
 * configuration template as its one commit on {@code main}, and born without everything that makes
 * a repository a component — no wrapper entry, no main workspace, no backup twin.
 *
 * <p>Asserted against the <b>git host's</b> bare, like {@code WrapperSubmoduleWriterTest}, because
 * that is what the workspace daemon clones; a seed that only reached the mirror would be invisible
 * to it.
 */
@QuarkusTest
public class AgentConfigRepositoryTest {

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject RepositoryNameRepository repositoryNameRepository;
  @Inject RecordingWorkspaceLifecycle workspaceLifecycle;
  @Inject ReleaseRequestSettingsParser releaseRequestSettings;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;

  private String inHost(String repoId, String... argv) throws Exception {
    return git.exec(Path.of(gitHost.fetchUrl(repoId)).toFile(), argv).trim();
  }

  private Repository reread(String repoId) {
    return QuarkusTransaction.requiringNew().call(() -> repositoryService.get(repoId));
  }

  @Test
  public void theRepositoryIsSeededWithTheTemplateAsOneCommitOnMain() throws Exception {
    var project = projectService.create("Agent Config Seed", "agent-config-seed", null);

    var ensured = projectService.ensureAgentConfigRepository(project.id);

    assertTrue(ensured.created());
    String id = ensured.repository().id;
    assertEquals("1", inHost(id, "git", "rev-list", "--count", "main"), "one commit, the seed");
    assertEquals(
        List.of(
            ".claude-plugin/plugin.json",
            ".config/qits/release-requests.yml",
            "README.md",
            "memory/MEMORY.md",
            "settings.json",
            "skills/.gitkeep"),
        inHost(id, "git", "ls-tree", "-r", "--name-only", "main").lines().toList(),
        "every dotfile and dot-directory is un-prefixed, and the two empty files are committed");
    assertEquals(
        "{\"enabledPlugins\": {}}", inHost(id, "git", "show", "main:settings.json"));
    assertEquals("", inHost(id, "git", "show", "main:memory/MEMORY.md"));
    assertTrue(
        inHost(id, "git", "show", "main:.claude-plugin/plugin.json")
            .contains("\"name\": \"qits-agent-config\""));
    assertTrue(
        releaseRequestSettings
            .parse(inHost(id, "git", "show", "main:" + ReleaseRequestSettingsParser.SETTINGS_PATH))
            .manualReview(),
        "every release request of it waits for a person");
  }

  /**
   * Platform state, not software: the row says so, and nothing a component is given at creation is
   * given here.
   */
  @Test
  public void itIsBornWithoutAWrapperEntryAWorkspaceOrATwin() throws Exception {
    var project = projectService.create("Agent Config Exempt", "agent-config-exempt", null);
    Repository wrapper = projectService.findWrapper(project.id).orElseThrow();
    String wrapperHead = inHost(wrapper.id, "git", "rev-parse", "main");

    String id = projectService.ensureAgentConfigRepository(project.id).repository().id;

    Repository row = reread(id);
    assertEquals(RepositoryArchetype.AGENT_CONFIG, row.archetype);
    assertNull(row.url, "no backup twin");
    assertNull(row.component, "no component: it is not mounted anywhere");
    assertEquals("main", row.mainBranch);
    assertEquals(
        id,
        repositoryNameRepository
            .findRepositoryByProjectAndName(project.id, RepositoryService.AGENT_CONFIG_NAME)
            .orElseThrow()
            .id,
        "addressable as /git/<project>/qits-agent-config");
    assertEquals(
        wrapperHead,
        inHost(wrapper.id, "git", "rev-parse", "main"),
        "the wrapper was not written: no .gitmodules entry names it");
    assertFalse(workspaceLifecycle.createdMainWorkspaceFor(id), "nobody works on its main");
  }

  @Test
  public void aSecondPressAnswersTheRepositoryItAlreadyHas() throws Exception {
    var project = projectService.create("Agent Config Twice", "agent-config-twice", null);
    String first = projectService.ensureAgentConfigRepository(project.id).repository().id;

    var second = projectService.ensureAgentConfigRepository(project.id);

    assertFalse(second.created());
    assertEquals(first, second.repository().id);
    assertEquals("1", inHost(first, "git", "rev-list", "--count", "main"), "and seeded nothing again");
  }

  /** The create door refuses it like every other kind that is not a component of its project. */
  @Test
  public void theOrdinaryCreateDoorRefusesTheArchetype() {
    var project = projectService.create("Agent Config Refused", "agent-config-refused", null);

    assertThrows(
        BadRequestException.class,
        () ->
            projectService.createRepository(
                project.id, null, "qits-agent-config", RepositoryArchetype.AGENT_CONFIG));
    assertTrue(
        repositoryNameRepository
            .findRepositoryByProjectAndName(project.id, RepositoryService.AGENT_CONFIG_NAME)
            .isEmpty(),
        "and created nothing");
  }
}
