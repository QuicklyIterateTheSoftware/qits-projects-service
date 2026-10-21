package eu.wohlben.qits.projects.control.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.AutomationKinds;
import eu.wohlben.qits.projects.control.AutomationKinds.Kind;
import eu.wohlben.qits.projects.control.FoldChanges;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link AutomationContentSource} with its fold and qits-maintenance's catalogue stood in for (task
 * qits-1046): the live catalogue's kinds, matched against a fold's paths; an unreachable catalogue
 * served from the cache or failing closed; a pathspec or role word it cannot read failing closed for
 * that kind.
 */
class AutomationContentSourceTest {

  private static final String FOLD = "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0";

  /** The catalogue as qits-maintenance answers it today (its golden master). */
  private static final List<Kind> LIVE =
      List.of(
          new Kind(
              "dependency-bump",
              "Dependency bump",
              List.of(
                  ":(glob)**/pom.xml",
                  ":(glob)**/package.json",
                  ":(glob)**/package-lock.json",
                  ":(glob)**/Dockerfile",
                  ":(glob)**/Dockerfile.*",
                  ":(glob)**/*.Dockerfile"),
              List.of()),
          new Kind(
              "entity-diagram",
              "Entity diagram",
              List.of(":(glob)docs/database/**"),
              List.of("agent", "person")),
          new Kind("estate-pins", "Estate pins", List.of(), List.of()),
          new Kind(
              "screenshot-baselines",
              "Screenshot baselines",
              List.of(":(glob)**/__screenshots__/**", ":(glob)**/testing/browser/renderer.txt"),
              List.of("agent", "person")));

  private final StubCatalogue stub = new StubCatalogue();
  private final MovableClock clock = new MovableClock();
  private int changesRead;

  private final AutomationContentSource source =
      new AutomationContentSource() {
        @Override
        AutomationKinds port() {
          return stub;
        }
      };

  {
    source.clock = clock;
    stub.answer = Optional.of(LIVE);
  }

  @Test
  void aScreenshotChangeNeedsAnAgentOrAPerson() {
    List<ApprovalRequirement> requirements =
        source.requirementsFor(
            subject(
                file("webui/src/app/__screenshots__/home.png"),
                file("webui/src/testing/browser/renderer.txt"),
                file("webui/src/app/home.ts")));

    assertEquals(1, requirements.size());
    ApprovalRequirement screenshots = requirements.get(0);
    assertEquals("automation:screenshot-baselines", screenshots.id());
    assertEquals("Screenshot baselines changed", screenshots.reason());
    assertEquals(List.of(ApprovalRole.AGENT, ApprovalRole.PERSON), screenshots.admits());
    assertFalse(screenshots.excludesAuthors());
    assertEquals(
        "webui/src/app/__screenshots__/home.png, webui/src/testing/browser/renderer.txt",
        screenshots.detail());
  }

  @Test
  void anEntityDiagramChangeNeedsAnAgentOrAPerson() {
    List<ApprovalRequirement> requirements =
        source.requirementsFor(subject(file("docs/database/projects.svg")));

    assertEquals(List.of("automation:entity-diagram"), ids(requirements));
    assertEquals("Entity diagram changed", requirements.get(0).reason());
    assertEquals(List.of(ApprovalRole.AGENT, ApprovalRole.PERSON), requirements.get(0).admits());
    assertEquals("docs/database/projects.svg", requirements.get(0).detail());
  }

  @Test
  void aKindWithoutRolesAddsNothing() {
    assertEquals(
        List.of(),
        source.requirementsFor(
            subject(file("pom.xml"), file("service/pom.xml"), file("docker/Dockerfile.jvm"))));
  }

  @Test
  void bothKindsInOneFoldAreTwoRequirements() {
    assertEquals(
        List.of("automation:entity-diagram", "automation:screenshot-baselines"),
        ids(
            source.requirementsFor(
                subject(file("docs/database/x.svg"), file("a/__screenshots__/b.png")))));
  }

