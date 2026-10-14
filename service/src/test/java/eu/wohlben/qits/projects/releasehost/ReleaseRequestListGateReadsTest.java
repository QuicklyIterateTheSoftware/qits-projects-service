package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ApprovalContentRecorder;
import eu.wohlben.qits.projects.control.ApprovalPolicy;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.StoredGateSet;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.path.json.JsonPath;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What the release-request list costs git and the git host</b> — nothing, since qits-1201.
 *
 * <p>qits-1203 moved the list's gate reads and fold diffs out of its transaction, and remembered both
 * in memory; but a miss was still read on the list's own thread, and the memory was empty after
 * every restart and after every movement of a repository's {@code main} — so the first page after a
 * push read qits-githost and ran a mirror fetch plus about ten git subprocesses per open fold, and N
 * pages arriving together each did it again. Now the list reads the database only: each repository's
 * <b>stored</b> gate set ({@code release_gate_set}) and each request's <b>stored</b> approval content
 * ({@code approval_content_reasons}), with everything missing or stale read in the background, once.
 *
 * <p>The suite runs with {@code display-reads-synchronously=true} — every list reads on its own
 * thread, as before — because its tests rewrite main or a fold's scripted content and list right
 * after. The tests here that are about the deployed path turn that switch off on the two beans for
 * their own length, as the freshness window is set on the bean: a {@code @TestProfile} would be a
 * whole second app kept for the rest of the run (CLAUDE.md, "the test-profile budget rule").
 *
 * <p>"Read nothing on the list's thread" is proven by latency: the git host and the fold reads are
 * made slower than the bound the page must answer within, so a page that waited for any of them
 * could not make it — and the reads are then seen to happen anyway, behind the page.
 */
@QuarkusTest
public class ReleaseRequestListGateReadsTest {

  /** How long every tree read takes here — a slow git host, made slow on purpose. */
  private static final long TREE_LATENCY_MS = 300;

  /** How long each content read takes: a mirror refresh plus its git subprocesses. */
  private static final long FOLD_LATENCY_MS = 300;

  /** What a page must answer within when nothing slow is on its thread. */
  private static final long PAGE_BOUND_MS = 250;

  private static final int REPOSITORIES = 4;

  @Inject ReleaseGates gates;

  @Inject ApprovalContentRecorder recorder;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject eu.wohlben.qits.projects.control.ReleaseRequestNumbers releaseRequestNumbers;

  @Inject ReleaseRequests releaseRequests;

  @Inject RecordingFoldChanges foldChanges;

  /** Holds every gate open, so an open request seeded here never releases itself mid-test. */
  @Inject FakeActiveBuilds activeBuilds;

  private String projectId;
  private final List<String> repoIds = new ArrayList<>();
  private long freshnessBefore;
  private boolean gatesSynchronousBefore;
  private boolean recorderSynchronousBefore;

  @BeforeEach
  void seed() throws Exception {
    gitHost.reset();
    foldChanges.reset();
    activeBuilds.answer(java.util.Optional.of(1));
    freshnessBefore = freshness();
    gatesSynchronousBefore = field(ReleaseGates.class, "displayReadsSynchronously").getBoolean(bean(gates));
    recorderSynchronousBefore =
        field(ApprovalContentRecorder.class, "displayReadsSynchronously").getBoolean(bean(recorder));
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
    warmUp();
  }

