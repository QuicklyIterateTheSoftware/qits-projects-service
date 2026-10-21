package eu.wohlben.qits.projects.control.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.DownstreamPins;
import eu.wohlben.qits.projects.control.DownstreamPins.Downstream;
import eu.wohlben.qits.projects.control.DownstreamPins.Pin;
import eu.wohlben.qits.projects.control.FoldChanges;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link ContractChangeSource} with its fold, qits-maintenance and the project rows stood in for
 * (task qits-1291): a golden-master change consumed by another project opens one requirement for
 * that project's agent or a person, and every way the consumers cannot be told — or are not
 * another project's — opens none and throws nothing.
 */
class ContractChangeSourceTest {

  private static final String REPO = "repo-provider";
  private static final String OWN_PROJECT = "p-own";
  private static final String OTHER_PROJECT = "p-other";
  private static final String FOLD = "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0";

  private static final String RELEASE_YML =
      "archetype: java-service\ncontracts:\n  application: qits-thing\n"
          + "  golden-masters: { from: golden-masters/, packages: [maven, npm] }\n";

  private static final Pin MAVEN_PIN = new Pin("maven", "eu.wohlben.qits:qits-thing-golden-masters");
  private static final Pin NPM_PIN = new Pin("npm", "@qits/thing-golden-masters");

  private final StubPins pins = new StubPins();
  private final Map<String, ContractChangeSource.ConsumerProject> projects = new HashMap<>();
  private final List<String> asked = new ArrayList<>();

  private final ContractChangeSource source =
      new ContractChangeSource() {
        @Override
        DownstreamPins pins() {
          return pins;
        }

        @Override
        Optional<ContractChangeSource.ConsumerProject> projectOf(Downstream consumer) {
          return Optional.ofNullable(projects.get(consumer.repository()));
        }
      };

  {
    projects.put("qits-own-frontend", new ContractChangeSource.ConsumerProject(OWN_PROJECT, "own"));
    projects.put("telemetry-service", new ContractChangeSource.ConsumerProject(OTHER_PROJECT, "telemetry"));
    projects.put("telemetry-app", new ContractChangeSource.ConsumerProject(OTHER_PROJECT, "telemetry"));
  }

  @Test
  void aGoldenMasterChangeConsumedByAnotherProjectNeedsThatProjectsAgentOrAPerson() {
    pins.downstream(
        List.of(
            new Downstream("telemetry-service", "row-1", 1),
            new Downstream("telemetry-app", "row-2", 1)));
    pins.pins("telemetry-service", List.of(MAVEN_PIN));
    pins.pins("telemetry-app", List.of(NPM_PIN));

    List<ApprovalRequirement> requirements =
        source.requirementsFor(
            subject(
                List.of(
                    file("golden-masters/a-state/getThing.json"),
                    file("golden-masters/index.json"),
                    file("src/Main.java"))));

    assertEquals(1, requirements.size(), "one requirement per consumer project");
    ApprovalRequirement contract = requirements.get(0);
    assertEquals("contract:telemetry", contract.id());
    assertEquals(
        List.of(ApprovalRole.PERSON, ApprovalRole.agentOf(OTHER_PROJECT)), contract.admits());
    assertTrue(contract.excludesAuthors());
    assertEquals(
        "Golden masters consumed by project telemetry changed (pinned by telemetry-app,"
            + " telemetry-service)",
        contract.reason());
    assertEquals("golden-masters/a-state/getThing.json, golden-masters/index.json", contract.detail());
    assertEquals(
        List.of("agent@telemetry", "person"),
        ApprovalRole.renderAll(contract.admits(), id -> OTHER_PROJECT.equals(id) ? "telemetry" : null));
  }

  @Test
  void aSameProjectConsumerAddsNothing() {
    pins.downstream(List.of(new Downstream("qits-own-frontend", "row-3", 1)));
    pins.pins("qits-own-frontend", List.of(NPM_PIN));

    assertEquals(List.of(), source.requirementsFor(subject(List.of(file("golden-masters/x.json")))));
  }

  @Test
  void aFoldThatChangesNoGoldenMasterAsksNobody() {
    pins.downstream(List.of(new Downstream("telemetry-service", "row-1", 1)));
    pins.pins("telemetry-service", List.of(MAVEN_PIN));

    assertEquals(
        List.of(),
        source.requirementsFor(
            subject(List.of(file("src/golden-masters/x.json"), file("docs/openapi.yml")))));
    assertEquals(0, pins.calls, "qits-maintenance is not asked about a fold without a contract change");
  }