  @Test
  void aRenameCountsByEitherPathAndAGitlinkNever() {
    CommitFileChangeDto renamedOut =
        new CommitFileChangeDto(
            "archive/home.png", "a/__screenshots__/home.png", "RENAMED", "100644", "100644", "a", "b",
            null);
    assertEquals(
        "a/__screenshots__/home.png",
        source.requirementsFor(subject(renamedOut)).get(0).detail());

    CommitFileChangeDto gitlink =
        new CommitFileChangeDto(
            "docs/database/sub", null, "MODIFIED", "160000", "160000", "a", "b", null);
    assertEquals(List.of(), source.requirementsFor(subject(gitlink)));
  }

  @Test
  void theDetailListsAtMostTwentyPaths() {
    CommitFileChangeDto[] many =
        IntStream.range(0, 25)
            .mapToObj(i -> file("a/__screenshots__/s" + i + ".png"))
            .toArray(CommitFileChangeDto[]::new);

    ApprovalRequirement screenshots = source.requirementsFor(subject(many)).get(0);

    assertEquals(20, screenshots.detail().split(ApprovalRequirement.PATH_SEPARATOR).length);
    assertEquals(
        "Screenshot baselines changed (20 of 25 changed paths listed)", screenshots.reason());
  }

  @Test
  void aCatalogueWithoutRolesReadsNoGit() {
    stub.answer =
        Optional.of(List.of(new Kind("estate-pins", "Estate pins", List.of(), List.of())));

    assertEquals(List.of(), source.requirementsFor(subject(file("a/__screenshots__/x.png"))));
    assertEquals(0, changesRead);
  }

  // ---- the catalogue: cache and fail closed ---------------------------------------------------

  @Test
  void anUnreachableCatalogueWithNothingCachedFailsClosedIntoAPerson() {
    stub.answer = Optional.empty();

    FoldUnreadable unreadable =
        assertThrows(
            FoldUnreadable.class,
            () -> source.requirementsFor(subject(file("a/__screenshots__/x.png"))));

    ApprovalRequirement closed = unreadable.requirement();
    assertEquals("automation:catalogue", closed.id());
    assertEquals(List.of(ApprovalRole.PERSON), closed.admits());
    assertTrue(closed.detail().contains("a person approves"), closed.detail());
    assertFalse(unreadable.stopsReading(), "the other content sources still read the fold");
  }

  @Test
  void aCachedAnswerIsServedForFiveMinutesWithoutAsking() {
    source.requirementsFor(subject(file("src/x.ts")));
    assertEquals(1, stub.calls);

    clock.advance(Duration.ofMinutes(4));
    source.requirementsFor(subject(file("src/x.ts")));
    assertEquals(1, stub.calls, "fresh: not asked again");

    clock.advance(Duration.ofMinutes(2));
    source.requirementsFor(subject(file("src/x.ts")));
    assertEquals(2, stub.calls, "stale: asked again");
  }

  @Test
  void anUnreachableCatalogueIsServedFromTheLastGoodAnswer() {
    source.requirementsFor(subject(file("src/x.ts")));
    stub.answer = Optional.empty();
    clock.advance(Duration.ofHours(1));

    List<ApprovalRequirement> requirements =
        source.requirementsFor(subject(file("a/__screenshots__/x.png")));

    assertEquals(List.of("automation:screenshot-baselines"), ids(requirements));
    assertEquals(List.of(ApprovalRole.AGENT, ApprovalRole.PERSON), requirements.get(0).admits());
    assertEquals(2, stub.calls, "it was asked, and the cache answered for it");

    source.forget();
    assertThrows(
        FoldUnreadable.class, () -> source.requirementsFor(subject(file("a/__screenshots__/x.png"))));
  }

  @Test
  void anUnconfiguredCatalogueAddsNothingAndIsNotAsked() {
    stub.configured = false;

    assertEquals(List.of(), source.requirementsFor(subject(file("a/__screenshots__/x.png"))));
    assertEquals(0, stub.calls);

    AutomationContentSource unwired =
        new AutomationContentSource() {
          @Override
          AutomationKinds port() {
            return null;
          }
        };
    assertEquals(List.of(), unwired.requirementsFor(subject(file("a/__screenshots__/x.png"))));
  }

