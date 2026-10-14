package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What the release-request list costs the git host</b> (qits-1203): the project-wide list asked
 * {@code ReleaseGates} for each distinct repository's gate set one after another, inside the read's
 * transaction, and each ask was a tree read at qits-githost that could take 1.3 s — a page took 4.5
 * to 5.5 s, once 38.
 *
 * <p>Three things are held here: a page spanning several repositories reads each one's tree at most
 * once, and those reads run <b>at the same time</b>; a second page inside the freshness window reads
 * nothing; and a page meeting a stale entry is answered from it at once while <b>one</b> refresh per
 * repository runs behind it, however many pages arrive meanwhile.
 *
 * <p>The suite runs with {@code gate-freshness-ms=0}, which turns the cache — and with it stale
 * serving — off; this class needs a window, and a {@code @TestProfile} would be a whole second app
 * kept for the rest of the run (CLAUDE.md, "the test-profile budget rule"). So the window is set on
 * the bean itself for the length of each test and put back afterwards, together with every entry
 * this class left in the cache.
 */
@QuarkusTest
public class ReleaseRequestListGateReadsTest {

  /** How long every tree read takes here — a slow git host, made slow on purpose. */
  private static final long TREE_LATENCY_MS = 300;

  private static final int REPOSITORIES = 4;

  @Inject ReleaseGates gates;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject eu.wohlben.qits.projects.control.ReleaseRequestNumbers releaseRequestNumbers;

  private String projectId;
  private final List<String> repoIds = new ArrayList<>();
  private long freshnessBefore;

