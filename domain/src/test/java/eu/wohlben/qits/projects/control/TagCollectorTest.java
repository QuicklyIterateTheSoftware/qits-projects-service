package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.TagCollector.CatalogueRow;
import eu.wohlben.qits.projects.dto.TagCollectionReportDto;
import eu.wohlben.qits.projects.dto.TagCollectionReportDto.DeletedTag;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tag collection against real bares: the git host's (the suite's fake host, a local bare per
 * repository) and a forge twin (a throwaway bare the row's url names). Every assertion is made
 * against the bares themselves — what the host and the twin end up holding is the whole point.
 *
 * <p>Driven through {@link TagCollector#collect(List, Set, Set, Instant, boolean)}, the catalogue
 * handed in, because the suite's database holds every other test's repositories and a real run
 * would judge those too.
 *
 * <p>The tags are annotated and dated a year back ({@code GIT_COMMITTER_DATE} is the tagger date),
 * so the min-age rule keeps none of them; {@code keep-newest} is the shipped five.
 */
@QuarkusTest
public class TagCollectorTest {

  private static final Map<String, String> A_YEAR_AGO =
      Map.of(
          "GIT_COMMITTER_DATE", "2025-10-01T00:00:00Z",
          "GIT_COMMITTER_NAME", "qits-test",
          "GIT_COMMITTER_EMAIL", "qits-test@local");

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject TagCollector collector;
  @Inject GitExecutor git;
  @Inject GitHostAddress gitHost;

  private Path hostOf(String repoId) {
    return Path.of(gitHost.fetchUrl(repoId));
  }

  private String in(Path repo, String... argv) throws Exception {
    return git.exec(repo.toFile(), argv).trim();
  }

  private void oldTag(Path repo, String name) throws Exception {
    git.exec(repo.toFile(), A_YEAR_AGO, "git", "tag", "-a", "-m", name, name, "HEAD");
  }

  private boolean has(Path repo, String tag) throws Exception {
    return !in(repo, "git", "tag", "--list", tag).isEmpty();
  }

  /** The world each case starts from. */
  private record Estate(Project project, Repository repo, Repository wrapper, Path host, Path twin) {
    List<CatalogueRow> catalogue(String name) {
      return List.of(
          new CatalogueRow(wrapper.id, "wrapper", wrapper.url, wrapper.mainBranch, true),
          new CatalogueRow(repo.id, name, repo.url, repo.mainBranch, false));
    }
  }

  /**
   * A repository whose host holds seven old releases and a non-calver tag, backed up to a twin
   * holding the same seven plus two releases from before the platform.
   */
  private Estate estate(String slug) throws Exception {
    Project project = projectService.create("Tags " + slug, slug, null);
    Path parent = Files.createTempDirectory("qits-tag-twin");
    Path twin = parent.resolve("testing-repo.git");
    git.exec(
        parent.toFile(),
        "git",
        "clone",
        "--bare",
        GitFixtures.path("testing-repo.git"),
        twin.toString());
    Repository repo =
        repositoryService.cloneRepository(twin.toString(), RepositoryArchetype.SERVICE, project);
    Path host = hostOf(repo.id);
    for (int i = 1; i <= 7; i++) {
      oldTag(host, "2026.101." + i);
    }
    oldTag(host, "v1.0");
    in(host, "git", "push", "--quiet", twin.toString(), "refs/tags/*:refs/tags/*");
    oldTag(twin, "2025.101.1");
    oldTag(twin, "2025.102.1");
    Repository wrapper = projectService.findWrapper(project.id).orElseThrow();
    return new Estate(project, repo, wrapper, host, twin);
  }

  @Test
  public void oldReleasesGoFromTheHostAndTheTwinAndEverythingKeptStays() throws Exception {
    Estate estate = estate("tags-sweep");

    TagCollectionReportDto report =
        collector.collect(
            estate.catalogue("svc"),
            Set.of("2026.101.1", "2025.102.1"),
            Set.of(),
            Instant.now(),
            false);

    assertFalse(report.dryRun());
    assertEquals(List.of(), report.errors());
    // 2026.101.2: not newest, not pinned, old — gone from both sides.
    assertFalse(has(estate.host(), "2026.101.2"));
    assertFalse(has(estate.twin(), "2026.101.2"));
    // 2025.101.1: only on the twin, pinned by nobody — gone; 2025.102.1 is pinned — kept.
    assertFalse(has(estate.twin(), "2025.101.1"));
    assertTrue(has(estate.twin(), "2025.102.1"));
    // the pinned release and the newest five, on both sides; the non-calver tag untouched
    for (String kept :
        List.of("2026.101.1", "2026.101.3", "2026.101.4", "2026.101.5", "2026.101.6", "2026.101.7")) {
      assertTrue(has(estate.host(), kept), kept + " on the host");
      assertTrue(has(estate.twin(), kept), kept + " on the twin");
    }
    assertTrue(has(estate.host(), "v1.0"));
    assertTrue(has(estate.twin(), "v1.0"));

    assertEquals(
        List.of(
            new DeletedTag("svc", "2025.101.1", false, true),
            new DeletedTag("svc", "2026.101.2", true, true)),
        report.deleted());
    assertEquals(5, report.kept().newest());
    assertEquals(2, report.kept().pinnedVersion(), "2026.101.1 on the host, 2025.102.1 on the twin");
    assertEquals(9, report.examined(), "seven on the host and the two only the twin holds");
  }

  @Test
  public void aDryRunJudgesTheSameAndDeletesNothing() throws Exception {
    Estate estate = estate("tags-dry");

    TagCollectionReportDto report =
        collector.collect(
            estate.catalogue("svc"), Set.of("2026.101.1"), Set.of(), Instant.now(), true);

    assertTrue(report.dryRun());
    assertEquals(
        List.of(
            new DeletedTag("svc", "2025.101.1", false, true),
            new DeletedTag("svc", "2025.102.1", false, true),
            new DeletedTag("svc", "2026.101.2", true, true)),
        report.deleted());
    assertTrue(has(estate.host(), "2026.101.2"));
    assertTrue(has(estate.twin(), "2026.101.2"));
    assertTrue(has(estate.twin(), "2025.101.1"));
  }

  /** A wrapper that cannot be read: gitlink keeps are global, so nothing anywhere is deleted. */
  @Test
  public void anUnreadableWrapperSweepsNothing() throws Exception {
    Estate estate = estate("tags-blind");
    List<CatalogueRow> catalogue =
        List.of(
            new CatalogueRow("no-such-wrapper", "lost-wrapper", null, "main", true),
            new CatalogueRow(estate.repo().id, "svc", estate.repo().url, "main", false));

    TagCollectionReportDto report =
        collector.collect(catalogue, Set.of(), Set.of(), Instant.now(), false);

    assertEquals(List.of(), report.deleted());
    assertEquals(1, report.errors().size());
    assertTrue(report.errors().get(0).startsWith("lost-wrapper:"), report.errors().get(0));
    assertTrue(has(estate.host(), "2026.101.2"));
    assertTrue(has(estate.twin(), "2025.101.1"));
  }

  /**
   * The wrapper's {@code main} mounts the commit every one of these releases points at, so its
   * gitlink keeps them all — on the host and, for the two only the twin holds, on the twin.
   */
  @Test
  public void aGitlinkInTheWrappersMainKeepsTheReleaseItMounts() throws Exception {
    Estate estate = estate("tags-mounted");
    Path wrapperHost = hostOf(estate.wrapper().id);
    String mounted = in(estate.host(), "git", "rev-parse", "HEAD^{commit}");
    Path index = Files.createTempDirectory("qits-tag-index").resolve("index");
    Map<String, String> scratch = Map.of("GIT_INDEX_FILE", index.toString());
    String main = in(wrapperHost, "git", "rev-parse", "refs/heads/main");
    git.exec(wrapperHost.toFile(), scratch, "git", "read-tree", main);
    git.exec(
        wrapperHost.toFile(),
        scratch,
        "git",
        "update-index",
        "--add",
        "--cacheinfo",
        "160000," + mounted + ",components/svc/svc");
    String tree = git.exec(wrapperHost.toFile(), scratch, "git", "write-tree").trim();
    Map<String, String> identity = new java.util.HashMap<>(A_YEAR_AGO);
    identity.put("GIT_AUTHOR_NAME", "qits-test");
    identity.put("GIT_AUTHOR_EMAIL", "qits-test@local");
    String commit =
        git.exec(wrapperHost.toFile(), identity, "git", "commit-tree", tree, "-p", main, "-m", "mount")
            .trim();
    in(wrapperHost, "git", "update-ref", "refs/heads/main", commit);

    TagCollectionReportDto report =
        collector.collect(estate.catalogue("svc"), Set.of(), Set.of(), Instant.now(), false);

    assertEquals(List.of(), report.errors());
    assertEquals(List.of(), report.deleted());
    assertEquals(4, report.kept().gitlink(), "two on the host past the newest five, two twin-only");
    assertTrue(has(estate.host(), "2026.101.1"));
    assertTrue(has(estate.twin(), "2025.101.1"));
  }

  @Test
  public void anLsRemoteAnswerLeasesTheTagObjectNotThePeeledCommit() {
    String output =
        """
        aaa\trefs/tags/2026.101.1
        bbb\trefs/tags/2026.101.1^{}
        ccc\trefs/tags/light
        """;

    assertEquals(
        List.of(
            new TagKeepRule.TwinTag("2026.101.1", "aaa", "bbb"),
            new TagKeepRule.TwinTag("light", "ccc", "ccc")),
        TagCollector.parseLsRemote(output));
  }

  @Test
  public void onlyAConfirmedDeletionCountsAsGone() {
    String porcelain =
        """
        To /tmp/twin.git
        -\t:refs/tags/2026.101.1\t[deleted]
        !\t:refs/tags/2026.101.2\t[rejected] (stale info)
        Done
        """;

    assertEquals(Set.of("2026.101.1"), TagCollector.confirmedDeletions(porcelain));
  }
}