  /**
   * The first page into a fresh app pays for warming the route and its queries up — measured at
   * about a tree read's latency — and no page timed here should. So a throwaway project of its own
   * is listed once, in the suite's mode, before anything is timed.
   */
  private void warmUp() {
    String warmProject = "gate-reads-warm-up-" + UUID.randomUUID();
    String warmRepo = "gate-reads-warm-up-repo-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = warmProject;
              project.name = "gate-reads-warm-up";
              project.slug = "gate-reads-warm-up-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = warmRepo;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
              Instant when = Instant.now();
              ReleaseRequest row = new ReleaseRequest();
              row.id = UUID.randomUUID().toString();
              row.repoId = warmRepo;
              row.projectId = warmProject;
              row.summary = "warming up";
              row.state = ReleaseRequest.State.CONFLICTED;
              row.mergedSha = fold(99);
              row.createdAt = when;
              row.armedAt = when;
              row.updatedAt = when;
              row.name(releaseRequestNumbers.next(row.repoId));
              row.persist();
            });
    given()
        .get("/projects/api/projects/" + warmProject + "/release-requests?state=all")
        .then()
        .statusCode(200);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", warmProject);
              StoredGateSet.delete("repoId", warmRepo);
            });
  }

  @AfterEach
  void restore() throws Exception {
    gitHost.treeLatency(0);
    foldChanges.readLatency(0);
    awaitQuiet();
    setFreshness(freshnessBefore);
    field(ReleaseGates.class, "displayReadsSynchronously").setBoolean(bean(gates), gatesSynchronousBefore);
    field(ApprovalContentRecorder.class, "displayReadsSynchronously")
        .setBoolean(bean(recorder), recorderSynchronousBefore);
    repoIds.forEach(gates::forget);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              StoredGateSet.delete("repoId in ?1", repoIds);
            });
  }

  // ---------------------------------------------------------------------------------------------
  // The suite's mode: everything read on the list's thread, as before qits-1201
  // ---------------------------------------------------------------------------------------------

  /**
   * <b>A freshness of zero is the suite's setting, and it serves nothing stale</b>: every page reads
   * what main says now, so a test that rewrites main and lists right after sees what it staged.
   */
  @Test
  void inTheSuitesModeEveryPageReadsMainAfresh() throws Exception {
    setFreshness(0);
    list();
    list();
    assertEquals(
        each(2L), countByRepository(ours()), "two pages, two reads of each repository, both synchronous");
  }

  /** And every page reads every open fold, so a test that rescripts a fold sees the new script. */
  @Test
  void inTheSuitesModeEveryPageReadsTheOpenFoldsAfresh() throws Exception {
    for (int index = 0; index < REPOSITORIES; index++) {
      pending(repoIds.get(index), fold(index));
    }
    list();
    list();
    assertEquals(each(4L), foldReadsNow());
  }

  // ---------------------------------------------------------------------------------------------
  // The deployed mode: the database only
  // ---------------------------------------------------------------------------------------------

  /**
   * A cold page — nothing in memory, nothing stored — asks neither the git host nor the mirror: it
   * answers within a bound shorter than one read of either, shows every gate set as not read yet and
   * every open fold as not read yet (approval required: fail closed), and the reads then happen
   * behind it, once each, and land in the store. The next page shows them and reads nothing.
   */
  @Test
  void aColdPageReadsNothingAndWhatItStartedLandsInTheStore() throws Exception {
    deployed(60_000);
    List<String> open = new ArrayList<>();
    for (int index = 0; index < REPOSITORIES; index++) {
      open.add(pending(repoIds.get(index), fold(index)));
    }
    gitHost.treeLatency(TREE_LATENCY_MS);
    foldChanges.readLatency(FOLD_LATENCY_MS);

    long started = System.nanoTime();
    JsonPath page = page();
    long tookMs = (System.nanoTime() - started) / 1_000_000;

    assertTrue(tookMs < PAGE_BOUND_MS, "a cold page took " + tookMs + " ms: it waited for a read");
    for (String id : open) {
      assertEquals(
          ApprovalContentRecorder.NOT_YET_READ,
          page.getString(row(id) + ".gates.find { it.kind == 'APPROVAL' }.detail"),
          "an unread fold fails closed");
      assertEquals("UNKNOWN", page.getString(row(id) + ".gates.find { it.kind == 'CI' }.state"));
    }

    await(() -> repoIds.stream().allMatch(repoId -> storedGateSet(repoId) != null), "gate sets stored");
    await(() -> open.stream().allMatch(id -> storedFold(id) != null), "fold contents stored");
    assertEquals(each(1L), countByRepository(ours()), "one background tree read per repository");
    assertEquals(each(2L), foldReadsNow(), "one background readContent per fold (two reads each)");
    for (String repoId : repoIds) {
      assertTrue(storedGateSet(repoId).contains("\"CI\""), "the stored set: " + storedGateSet(repoId));
    }

    gitHost.clearTreeReads();
    started = System.nanoTime();
    page = page();
    tookMs = (System.nanoTime() - started) / 1_000_000;
    assertTrue(tookMs < PAGE_BOUND_MS, "the second page took " + tookMs + " ms");
    for (String id : open) {
      assertEquals("false", page.getString(row(id) + ".approvalRequired"), "the stored reasons: none");
      assertNotEquals("UNKNOWN", page.getString(row(id) + ".gates.find { it.kind == 'CI' }.state"));
    }
    assertEquals(List.of(), ours(), "a page with everything stored asks the git host nothing");
    assertEquals(each(2L), foldReadsNow(), "and reads no fold");
  }

  /**
   * <b>The page after a push to main</b> — the case that used to be the slow one: {@code onMainMoved}
   * emptied both memories and the next page read everything on its thread. Now it shows what is
   * stored, at once, while the gate set and every open fold of that repository are re-read behind
   * it; and once the re-read lands, the page shows the new answer.
   */
  @Test
  void aPageAfterMainMovedShowsWhatIsStoredWhileItIsReReadBehindIt() throws Exception {
    deployed(60_000);
    List<String> open = new ArrayList<>();
    for (int index = 0; index < REPOSITORIES; index++) {
      open.add(pending(repoIds.get(index), fold(index)));
    }
    page();
    await(() -> open.stream().allMatch(id -> storedFold(id) != null), "fold contents stored");
    await(() -> repoIds.stream().allMatch(repoId -> storedGateSet(repoId) != null), "gate sets stored");
    awaitQuiet();
    gitHost.clearTreeReads();

    String moved = repoIds.get(0);
    String movedRow = open.get(0);
    foldChanges.changes(
        moved,
        List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release-requests.yml", null)));
    gitHost.treeLatency(TREE_LATENCY_MS);
    foldChanges.readLatency(FOLD_LATENCY_MS);

    long started = System.nanoTime();
    releaseRequests.onMainMoved(moved, "0000000000000000000000000000000000000abc");
    JsonPath page = page();
    long tookMs = (System.nanoTime() - started) / 1_000_000;

    assertTrue(
        tookMs < PAGE_BOUND_MS,
        "onMainMoved and the page after it took " + tookMs + " ms: something read on this thread");
    assertEquals("false", page.getString(row(movedRow) + ".approvalRequired"), "the stored reasons");
    assertNotEquals(
        "UNKNOWN",
        page.getString(row(movedRow) + ".gates.find { it.kind == 'CI' }.state"),
        "the stored gate set, not 'not read yet'");

    await(
        () -> {
          String reasons = storedReasons(movedRow);
          return reasons != null && reasons.contains("changes .config/qits/");
        },
        "the moved repository's fold re-read and rewritten");
    await(() -> countByRepository(ours()).getOrDefault(moved, 0L) == 1L, "its gate set re-read");
    awaitQuiet();
    Map<String, Long> expected = new HashMap<>(each(2L));
    expected.put(moved, 4L);
    assertEquals(expected, foldReadsNow(), "main moved under one repository: only its fold again");
    assertEquals(Map.of(moved, 1L), countByRepository(ours()), "and only its gate set");

    page = page();
    assertEquals("true", page.getString(row(movedRow) + ".approvalRequired"));
    assertEquals(
        "changes .config/qits/: .config/qits/release-requests.yml",
        page.getString(row(movedRow) + ".gates.find { it.kind == 'APPROVAL' }.detail"));
  }

  /**
   * <b>One read per fold, however many ask at once.</b> Two enqueues and three pages arriving while
   * the fold is being read cost one {@code readContent} between them; a re-read asked for while it
   * is in flight (main moved under it) costs exactly one more, after it.
   */
  @Test
  void concurrentAsksForOneFoldReadItOnce() throws Exception {
    deployed(60_000);
    String repoId = repoIds.get(0);
    String sha = fold(0);
    String id = pending(repoId, sha);
    foldChanges.readLatency(FOLD_LATENCY_MS);

    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<?>> asks = new ArrayList<>();
    for (int ask = 0; ask < 2; ask++) {
      asks.add(
          CompletableFuture.runAsync(
              () -> {
                awaitQuietly(go);
                recorder.enqueue(repoId, sha);
              }));
    }
    for (int ask = 0; ask < 3; ask++) {
      asks.add(
          CompletableFuture.runAsync(
              () -> {
                awaitQuietly(go);
                page();
              }));
    }
    go.countDown();
    asks.forEach(CompletableFuture::join);
    await(() -> storedFold(id) != null, "the fold stored");
    awaitQuiet();
    assertEquals(2, foldChanges.reads(repoId), "one readContent (two reads) for five asks");

    // A re-read asked for while the fold is being read follows it, once.
    recorder.enqueue(repoId, sha);
    await(() -> foldChanges.reads(repoId) > 2, "the second read started");
    recorder.reread(repoId, sha);
    recorder.reread(repoId, sha);
    recorder.enqueue(repoId, sha);
    awaitQuiet();
    assertEquals(6, foldChanges.reads(repoId), "the read in flight, and exactly one after it");
  }

  /**
   * What the recorder stores for a fold is what the list shows for it, word for word — the trigger
   * {@code ReleaseRequests.remerge} pulls once a fold commits — and a fold that moves past the stored
   * reading is shown as not read yet until its own reading lands.
   */
  @Test
  void theReasonsRecordedForAFoldAreWhatTheListShows() throws Exception {
    deployed(60_000);
    String config = repoIds.get(0);
    String empty = repoIds.get(1);
    foldChanges.changes(
        config,
        List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release-requests.yml", null)));
    foldChanges.empty(empty);
    String configRow = pending(config, fold(0));
    String emptyRow = pending(empty, fold(1));

    recorder.enqueue(config, fold(0));
    recorder.enqueue(empty, fold(1));
    await(() -> storedFold(configRow) != null && storedFold(emptyRow) != null, "both stored");
    assertEquals(
        "[\"changes .config/qits/: .config/qits/release-requests.yml\"]", storedReasons(configRow));
    assertEquals("[\"" + ApprovalPolicy.NO_CHANGES_DETAIL + "\"]", storedReasons(emptyRow));
    int readsBefore = foldChanges.reads(config) + foldChanges.reads(empty);

    JsonPath page = page();
    assertEquals(
        "changes .config/qits/: .config/qits/release-requests.yml",
        page.getString(row(configRow) + ".gates.find { it.kind == 'APPROVAL' }.detail"));
    assertEquals(
        ApprovalPolicy.NO_CHANGES_DETAIL,
        page.getString(row(emptyRow) + ".gates.find { it.kind == 'APPROVAL' }.detail"));
    assertEquals(
        readsBefore, foldChanges.reads(config) + foldChanges.reads(empty), "the page read no fold");

    // The fold moves on (written straight to the row, as a fold does): the stored reading is about
    // the old one, so the page fails closed for it and has the new one read.
    String refold = fold(9);
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(configRow).mergedSha = refold);
    page = page();
    assertEquals(
        ApprovalContentRecorder.NOT_YET_READ,
        page.getString(row(configRow) + ".gates.find { it.kind == 'APPROVAL' }.detail"));
    await(() -> refold.equals(storedFold(configRow)), "the new fold read and stored");
    assertEquals(
        "changes .config/qits/: .config/qits/release-requests.yml",
        page().getString(row(configRow) + ".gates.find { it.kind == 'APPROVAL' }.detail"));
  }

  /**
   * <b>A gate set read once is shown after the memory is gone</b> — a restart, as far as the list
   * can tell. A decision's {@code resolve} stores what it read; with the in-memory cache emptied the
   * page shows the stored set and asks the git host nothing, inside the window. Past the window it
   * still shows it at once, and refreshes it once behind the page however many pages arrive.
   */
  @Test
  void aStoredGateSetIsServedAfterTheMemoryIsLost() throws Exception {
    deployed(60_000);
    repoIds.forEach(gates::resolve);
    await(() -> repoIds.stream().allMatch(repoId -> storedGateSet(repoId) != null), "gate sets stored");
    awaitQuiet();
    clearGateMemory();
    gitHost.clearTreeReads();
    gitHost.treeLatency(TREE_LATENCY_MS);

    JsonPath page = page();
    for (String repoId : repoIds) {
      assertNotEquals(
          "UNKNOWN",
          page.getString(
              "requests.find { it.repoId == '" + repoId + "' }.gates.find { it.kind == 'CI' }.state"),
          "the stored set");
    }
    assertEquals(List.of(), ours(), "a stored set inside the window asks the git host nothing");

    // Past the window: shown at once, refreshed once behind the pages.
    setFreshness(200);
    Thread.sleep(250);
    long started = System.nanoTime();
    List<CompletableFuture<List<String>>> pages = new ArrayList<>();
    for (int count = 0; count < 3; count++) {
      pages.add(CompletableFuture.supplyAsync(this::list));
    }
    pages.forEach(CompletableFuture::join);
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    assertTrue(
        tookMs < TREE_LATENCY_MS,
        "three pages took " + tookMs + " ms: a stale set must not make a page wait for the read");
    setFreshness(60_000);
    awaitQuiet();
    assertEquals(
        each(1L), countByRepository(ours()), "one background refresh per repository, however many pages");
  }

  // ---------------------------------------------------------------------------------------------

  /** The deployed path, with {@code freshnessMs} as the window. */
  private void deployed(long freshnessMs) throws Exception {
    setFreshness(freshnessMs);
    field(ReleaseGates.class, "displayReadsSynchronously").setBoolean(bean(gates), false);
    field(ApprovalContentRecorder.class, "displayReadsSynchronously").setBoolean(bean(recorder), false);
  }

  private static String fold(int index) {
    return String.format("%040x", 0xf01d00 + index);
  }

  private static String row(String id) {
    return "requests.find { it.id == '" + id + "' }";
  }

  private <T> Map<String, T> eachOf(T value) {
    return repoIds.stream().collect(Collectors.toMap(Function.identity(), repoId -> value));
  }

  private Map<String, Long> each(long value) {
    return eachOf(value);
  }

  private Map<String, Long> foldReadsNow() {
    return repoIds.stream()
        .collect(
            Collectors.toMap(Function.identity(), repoId -> (long) foldChanges.reads(repoId)));
  }

  /**
   * An open request on {@code fold}, written straight to the table: the list is the subject. It is
   * CONFLICTED — before its tag, so its approval content is read, and the one open state the 30 s
   * sweep leaves alone, so no evaluation of a neighbour's sweep reads its gates or its fold while a
   * test is counting reads.
   */
  private String pending(String repoId, String fold) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Instant when = Instant.now();
              ReleaseRequest row = new ReleaseRequest();
              row.id = id;
              row.repoId = repoId;
              row.projectId = projectId;
              row.summary = "an open release of " + repoId;
              row.state = ReleaseRequest.State.CONFLICTED;
              row.mergedSha = fold;
              row.createdAt = when;
              row.armedAt = when;
              row.updatedAt = when;
              row.name(releaseRequestNumbers.next(row.repoId));
              row.persist();
            });
    return id;
  }

  private String storedGateSet(String repoId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                StoredGateSet.<StoredGateSet>findByIdOptional(repoId)
                    .map(row -> row.gateSet)
                    .orElse(null));
  }

  private String storedFold(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(requestId).approvalContentFold);
  }

  private String storedReasons(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(requestId).approvalContentReasons);
  }

  private JsonPath page() {
    return given()
        .get("/projects/api/projects/" + projectId + "/release-requests?state=all")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  private List<String> list() {
    return page().getList("requests.id");
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

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: " + what);
      }
      Thread.sleep(50);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Wait until no read of ours — tree or fold — has started for a moment: the background work
   * settled. A read in flight makes no count move for its latency, hence the long quiet interval.
   */
  private void awaitQuiet() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    String seen = null;
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(Math.max(TREE_LATENCY_MS, FOLD_LATENCY_MS) * 3);
      String now = ours().size() + "/" + foldReadsNow() + "/" + storedSnapshot();
      if (now.equals(seen)) {
        return;
      }
      seen = now;
    }
  }

  /** Every stored value of ours, so a write landing after the last read also counts as movement. */
  private String storedSnapshot() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                StoredGateSet.<StoredGateSet>list("repoId in ?1", repoIds).stream()
                        .map(row -> row.repoId + row.readAt)
                        .sorted()
                        .collect(Collectors.joining(","))
                    + ReleaseRequest.<ReleaseRequest>list("projectId", projectId).stream()
                        .map(row -> row.id + row.approvalContentFold + row.approvalContentReasons)
                        .sorted()
                        .collect(Collectors.joining(",")));
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

  /** The in-memory gate sets, emptied as a restart would — without the forget a main move makes. */
  @SuppressWarnings("unchecked")
  private void clearGateMemory() throws Exception {
    ((Map<String, ?>) field(ReleaseGates.class, "cache").get(bean(gates))).clear();
  }

  /** The bean behind the client proxy: a field set on the proxy would set nothing. */
  private static Object bean(Object proxy) {
    return ClientProxy.unwrap(proxy);
  }

  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private long freshness() throws Exception {
    return field(ReleaseGates.class, "freshnessMs").getLong(bean(gates));
  }

  private void setFreshness(long millis) throws Exception {
    field(ReleaseGates.class, "freshnessMs").setLong(bean(gates), millis);
  }
}
