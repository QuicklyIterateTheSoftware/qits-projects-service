package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import eu.wohlben.qits.projects.desktriggers.WaitClosedSource;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection} — which is to say, guards the <em>completeness</em> of the
 * registration, because its correctness is not something this suite can reach.
 *
 * <p><b>Say plainly what a JVM test can and cannot prove here.</b> On a JVM every class reflects
 * whether anyone registered it or not, so nothing below would fail if the annotation were deleted
 * tomorrow, except the assertions that read the annotation itself. Only the <b>native artifact</b>,
 * running against a real qits-events, proves that the registration does its job — qits-ci measured
 * the failure it prevents twice, once as a thrown "no serializer found" and once as an {@code
 * eventId} silently appearing in a payload that must carry no identity. What is checkable is written
 * here: that the registered set still covers every type the wire path touches, and that the one
 * entry named as a string still resolves.
 */
@QuarkusTest
public class EventWireReflectionTest {

  /** The private nested mix-in {@link EventWireReflection} can only name as a string. */
  private static final String MIXIN =
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  @Inject @Any Instance<QitsDurableEventListener> listeners;

  /**
   * By its OWN type, past its {@code @DefaultBean}: the port's injection point is won by the
   * suite's recording fake, so asking for the port here would prove nothing about what ships.
   */
  @Inject Instance<RepositoryRenamedAnnouncer> shippedAnnouncer;

  /** The same, for the second publisher — by its own type, past its {@code @DefaultBean}. */
  @Inject Instance<ReleaseRequestChangedAnnouncer> shippedReleaseAnnouncer;

  /** And the one that says how a release request stands (qits-1153). */
  @Inject Instance<ReleaseRequestStateChangedAnnouncer> shippedStateAnnouncer;

  /** And the one that says an approval requirement changed (epic qits-1038). */
  @Inject Instance<ApprovalRequirementChangedAnnouncer> shippedApprovalRequirementAnnouncer;

  /** And the third, which is the release itself. */
  @Inject Instance<SCMReleaseAnnouncer> shippedScmReleaseAnnouncer;

  /** And the fourth, which publishes both ends of a project's life. */
  @Inject Instance<ProjectLifecycleAnnouncer> shippedProjectAnnouncer;

  /** And the fifth, the one announcement a whole multi-entity transition makes. */
  @Inject Instance<EntityTransitionAnnouncer> shippedTransitionAnnouncer;

  /** And the sixth, which says a comment was written (qits-1660). */
  @Inject Instance<EntityCommentAnnouncer> shippedCommentAnnouncer;

  /** The typed live listener of qits-system's lease event (epic qits-1481). */
  @Inject Instance<eu.wohlben.qits.projects.targethost.TargetLeaseEvents> leaseEvents;

  /**
   * <b>A typed live listener's event is bound by the bus's own mapper</b> (epic qits-1481,
   * qits-1551): {@code TargetLeaseEvents} receives qits-system's {@code TargetLeaseChanged} as the
   * local record, so that record must be registered — and the bean must ship, or nothing listens.
   * The JVM suite reads the event either way; only the native binary fails without the line, which
   * the workspace cutover proved twice.
   */
  @Test
  public void theLeaseEventTheDesksListenForIsRegistered() {
    assertTrue(leaseEvents.isResolvable(), "TargetLeaseEvents ships as a bean");
    Class<?> bound = leaseEvents.get().eventType();
    assertEquals(eu.wohlben.qits.projects.targethost.TargetLeaseChanged.class, bound);
    assertEquals("TargetLeaseChanged", bound.getSimpleName(), "the simple name is the wire name");
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertTrue(
        Set.of(registration.targets()).contains(bound),
        "TargetLeaseChanged is bound by the bus and must be registered for native-image");
  }