  // ---- a kind it cannot read: fail closed for that kind -----------------------------------------

  @Test
  void anUnsupportedPathspecMagicNeedsAPersonForThatKind() {
    stub.answer =
        Optional.of(
            List.of(
                new Kind(
                    "screenshot-baselines",
                    "Screenshot baselines",
                    List.of(":(glob)**/__screenshots__/**", ":(icase)**/SCREENSHOTS/**"),
                    List.of("agent", "person")),
                LIVE.get(1)));

    List<ApprovalRequirement> requirements =
        source.requirementsFor(subject(file("src/unrelated.ts")));

    assertEquals(List.of("automation:screenshot-baselines"), ids(requirements));
    ApprovalRequirement closed = requirements.get(0);
    assertEquals(List.of(ApprovalRole.PERSON), closed.admits());
    assertTrue(closed.detail().contains("':(icase)**/SCREENSHOTS/**'"), closed.detail());
    assertTrue(closed.detail().contains("magic 'icase'"), closed.detail());
  }

  @Test
  void anUnsupportedPathspecInAKindWithoutRolesAddsNothing() {
    stub.answer =
        Optional.of(
            List.of(new Kind("dependency-bump", "Dependency bump", List.of(":!pom.xml"), List.of())));

    assertEquals(List.of(), source.requirementsFor(subject(file("pom.xml"))));
  }

  @Test
  void aRoleWordItCannotReadNeedsAPerson() {
    stub.answer =
        Optional.of(
            List.of(
                new Kind(
                    "entity-diagram",
                    "Entity diagram",
                    List.of(":(glob)docs/database/**"),
                    List.of("agent", "reviewer"))));

    ApprovalRequirement requirement =
        source.requirementsFor(subject(file("docs/database/x.svg"))).get(0);

    assertEquals(List.of(ApprovalRole.PERSON), requirement.admits());
  }

  @Test
  void anAgentOfAnotherProjectIsReadAsSuch() {
    stub.answer =
        Optional.of(
            List.of(
                new Kind(
                    "entity-diagram",
                    "Entity diagram",
                    List.of("docs/database"),
                    List.of("agent@docs-team"))));

    assertEquals(
        List.of(ApprovalRole.agentOf("docs-team")),
        source.requirementsFor(subject(file("docs/database/x.svg"))).get(0).admits());
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  private static List<String> ids(List<ApprovalRequirement> requirements) {
    return requirements.stream().map(ApprovalRequirement::id).toList();
  }

  private ApprovalSubject subject(CommitFileChangeDto... changes) {
    FoldChanges reader =
        new FoldChanges() {
          @Override
          public List<CommitFileChangeDto> changes(String repoId, String mergedSha, String pathspec) {
            changesRead++;
            return List.of(changes);
          }

          @Override
          public List<String> pathsBetween(String repoId, String foldSha, String previousFoldSha) {
            throw new UnsupportedOperationException();
          }

          @Override
          public boolean addsNothingToMain(String repoId, String mergedSha) {
            return false;
          }

          @Override
          public String blob(String repoId, String blobSha) {
            throw new UnsupportedOperationException();
          }

          @Override
          public String fileAt(String repoId, String mergedSha, String path) {
            throw new UnsupportedOperationException();
          }
        };
    return new ApprovalSubject(
        "repo-1",
        "p-own",
        FOLD,
        ReleaseRequest.State.PENDING,
        ReleaseGates.GateSet.of(Set.of()),
        true,
        reader);
  }

  private static CommitFileChangeDto file(String path) {
    return new CommitFileChangeDto(path, null, "MODIFIED", "100644", "100644", "a", "b", null);
  }

  /** qits-maintenance's catalogue, scripted. */
  private static final class StubCatalogue implements AutomationKinds {
    Optional<List<Kind>> answer = Optional.empty();
    boolean configured = true;
    int calls;

    @Override
    public boolean configured() {
      return configured;
    }

    @Override
    public Optional<List<Kind>> kinds() {
      calls++;
      return answer;
    }
  }

  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-10-21T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