  @BeforeEach
  void seed() throws Exception {
    gitHost.reset();
    freshnessBefore = freshness();
    projectId = "gate-reads-project-" + UUID.randomUUID();
    repoIds.clear();
    for (int index = 0; index < REPOSITORIES; index++) {
      repoIds.add("gate-reads-repo-" + index + "-" + UUID.randomUUID());
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "gate-reads";
              project.slug = "gate-reads-" + UUID.randomUUID();
              project.persist();
              for (String repoId : repoIds) {
                Repository repository = new Repository();
                repository.id = repoId;
                repository.project = project;
                repository.mainBranch = "main";
                repository.archetype = RepositoryArchetype.SERVICE;
                repository.persist();
              }
            });
    // A settled request per repository: the list has to draw each one's gates, and a FINALIZED row
    // asks no git of the approval question and is no open work for a neighbour's sweep to find.
    for (String repoId : repoIds) {
      finalized(repoId);
    }
    repoIds.forEach(gates::forget);
  }

  @AfterEach
  void restore() throws Exception {
    gitHost.treeLatency(0);
    awaitNoReadsInFlight();
    setFreshness(freshnessBefore);
    repoIds.forEach(gates::forget);
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
  }

  /**
   * A page spanning four repositories reads each one's tree exactly once, and the four reads
   * overlap: the page waits about one read, not four. A second page inside the window reads none.
   */
  @Test
  void aPageReadsEachRepositoryOnceConcurrentlyAndAPageInsideTheWindowReadsNothing()
      throws Exception {
    setFreshness(60_000);
    gitHost.treeLatency(TREE_LATENCY_MS);

    long started = System.nanoTime();
    List<String> listed = list();
    long tookMs = (System.nanoTime() - started) / 1_000_000;

    assertEquals(REPOSITORIES, listed.size(), "one request per repository: " + listed);
    List<RecordingReleaseGitHost.TreeRead> reads = ours();
    Map<String, Long> perRepository = countByRepository(reads);
    assertEquals(
        repoIds.stream().collect(Collectors.toMap(Function.identity(), repoId -> 1L)),
        perRepository,
        "exactly one tree read per repository on the page");
    long lastStart =
        reads.stream().mapToLong(RecordingReleaseGitHost.TreeRead::startedNanos).max().orElseThrow();
    long firstEnd =
        reads.stream().mapToLong(RecordingReleaseGitHost.TreeRead::endedNanos).min().orElseThrow();
    assertTrue(
        lastStart < firstEnd,
        "every read began before any had finished — they ran at the same time, not in turn");
    assertTrue(
        tookMs < REPOSITORIES * TREE_LATENCY_MS,
        "the page took " + tookMs + " ms, where four reads in turn take at least "
            + REPOSITORIES * TREE_LATENCY_MS);

    gitHost.clearTreeReads();
    assertEquals(listed, list());
    assertEquals(List.of(), ours(), "a page inside the freshness window asks the git host nothing");
  }

  /**
   * A stale entry is answered at once, and refreshed once behind the page: three pages arriving
   * while the refresh is still reading cost one read per repository between them, and none of the
   * three waits for it.
   */
  @Test
  void aStaleEntryIsAnsweredAtOnceAndRefreshedOnceBehindThePage() throws Exception {
    setFreshness(200);
    list(); // fills the cache, synchronously: nothing was remembered yet
    Thread.sleep(250); // every entry is now older than the window
    gitHost.clearTreeReads();
    gitHost.treeLatency(TREE_LATENCY_MS * 2);

    long started = System.nanoTime();
    List<CompletableFuture<List<String>>> pages = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      pages.add(CompletableFuture.supplyAsync(this::list));
    }
    pages.forEach(CompletableFuture::join);
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    assertTrue(
        tookMs < TREE_LATENCY_MS * 2,
        "three pages took " + tookMs + " ms: a stale entry must not make a page wait for the read");
    pages.forEach(page -> assertEquals(REPOSITORIES, page.join().size()));

    awaitNoReadsInFlight();
    assertEquals(
        repoIds.stream().collect(Collectors.toMap(Function.identity(), repoId -> 1L)),
        countByRepository(ours()),
        "one background refresh per repository, however many pages found it stale");
  }

  /**
   * <b>A freshness of zero is the suite's setting, and it serves nothing stale</b>: every page reads
   * what main says now, so a test that rewrites main and lists right after sees what it staged.
   */
  @Test
  void withNoWindowEveryPageReadsMainAfresh() throws Exception {
    setFreshness(0);
    list();
    list();
    assertEquals(
        repoIds.stream().collect(Collectors.toMap(Function.identity(), repoId -> 2L)),
        countByRepository(ours()),
        "two pages, two reads of each repository, both synchronous");
  }

  // ---------------------------------------------------------------------------------------------

  private List<String> list() {
    return given()
        .get("/projects/api/projects/" + projectId + "/release-requests?state=all")
        .then()
        .statusCode(200)
        .extract()
        .path("requests.id");
  }

  /** The tree reads of this class's repositories — a neighbour's reads are not this test's. */
  private List<RecordingReleaseGitHost.TreeRead> ours() {
    return gitHost.treeReads().stream().filter(read -> repoIds.contains(read.repoId())).toList();
  }

  private static Map<String, Long> countByRepository(List<RecordingReleaseGitHost.TreeRead> reads) {
    return reads.stream()
        .collect(
            Collectors.groupingBy(RecordingReleaseGitHost.TreeRead::repoId, Collectors.counting()));
  }

  /** Wait until no tree read of ours has been running for a moment — the background refreshes. */
  private void awaitNoReadsInFlight() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    int seen = -1;
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(TREE_LATENCY_MS * 3);
      int now = ours().size();
      if (now == seen) {
        return;
      }
      seen = now;
    }
  }

  private void finalized(String repoId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Instant when = Instant.now();
              ReleaseRequest row = new ReleaseRequest();
              row.id = UUID.randomUUID().toString();
              row.repoId = repoId;
              row.projectId = projectId;
              row.summary = "a finished release of " + repoId;
              row.state = ReleaseRequest.State.FINALIZED;
              row.version = "2026.1014." + Math.abs(repoId.hashCode() % 900000 + 100000);
              row.createdAt = when;
              row.armedAt = when;
              row.updatedAt = when;
              row.name(releaseRequestNumbers.next(row.repoId));
              row.persist();
            });
  }

  /** The bean behind the client proxy: a field set on the proxy would set nothing. */
  private Field freshnessField() throws NoSuchFieldException {
    Field field = ReleaseGates.class.getDeclaredField("freshnessMs");
    field.setAccessible(true);
    return field;
  }

  private long freshness() throws Exception {
    return freshnessField().getLong(ClientProxy.unwrap(gates));
  }

  private void setFreshness(long millis) throws Exception {
    freshnessField().setLong(ClientProxy.unwrap(gates), millis);
  }
}