  @Test
  public void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            SCMPublishCommit.class,
            SCMPublishTag.class,
            SCMDeleteBranch.class,
            SCMDeleteTag.class,
            RepositoryRenamed.class,
            ReleaseRequestChanged.class,
            ReleaseRequestStateChanged.class,
            ApprovalRequirementChanged.class,
            SCMRelease.class,
            ProjectCreated.class,
            ProjectChanged.class,
            ProjectDeleted.class,
            EntityTransitioned.class,
            EntityTransitioned.Entity.class,
            EntityCommented.class,
            BuildStatusListener.BuildVerdictPayload.class,
            ReleasePipelineRunListener.PipelineRunPayload.class,
            DeploymentActiveListener.DeploymentActivePayload.class,
            WaitClosedSource.DeskWaitClosedPayload.class,
            eu.wohlben.qits.projects.desktriggers.CapacityOpenedSource.AgentLeftPayload.class,
            eu.wohlben.qits.projects.targethost.TargetLeaseChanged.class,
            EventEnvelope.class,
            EventFrame.class),
        Set.of(registration.targets()),
        "the four SCM records and the five bound consumption payloads in, RepositoryRenamed,"
            + " ReleaseRequestChanged, ReleaseRequestStateChanged, SCMRelease, the three project lifecycle events and the"
            + " transition's payload PAIR and EntityCommented out, the PUT body, the frame — one more wire type"
            + " means a line here, and a nested payload record means two");
  }

  /**
   * The publishing half of the same rule. A consumed type is named by a listener's {@code
   * signatures()} and the test below reads it off the bean; a <em>published</em> one has no such
   * declaration to read, so the announcer's own event class is asserted by name — and it is the one
   * whose absence fails inside {@code CanonicalJson}, before an envelope exists, losing the event
   * rather than delaying it.
   */
  @Test
  public void thePublishedEventTypesAreRegistered() {
    Set<Class<?>> targets =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets());
    assertTrue(
        targets.contains(RepositoryRenamed.class),
        "RepositoryRenamedAnnouncer publishes this; an unregistered payload is a lost announcement");
    assertTrue(
        targets.contains(ReleaseRequestChanged.class),
        "ReleaseRequestChangedAnnouncer publishes this, and it is the only thing that tells qits-ci"
            + " a release request's fold exists to build");
    assertTrue(
        targets.contains(ReleaseRequestStateChanged.class),
        "ReleaseRequestStateChangedAnnouncer publishes this; it is what wakes an agent waiting on a"
            + " release request");
    assertTrue(
        targets.contains(ApprovalRequirementChanged.class),
        "ApprovalRequirementChangedAnnouncer publishes this (epic qits-1038); an OPEN requirement is"
            + " what a reviewer is sent to");
    assertTrue(
        targets.contains(SCMRelease.class),
        "SCMReleaseAnnouncer publishes this — the event qits-workspaces used to publish and this"
            + " service does since the release became a tag; the WIRE name is the simple class"
            + " name, so a consumer cannot tell the two apart and must not have to");
    assertTrue(
        targets.contains(ProjectCreated.class),
        "ProjectLifecycleAnnouncer publishes this, and the platform edge derives a project's TLS"
            + " SANs from the slug it carries — unregistered, the edge never learns a project"
            + " exists and the create itself succeeds");
    assertTrue(
        targets.contains(ProjectChanged.class),
        "and its middle — the wrapper reconcile publishes this when a project's committed"
            + " supports_environments moves; unregistered, the flag changes and nobody hears");
    assertTrue(
        targets.contains(ProjectDeleted.class),
        "and its closing half, without which the edge holds names it can never retire");
    assertTrue(
        targets.contains(EntityTransitioned.class),
        "EntityTransitionAnnouncer publishes this — the multi-entity transition's one announcement"
            + " for the whole batch");
    assertTrue(
        targets.contains(EntityTransitioned.Entity.class),
        "and its NESTED payload record, which is as invisible to the image builder as the outer"
            + " one: registering only the enclosing record fails in exactly the same place and in"
            + " exactly the same words as registering neither");
    assertTrue(
        targets.contains(EntityCommented.class),
        "EntityCommentAnnouncer publishes this (qits-1660); unregistered, an @desk comment never"
            + " wakes the desk");
  }

  /**
   * The transition announcer, by its OWN type past its {@code @DefaultBean} — the port's injection
   * point is won by the suite's {@code RecordingEntityTransitionAnnouncer}, so asking for the port
   * here would prove nothing about what ships.
   */
  @Test
  public void theEntityTransitionAnnouncerShipsAsABean() {
    assertTrue(!shippedTransitionAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The project lifecycle announcer, by its OWN type past its {@code @DefaultBean} — the port's
   * injection point is won by the suite's {@code RecordingProjectAnnouncer}, so asking for the port
   * here would prove nothing about what ships.
   */
  @Test
  public void theEntityCommentAnnouncerShipsAsABean() {
    assertTrue(!shippedCommentAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  @Test
  public void theProjectLifecycleAnnouncerShipsAsABean() {
    assertTrue(!shippedProjectAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  @Test
  public void theApprovalRequirementAnnouncerShipsAsABean() {
    assertTrue(
        !shippedApprovalRequirementAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  @Test
  public void theReleaseRequestStateAnnouncerShipsAsABean() {
    assertTrue(!shippedStateAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  @Test
  public void theReleaseRequestAnnouncerShipsAsABean() {
    assertTrue(!shippedReleaseAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The release announcement, whose absence is the loudest of the three: a release would land, the
   * tag would exist, and no consumer of {@code SCMRelease} — every release pipeline on the platform
   * — would ever hear about it.
   */
  @Test
  public void theScmReleaseAnnouncerShipsAsABean() {
    assertTrue(!shippedScmReleaseAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The replica's signature is the original's, which is the whole of the claim that the publisher
   * moved and the event did not. {@code QitsEvent.signature()} is the simple class name, so the
   * package this record lives in is invisible on the wire — and a rename here would silently mint a
   * second event nobody subscribes to.
   *
   * <p><b>The component LIST is pinned, and qits-ci holds a transcription of it.</b> {@code
   * ScmReleaseContractTest} over there declares a local record whose components are copied from this
   * one and runs it through the same {@code CanonicalJson}; nothing in either build can see the
   * other, so this assertion and that transcription are the two ends of the contract. A change here
   * is a change there, in the same campaign — which {@code commitSha} was, which {@code priority}
   * is, which {@code releaseRequestId} is, and which {@code releaseRequestQualifiedId} is (qits-1158).
   */
  @Test
  public void theReplicatedReleaseEventKeepsTheWireNameAndTheFiveFieldsItAlwaysHad() {
    assertEquals("SCMRelease", SCMRelease.class.getSimpleName());
    assertEquals(
        java.util.List.of(
            "eventId",
            "projectId",
            "repository",
            "repositoryName",
            "branch",
            "version",
            "commitSha",
            "releaseRequestId",
            "occurredAt",
            "priority",
            "releaseRequestQualifiedId"),
        java.util.Arrays.stream(SCMRelease.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList(),
        "the five qits-workspaces' record carried, in their order, plus commitSha,"
            + " releaseRequestId, priority and releaseRequestQualifiedId — eventId and occurredAt"
            + " are components the canonical mix-in keeps out of the payload, leaving the nine a"
            + " consumer selects on");
  }

  /**
   * <b>Additive means the wire form of an old release is byte-identical.</b> The five original
   * fields are what every existing consumer selects on, and neither new component may disturb one of
   * them; and because {@code CanonicalJson} includes {@code NON_NULL} only, an event published
   * without a {@code commitSha} or a {@code priority} — a replay from before those fields, or any
   * publisher that has not grown them — carries no such key at all rather than a null. That absence
   * is the compatibility arm qits-ci's trigger engine falls back on, so it is pinned here at the
   * source rather than only asserted at the consumer.
   */
  @Test
  public void aReleaseWithoutACommitShaIsTheOlderPayloadExactly() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var withSha =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new SCMRelease(
                    "p-1",
                    "r-1",
                    "qits-ci-service",
                    "release/9f2c1a7e",
                    "2026.905.60215",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    "9f2c1a7e",
                    java.time.Instant.parse("2026-09-05T06:02:15Z"),
                    "HIGH")));
    var without =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new SCMRelease(
                    "p-1",
                    "r-1",
                    "qits-ci-service",
                    "release/9f2c1a7e",
                    "2026.905.60215",
                    null,
                    null,
                    java.time.Instant.parse("2026-09-05T06:02:15Z"),
                    null)));

    assertEquals(
        "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
        withSha.get("commitSha").asText(),
        "the new coordinate is on the wire when there is one");
    assertEquals(
        "HIGH", withSha.get("priority").asText(), "and so is what the release was worth");
    assertTrue(
        !without.has("commitSha"),
        "NON_NULL: an absent commit sha is an absent KEY, which is what a pre-field replay looks"
            + " like and what the consumer's fallback is written against");
    assertTrue(
        !without.has("priority"),
        "the same for the priority: nothing states one on a replay, and a consumer must read that"
            + " as 'the release said nothing' rather than as a value");
    for (String field :
        java.util.List.of("branch", "projectId", "repository", "repositoryName", "version")) {
      assertEquals(
          withSha.get(field),
          without.get(field),
          field + " moved with commitSha, so the addition is not additive after all");
    }
  }

  /**
   * The same additive pin for {@code releaseRequestId}, the key the publish phase of a release
   * pipeline is recognised by.
   *
   * <p>It exists because {@code branch} is not that key: it names the request's backing branch,
   * {@code release/<id>}, and qits-projects <em>deletes that ref in the same operation that creates
   * the tag</em> — so a consumer parsing the id back out of it is parsing a naming convention off a
   * ref that no longer resolves. The id is the durable half of the pair and travels in its own
   * field. Nullable on the same terms as the two before it: {@code NON_NULL} means an announcer with
   * nothing to say, and a replay from before this field, are one and the same <b>absent key</b>
   * rather than a null — a pre-change event keeps its exact shape.
   */
  @Test
  public void aReleaseWithoutARequestIdIsAnAbsentKeyRatherThanANull() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    java.util.function.Function<String, com.fasterxml.jackson.databind.JsonNode> payload =
        requestId -> {
          try {
            return mapper.readTree(
                eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                    new SCMRelease(
                        "p-1",
                        "r-1",
                        "qits-ci-service",
                        "release/9f2c1a7e",
                        "2026.905.60215",
                        "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                        requestId,
                        java.time.Instant.parse("2026-09-05T06:02:15Z"),
                        "HIGH")));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };

    var withId = payload.apply("9f2c1a7e-1c2b-4d3e-8f90-abcdef012345");
    var without = payload.apply(null);

    assertEquals(
        "9f2c1a7e-1c2b-4d3e-8f90-abcdef012345",
        withId.get("releaseRequestId").asText(),
        "the publish phase's key is on the wire in its own field, not spelled into the branch");
    assertTrue(
        !without.has("releaseRequestId"),
        "NON_NULL: an absent request id is an absent KEY, which is exactly the shape a replay from"
            + " before this field has");
    for (String field :
        java.util.List.of(
            "branch", "commitSha", "priority", "projectId", "repository", "repositoryName",
            "version")) {
      assertEquals(
          withId.get(field),
          without.get(field),
          field + " moved with releaseRequestId, so the addition is not additive after all");
    }
  }

  /**
   * The same additive pin, one event over, for the closure qits-ci orders its queue with.
   *
   * <p><b>{@code downstreamTechnicalComponents} is LAST and nullable, and null is not {@code [].}</b>
   * A list travels as a JSON array in the order it was given — the order IS the answer, nearest
   * first, and a consumer that re-sorted it would build in a different order — while null is an
   * absent KEY, which is exactly the payload every {@code ReleaseRequestChanged} published before
   * this field has. That absence is what qits-ci reads as "unknown, no constraint", so it is pinned
   * here at the source as well as in that repository's hand-kept transcription. Every field the event
   * already had must be byte-identical across the two, or the addition is not additive.
   */
  @Test
  public void aChangeWithoutADownstreamClosureIsTheOlderPayloadExactly() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var withList =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new ReleaseRequestChanged(
                    "p-1",
                    "r-1",
                    "qits-ui-components-jslib",
                    "9f2c1a7e",
                    "release/9f2c1a7e",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    java.time.Instant.parse("2026-09-05T06:02:15Z"),
                    "HIGH",
                    java.util.List.of("qits-ci-frontend", "qits-ci-service"))));
    var without =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new ReleaseRequestChanged(
                    "p-1",
                    "r-1",
                    "qits-ui-components-jslib",
                    "9f2c1a7e",
                    "release/9f2c1a7e",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    java.time.Instant.parse("2026-09-05T06:02:15Z"),
                    "HIGH",
                    null)));

    assertTrue(
        withList.get("downstreamTechnicalComponents").isArray(),
        "a closure travels as a JSON array, not as a joined string");
    assertEquals(
        java.util.List.of("qits-ci-frontend", "qits-ci-service"),
        mapper.convertValue(
            withList.get("downstreamTechnicalComponents"),
            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}),
        "in the order it was answered — nearest first is the whole point of carrying it");
    assertTrue(
        !without.has("downstreamTechnicalComponents"),
        "NON_NULL: an unknown closure is an absent KEY, which is what a pre-field replay looks like"
            + " and what qits-ci's fallback is written against");
    for (String field :
        java.util.List.of(
            "projectId",
            "repoId",
            "repoName",
            "releaseRequestId",
            "backingBranch",
            "mergedSha",
            "changedAt",
            "priority")) {
      assertEquals(
          withList.get(field),
          without.get(field),
          field + " moved with the closure, so the addition is not additive after all");
    }
  }

  /**
   * qits-1158: both events qits-ci reads carry the request's qualified id beside its uuid, under
   * {@code releaseRequestQualifiedId}; the older constructors leave the key out.
   */
  @Test
  public void bothReleaseEventsCarryTheQualifiedId() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var changed =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new ReleaseRequestChanged(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "9f2c1a7e",
                    "qits-projects-service-rr-12",
                    "release/qits-projects-service-rr-12",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    java.time.Instant.parse("2026-10-10T06:02:15Z"),
                    "LOWEST",
                    java.util.List.of(),
                    "DONE")));
    var released =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new SCMRelease(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "release/qits-projects-service-rr-12",
                    "2026.1010.60215",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    "9f2c1a7e",
                    java.time.Instant.parse("2026-10-10T06:02:15Z"),
                    "HIGH",
                    "qits-projects-service-rr-12")));

    assertEquals("9f2c1a7e", changed.get("releaseRequestId").asText());
    assertEquals(
        "qits-projects-service-rr-12", changed.get("releaseRequestQualifiedId").asText());
    assertEquals("release/qits-projects-service-rr-12", changed.get("backingBranch").asText());
    assertEquals("9f2c1a7e", released.get("releaseRequestId").asText());
    assertEquals(
        "qits-projects-service-rr-12", released.get("releaseRequestQualifiedId").asText());
    assertTrue(
        !mapper
            .readTree(
                eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                    new SCMRelease(
                        "p-1",
                        "r-1",
                        "qits-projects-service",
                        "release/9f2c1a7e",
                        "2026.1010.60215",
                        null,
                        "9f2c1a7e",
                        java.time.Instant.parse("2026-10-10T06:02:15Z"),
                        null)))
            .has("releaseRequestQualifiedId"),
        "NON_NULL: the older shape has no key");
  }

  /**
   * qits-1133's field, pinned the same way: {@code preRun} travels as the word it was given ({@code
   * PENDING}, {@code DONE}) and an absent one is an absent KEY — what every announcement before the
   * field looked like, and what qits-ci's filter reads as DONE. Every other field is byte-identical.
   */
  @Test
  public void aChangeWithoutAPreRunIsTheOlderPayloadExactly() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var pending =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new ReleaseRequestChanged(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "9f2c1a7e",
                    "release/9f2c1a7e",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    java.time.Instant.parse("2026-10-10T06:02:15Z"),
                    "LOWEST",
                    java.util.List.of(),
                    "PENDING")));
    var without =
        mapper.readTree(
            eu.wohlben.qits.eventstream.control.CanonicalJson.payload(
                new ReleaseRequestChanged(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "9f2c1a7e",
                    "release/9f2c1a7e",
                    "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af",
                    java.time.Instant.parse("2026-10-10T06:02:15Z"),
                    "LOWEST",
                    java.util.List.of())));

    assertEquals("PENDING", pending.get("preRun").asText());
    assertTrue(!without.has("preRun"), "NON_NULL: no preRun is an absent KEY, read as DONE");
    for (String field :
        java.util.List.of(
            "projectId",
            "repoId",
            "repoName",
            "releaseRequestId",
            "backingBranch",
            "mergedSha",
            "changedAt",
            "priority",
            "downstreamTechnicalComponents")) {
      assertEquals(
          pending.get(field),
          without.get(field),
          field + " moved with preRun, so the addition is not additive after all");
    }
  }

  /**
   * The announcer is a BEAN, which is the whole of how {@code RepositoryService} finds it — an
   * {@code Instance<RepositoryAnnouncer>} that is unsatisfied announces nothing and says nothing
   * about it, which is correct as a configuration and wrong as an accident.
   */
  @Test
  public void theRenameAnnouncerShipsAsABean() {
    assertTrue(!shippedAnnouncer.isUnsatisfied(), "an unsatisfied port is a silent one");
  }

  /**
   * The rule that generalises: a durable listener bean is how this service declares it wants an
   * event, and an unregistered one is a binary that subscribes to a signature it cannot deserialize.
   * Written against signatures rather than classes because the durable seam has no {@code
   * eventType()} — a listener takes a frame and reads what it wants — and a signature is an event
   * class's simple name by the same derivation the typed seam used.
   */
  /**
   * Signatures another context publishes and this service binds through a LOCAL payload record —
   * the qits-deployments subscriber shape, where no vocabulary jar carries a type of the wire's
   * name. Each entry maps the signature to the registered record that binds it, so the rule below
   * still refuses a listener whose wire path nothing registered.
   */
  private static final java.util.Map<String, Class<?>> BOUND_BY_LOCAL_RECORD =
      java.util.Map.of(
          "BuildSuccessful", BuildStatusListener.BuildVerdictPayload.class,
          "BuildFailed", BuildStatusListener.BuildVerdictPayload.class,
          "BuildStatusChanged", ReleasePipelineRunListener.PipelineRunPayload.class,
          "DeploymentActive", DeploymentActiveListener.DeploymentActivePayload.class,
          "DeskWaitClosed", WaitClosedSource.DeskWaitClosedPayload.class,
          "AgentObsolete",
          eu.wohlben.qits.projects.desktriggers.CapacityOpenedSource.AgentLeftPayload.class,
          "AgentRemoved",
          eu.wohlben.qits.projects.desktriggers.CapacityOpenedSource.AgentLeftPayload.class);

  @Test
  public void everyDurableListenersSignatureNamesARegisteredType() {
    Set<Class<?>> targets =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets());
    Set<String> registered = targets.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    for (QitsDurableEventListener listener : listeners) {
      for (String signature : listener.signatures()) {
        if (QitsRawEventListener.ALL.equals(signature)) {
          continue;
        }
        Class<?> localRecord = BOUND_BY_LOCAL_RECORD.get(signature);
        if (localRecord != null) {
          assertTrue(
              targets.contains(localRecord),
              signature + " is bound by " + localRecord.getName() + ", which is not registered");
          continue;
        }
        assertTrue(
            registered.contains(signature),
            listener.getClass().getName()
                + " listens for "
                + signature
                + ", which no registered type is named after");
      }
    }
  }

  /**
   * The listener is a BEAN, which is the whole of how it is registered — {@code EventDispatcher}
   * injects {@code Instance<QitsDurableEventListener>}, so being injectable is being subscribed. A
   * listener ArC removed would subscribe to nothing, be swept for nothing, and say nothing about it.
   */
  @Test
  public void theBackupTriggerListenerIsDiscoverableAsADurableListener() {
    assertTrue(
        listeners.stream().anyMatch(ScmBackupTriggerListener.class::isInstance),
        "a removed listener is a silent one");
  }

  /**
   * The publish phase's own subscription. Its absence is the quietest failure in the flow: releases
   * would go on happening, deployments would go on going active, and {@code main} would simply never
   * move again — with nothing failing anywhere to say so.
   */
  @Test
  public void theDeploymentActiveListenerIsDiscoverableAsADurableListener() {
    assertTrue(
        listeners.stream().anyMatch(DeploymentActiveListener.class::isInstance),
        "a removed listener is a silent one — and here it is main that stops being finalized");
  }

  /**
   * The publish phase's temporary half has NO listener of its own any more, and that is the fix of
   * 2026-09-04 rather than an omission. It used to consume qits-ci's {@code SoftwareRelease}, an
   * event only a repository carrying its own {@code ci-event-release.yml} recipe ever emitted, back
   * when those files existed, so every
   * recipe-less repository — every SPA — released tags that never reached {@code main}. The
   * deployability fork hangs off this service's OWN release now ({@code
   * ReleaseFinalization.onReleased}, plus its catch-up sweep), which needs no subscription at all.
   */
  @Test
  public void theNonDeployableForkNeedsNoSubscriptionOfItsOwn() {
    assertTrue(
        listeners.stream().noneMatch(l -> l.consumerId().equals("projects-non-deployable-publish")),
        "the SoftwareRelease-driven gate is gone; nothing should subscribe on its behalf");
  }

  @Test
  public void theBuildStatusListenerIsDiscoverableAsADurableListener() {
    assertTrue(
        listeners.stream().anyMatch(BuildStatusListener.class::isInstance),
        "a removed listener is a silent one — and here it is a build gate that never resolves");
  }

  /**
   * The string entry, kept honest. A rename or a move of the mix-in would otherwise leave a
   * registration naming nothing, and the consequence is not a crash but {@code eventId} appearing in
   * a canonical payload.
   */
  @Test
  public void theMixinNamedByStringStillExistsAndStillHidesTheEnvelopesFields() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertEquals(
        MIXIN,
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames())
            .iterator()
            .next());
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(
        eventId.getAnnotation(JsonIgnore.class),
        "the mix-in is registered because this @JsonIgnore is read by reflection");
  }
}
