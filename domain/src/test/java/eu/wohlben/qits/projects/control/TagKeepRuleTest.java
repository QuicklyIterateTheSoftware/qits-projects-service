package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.TagKeepRule.Facts;
import eu.wohlben.qits.projects.control.TagKeepRule.HostTag;
import eu.wohlben.qits.projects.control.TagKeepRule.Plan;
import eu.wohlben.qits.projects.control.TagKeepRule.Reason;
import eu.wohlben.qits.projects.control.TagKeepRule.RepositoryRead;
import eu.wohlben.qits.projects.control.TagKeepRule.TwinTag;
import eu.wohlben.qits.projects.control.TagKeepRule.TwinVerdicts;
import eu.wohlben.qits.projects.control.TagKeepRule.Verdicts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tag collection's judgement, one rule at a time, with no git and no Quarkus: every fact is
 * handed in, so each case says exactly which fact keeps (or fails to keep) which tag.
 *
 * <p>The baseline facts keep nothing — no pins, nothing in flight, no gitlinks, every tag a year old
 * and {@code keepNewest} zero — so a tag kept in a case is kept by the one fact that case adds.
 */
class TagKeepRuleTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final Instant OLD = NOW.minus(Duration.ofDays(365));

  private static Facts facts() {
    return new Facts(Set.of(), Set.of(), Set.of(), NOW, Duration.ofHours(24), 0);
  }

  private static Facts withPins(Set<String> pins) {
    Facts f = facts();
    return new Facts(pins, f.inFlight(), f.gitlinks(), f.now(), f.minAge(), f.keepNewest());
  }

  private static HostTag tag(String name) {
    return new HostTag(name, "obj-" + name, "commit-" + name, OLD);
  }

  // -----------------------------------------------------------------------------------------
  // which tags are judged at all, and in what order
  // -----------------------------------------------------------------------------------------

  @Test
  void onlyCalverTagsAreJudged() {
    assertTrue(TagKeepRule.isCalVer("2026.930.214434"));
    assertTrue(TagKeepRule.isCalVer("2026.1001.5"));
    assertFalse(TagKeepRule.isCalVer("v1.0.0"));
    assertFalse(TagKeepRule.isCalVer("2026.930"));
    assertFalse(TagKeepRule.isCalVer("2026.93.1"), "the middle segment is three or four digits");
    assertFalse(TagKeepRule.isCalVer("2026.930.1234567"), "the last is at most six");
    assertFalse(TagKeepRule.isCalVer("2026.930.1-rc"));

    Verdicts verdicts =
        TagKeepRule.judgeHost(List.of(tag("v1.0.0"), tag("release-1"), tag("2026.930.1")), facts());

    assertEquals(List.of("2026.930.1"), verdicts.deleted(), "a non-calver tag is never deleted");
    assertTrue(verdicts.kept().isEmpty(), "and never counted as kept either");
  }

  /** Not zero-padded: string order gets both of these pairs wrong. */
  @Test
  void releaseOrderIsNumericPerSegment() {
    List<String> names = new ArrayList<>(List.of("2026.1001.5", "2026.930.10", "2026.930.9"));
    names.sort(TagKeepRule.RELEASE_ORDER);
    assertEquals(List.of("2026.930.9", "2026.930.10", "2026.1001.5"), names);
  }

  // -----------------------------------------------------------------------------------------
  // the five reasons to keep
  // -----------------------------------------------------------------------------------------

  @Test
  void theNewestNOnTheHostAreKeptByNumericOrder() {
    Facts f = facts();
    Facts keepTwo = new Facts(f.pinnedVersions(), f.inFlight(), f.gitlinks(), NOW, f.minAge(), 2);

    Verdicts verdicts =
        TagKeepRule.judgeHost(
            List.of(tag("2026.930.9"), tag("2026.1001.5"), tag("2026.930.10"), tag("2026.929.1")),
            keepTwo);

    assertEquals(
        Map.of("2026.1001.5", Reason.NEWEST, "2026.930.10", Reason.NEWEST), verdicts.kept());
    assertEquals(List.of("2026.929.1", "2026.930.9"), verdicts.deleted(), "oldest first");
  }

  @Test
  void aPinnedVersionIsKeptWhateverRepositoryItIsIn() {
    Verdicts verdicts =
        TagKeepRule.judgeHost(
            List.of(tag("2026.901.1"), tag("2026.902.1")), withPins(Set.of("2026.901.1")));

    assertEquals(Map.of("2026.901.1", Reason.PINNED_VERSION), verdicts.kept());
    assertEquals(List.of("2026.902.1"), verdicts.deleted());
  }

  @Test
  void aTagWhoseCommitIsAGitlinkIsKept() {
    Facts f = facts();
    Facts linked =
        new Facts(Set.of(), Set.of(), Set.of("commit-2026.901.1"), NOW, f.minAge(), 0);

    Verdicts verdicts = TagKeepRule.judgeHost(List.of(tag("2026.901.1"), tag("2026.902.1")), linked);

    assertEquals(Map.of("2026.901.1", Reason.GITLINK), verdicts.kept());
  }

  @Test
  void aVersionInFlightIsKept() {
    Facts f = facts();
    Facts inFlight = new Facts(Set.of(), Set.of("2026.902.1"), Set.of(), NOW, f.minAge(), 0);

    Verdicts verdicts = TagKeepRule.judgeHost(List.of(tag("2026.901.1"), tag("2026.902.1")), inFlight);

    assertEquals(Map.of("2026.902.1", Reason.IN_FLIGHT), verdicts.kept());
  }

  @Test
  void aYoungTagIsKeptAndAnUnknownAgeCountsAsYoung() {
    HostTag young = new HostTag("2026.1001.1", "o", "c", NOW.minus(Duration.ofHours(2)));
    HostTag undated = new HostTag("2026.1001.2", "o2", "c2", null);
    HostTag justOld = new HostTag("2026.930.1", "o3", "c3", NOW.minus(Duration.ofHours(25)));

    Verdicts verdicts = TagKeepRule.judgeHost(List.of(young, undated, justOld), facts());

    assertEquals(
        Map.of("2026.1001.1", Reason.YOUNG, "2026.1001.2", Reason.YOUNG), verdicts.kept());
    assertEquals(List.of("2026.930.1"), verdicts.deleted());
  }

  @Test
  void aKeptTagIsCountedUnderTheFirstReasonOnly() {
    Facts f = facts();
    Facts everything =
        new Facts(
            Set.of("2026.901.1"),
            Set.of("2026.901.1"),
            Set.of("commit-2026.901.1"),
            NOW,
            f.minAge(),
            1);

    assertEquals(
        Map.of("2026.901.1", Reason.NEWEST),
        TagKeepRule.judgeHost(List.of(tag("2026.901.1")), everything).kept());
  }

  // -----------------------------------------------------------------------------------------
  // the pin document
  // -----------------------------------------------------------------------------------------

  @Test
  void everyCalverStringAnywhereInThePinsIsAPin() throws Exception {
    String body =
        """
        {"deployments": {"pins": [{"applicationName": "qits-ci", "shas": ["2026.930.10", "2026.929.1"]}]},
         "ciDaemon": {"daemonName": "qits-ci-daemon", "daemonVersion": "2026.928.5", "previousDaemonVersion": ""},
         "dependencies": {"pins": [{"ecosystem": "maven", "version": " 2026.927.7 ", "repository": "x"}]},
         "configuredImages": {"pins": []},
         "workspaceLaunches": {"pins": [{"image": "qits/workspace", "version": "2026.926.3"}]},
         "projectLaunches": {"generatedAt": "2026-10-01T12:00:00Z", "pins": [{"version": "1.2.3"}]}}
        """;

    Set<String> pins = TagKeepRule.pinnedVersions(new ObjectMapper().readTree(body));

    assertEquals(
        Set.of("2026.930.10", "2026.929.1", "2026.928.5", "2026.927.7", "2026.926.3"), pins);
  }

  // -----------------------------------------------------------------------------------------
  // the twin
  // -----------------------------------------------------------------------------------------

  /**
   * A tag only the twin holds — a release from before the platform — is older than everything on
   * the host, so it goes unless a pin or a gitlink names it. Never kept for being newest or young.
   */
  @Test
  void aTwinOnlyTagGoesUnlessPinnedOrGitlinked() {
    Facts f = facts();
    Facts facts =
        new Facts(
            Set.of("2025.101.1"), Set.of(), Set.of("c-2025.102.1"), NOW, f.minAge(), 5);
    List<TwinTag> twin =
        List.of(
            new TwinTag("2025.101.1", "o1", "c-2025.101.1"),
            new TwinTag("2025.102.1", "o2", "c-2025.102.1"),
            new TwinTag("2025.103.1", "tagobj-3", "c-2025.103.1"),
            new TwinTag("v0.9", "o4", "c4"));

    TwinVerdicts verdicts = TagKeepRule.judgeTwin(twin, Set.of(), Set.of(), facts);

    assertEquals(
        Map.of("2025.101.1", Reason.PINNED_VERSION, "2025.102.1", Reason.GITLINK),
        verdicts.kept());
    assertEquals(
        Map.of("2025.103.1", "tagobj-3"),
        verdicts.deleted(),
        "deleted under the lease of the object the listing saw; the non-calver tag untouched");
  }

  /** A tag the host still holds is pushed back by the next backup, so it stays on the twin too. */
  @Test
  void aTwinTagTheHostHoldsFollowsTheHost() {
    List<TwinTag> twin =
        List.of(new TwinTag("2026.901.1", "o1", "c1"), new TwinTag("2026.902.1", "o2", "c2"));

    TwinVerdicts verdicts =
        TagKeepRule.judgeTwin(
            twin, Set.of("2026.901.1", "2026.902.1"), Set.of("2026.902.1"), facts());

    assertEquals(Map.of("2026.902.1", "o2"), verdicts.deleted());
    assertTrue(verdicts.kept().isEmpty(), "the host's verdict is counted once, on the host");
  }

  // -----------------------------------------------------------------------------------------
  // the plan across repositories
  // -----------------------------------------------------------------------------------------

  /**
   * A wrapper release kept by a pin keeps the service release it mounts, and that keeps the
   * frontend release the service mounts — transitively, from facts only the second pass has.
   */
  @Test
  void gitlinksKeepTransitively() {
    RepositoryRead wrapper =
        new RepositoryRead("w", "qits-qits", true, List.of(tag("2026.901.1")), Set.of());
    RepositoryRead service =
        new RepositoryRead("s", "svc", false, List.of(tag("2026.801.1"), tag("2026.802.1")), Set.of());
    RepositoryRead frontend =
        new RepositoryRead("f", "spa", false, List.of(tag("2026.701.1"), tag("2026.702.1")), Set.of());
    Map<String, Set<String>> trees =
        Map.of(
            "commit-2026.901.1", Set.of("commit-2026.801.1"),
            "commit-2026.801.1", Set.of("commit-2026.701.1"));

    Plan plan =
        TagKeepRule.plan(
            List.of(wrapper, service, frontend),
            withPins(Set.of("2026.901.1")),
            (repoId, commit) -> trees.getOrDefault(commit, Set.of()));

    assertTrue(plan.sweep());
    assertEquals(Map.of("2026.801.1", Reason.GITLINK), plan.verdicts().get("s").kept());
    assertEquals(Map.of("2026.701.1", Reason.GITLINK), plan.verdicts().get("f").kept());
    assertEquals(List.of("2026.702.1"), plan.verdicts().get("f").deleted());
  }

  @Test
  void aMainBranchGitlinkKeepsTheTagItMounts() {
    RepositoryRead service =
        new RepositoryRead("s", "svc", false, List.of(), Set.of("commit-2026.701.1"));
    RepositoryRead frontend =
        new RepositoryRead("f", "spa", false, List.of(tag("2026.701.1")), Set.of());

    Plan plan = TagKeepRule.plan(List.of(service, frontend), facts(), (r, c) -> Set.of());

    assertEquals(Map.of("2026.701.1", Reason.GITLINK), plan.verdicts().get("f").kept());
  }

  /** Gitlink keeps are global: a wrapper that cannot be read leaves nothing safe to delete. */
  @Test
  void anUnreadableWrapperSweepsNothing() {
    RepositoryRead wrapper =
        new RepositoryRead("w", "qits-qits", true, List.of(tag("2026.901.1")), Set.of());
    RepositoryRead other =
        new RepositoryRead("s", "svc", false, List.of(tag("2026.801.1")), Set.of());

    Plan plan =
        TagKeepRule.plan(
            List.of(wrapper, other),
            withPins(Set.of("2026.901.1")),
            (repoId, commit) -> {
              throw new IllegalStateException("fatal: not a tree object");
            });

    assertFalse(plan.sweep());
    assertTrue(plan.verdicts().isEmpty(), "no repository may be swept");
    assertTrue(plan.errors().get(0).contains("nothing was swept anywhere"), plan.errors().get(0));
  }

  /**
   * A component that cannot be read stops the sweep too: its gitlinks may be the only thing keeping
   * a release elsewhere, so the estate's deletable set is unknown until it is read.
   */
  @Test
  void anUnreadableComponentSweepsNothingEither() {
    RepositoryRead broken =
        new RepositoryRead("b", "broken", false, List.of(tag("2026.901.1")), Set.of());
    RepositoryRead fine =
        new RepositoryRead("s", "svc", false, List.of(tag("2026.801.1")), Set.of());

    Plan plan =
        TagKeepRule.plan(
            List.of(broken, fine),
            withPins(Set.of("2026.901.1")),
            (repoId, commit) -> {
              throw new IllegalStateException("boom");
            });

    assertFalse(plan.sweep());
    assertTrue(plan.verdicts().isEmpty(), "svc's old release is not deleted either");
    assertEquals(1, plan.errors().size());
    assertTrue(plan.errors().get(0).startsWith("broken:"), plan.errors().get(0));
    assertTrue(plan.errors().get(0).contains("nothing was swept anywhere"), plan.errors().get(0));
  }
}