  @Test
  void anUnreadableDownstreamIsNoRequirementAndNoFailure() {
    // Nothing scripted: the closure answers "could not ask".
    assertEquals(List.of(), source.requirementsFor(subject(List.of(file("golden-masters/x.json")))));

    pins.downstream(List.of(new Downstream("telemetry-service", "row-1", 1)));
    // The closure answers, the consumer's pins do not.
    assertEquals(List.of(), source.requirementsFor(subject(List.of(file("golden-masters/x.json")))));

    ContractChangeSource unwired =
        new ContractChangeSource() {
          @Override
          DownstreamPins pins() {
            return null;
          }
        };
    assertEquals(List.of(), unwired.requirementsFor(subject(List.of(file("golden-masters/x.json")))));
  }

  @Test
  void aConsumerPinningAnotherArtifactIsNoConsumerOfTheContract() {
    pins.downstream(List.of(new Downstream("telemetry-service", "row-1", 1)));
    pins.pins(
        "telemetry-service",
        List.of(
            // This repository's consumer pact pinned by its provider, and another app's masters.
            new Pin("maven", "eu.wohlben.qits:qits-thing-service-pacts-telemetry-service"),
            new Pin("maven", "eu.wohlben.qits:qits-other-golden-masters"),
            new Pin("docker", "eu.wohlben.qits:qits-thing-golden-masters")));

    assertEquals(List.of(), source.requirementsFor(subject(List.of(file("golden-masters/x.json")))));
  }

  @Test
  void aConsumerDeeperThanOneHopIsNotAConsumerOfTheContract() {
    pins.downstream(List.of(new Downstream("telemetry-service", "row-1", 2)));
    pins.pins("telemetry-service", List.of(MAVEN_PIN));

    assertEquals(List.of(), source.requirementsFor(subject(List.of(file("golden-masters/x.json")))));
  }

  @Test
  void noPublishedApplicationIsNoContract() {
    pins.downstream(List.of(new Downstream("telemetry-service", "row-1", 1)));
    pins.pins("telemetry-service", List.of(MAVEN_PIN));

    assertEquals(
        List.of(),
        source.requirementsFor(
            subject(List.of(file("golden-masters/x.json")), "archetype: java-service\n")));
    assertEquals(
        List.of(), source.requirementsFor(subject(List.of(file("golden-masters/x.json")), null)));
  }

  @Test
  void theDetailListsAtMostTwentyPaths() {
    pins.downstream(List.of(new Downstream("telemetry-service", "row-1", 1)));
    pins.pins("telemetry-service", List.of(MAVEN_PIN));
    List<CommitFileChangeDto> many =
        IntStream.range(0, 25).mapToObj(i -> file("golden-masters/s/op" + i + ".json")).toList();

    ApprovalRequirement contract = source.requirementsFor(subject(many)).get(0);

    assertEquals(20, contract.detail().split(ApprovalRequirement.PATH_SEPARATOR).length);
    assertTrue(contract.reason().endsWith("; 20 of 25 changed paths listed)"), contract.reason());
  }

  @Test
  void thePackagesAreTheApplicationsGoldenMasters() {
    assertEquals(
        Set.of("eu.wohlben.qits:qits-projects-golden-masters", "@qits/projects-golden-masters"),
        ContractChangeSource.packagesOf("qits-projects"));
    assertEquals("qits-projects", ContractChangeSource.application(RELEASE_YML.replace("qits-thing", "qits-projects")));
    assertEquals(null, ContractChangeSource.application("contracts: [not, a, map]\n"));
    assertEquals(null, ContractChangeSource.application("{{{"));
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private ApprovalSubject subject(List<CommitFileChangeDto> changes) {
    return subject(changes, RELEASE_YML);
  }

  private ApprovalSubject subject(List<CommitFileChangeDto> changes, String releaseYml) {
    FoldChanges reader =
        new FoldChanges() {
          @Override
          public List<CommitFileChangeDto> changes(String repoId, String mergedSha, String pathspec) {
            return changes;
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
            asked.add(path);
            return ContractChangeSource.RELEASE_CONFIG.equals(path) ? releaseYml : null;
          }
        };
    return new ApprovalSubject(
        REPO,
        OWN_PROJECT,
        FOLD,
        ReleaseRequest.State.PENDING,
        ReleaseGates.GateSet.of(Set.of()),
        true,
        reader);
  }

  private static CommitFileChangeDto file(String path) {
    return new CommitFileChangeDto(path, null, "MODIFIED", "100644", "100644", "a", "b", null);
  }

  /** qits-maintenance, scripted; unscripted reads answer "could not ask". */
  private static final class StubPins implements DownstreamPins {
    private List<Downstream> closure;
    private final Map<String, List<Pin>> held = new HashMap<>();
    int calls;

    void downstream(List<Downstream> entries) {
      closure = entries;
    }

    void pins(String repository, List<Pin> pins) {
      held.put(repository, pins);
    }

    @Override
    public Optional<List<Downstream>> downstreamOf(String repoId) {
      calls++;
      return Optional.ofNullable(closure);
    }

    @Override
    public Optional<List<Pin>> pinsOf(String repository) {
      calls++;
      return Optional.ofNullable(held.get(repository));
    }
  }
}
