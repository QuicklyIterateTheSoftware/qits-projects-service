package eu.wohlben.qits.projects.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running provider</b> (epic qits-546).
 *
 * <p>The pacts come off the test classpath, never out of this tree: each consumer publishes its
 * pact against qits-projects as a jar ({@code pacts/<consumer>_qits-projects-service.json}, both
 * repository names), this repo pins that jar as a test dependency, and qits-maintenance bumps the
 * pin when the consumer releases a changed pact. {@link ClasspathPactLoader} finds them all; an
 * empty classpath fails rather than skipping.
 *
 * <p>Each interaction runs as one {@code @TestTemplate} invocation against this {@code @QuarkusTest}
 * application over real HTTP ({@link HttpTestTarget} at the test port), unauthenticated — the
 * {@code %test} dev user, exactly as {@link GoldenMasterRecordingTest}'s REST-assured calls run.
 *
 * <p><b>Ordering.</b> {@code PlatformStateReset} is a Quarkus before-each callback, so it runs in
 * JUnit's before-each phase; pact-jvm runs the {@code @State} methods in the before-TEST-EXECUTION
 * phase, after every before-each. So the reset truncates first and the state seeds after it, per
 * interaction. The states assume nothing about the database either way.
 *
 * <p><b>States.</b> Every {@code @State} method delegates to {@link ProviderStates} and returns its
 * parameter map, which is what pact-jvm feeds the pact's {@code ProviderState} generators — so
 * {@code ${repositoryId}} becomes the id this run created. pact-jvm cannot dispatch every name to
 * one generic handler (a {@code @State} names its states as compile-time constants and is not told
 * which one it is running), so there is one method per state, and an explicit check in {@link
 * #target} that fails an unknown state with its name and its consumer before pact-jvm gets to it.
 * Adding a state to the registry means adding its one-line method here.
 *
 * <p><b>The request, as the consumer's caller sends it</b> ({@link #asTheConsumerSendsIt}). Two
 * things pact-jvm cannot do for us are done to the prepared request, and nothing else is: neither
 * touches a response or a matching rule.
 *
 * <ul>
 *   <li><b>A browser rides a person's session.</b> An interaction whose {@code qits-trigger.kind} is
 *       {@code ui} is a click in a browser, which carries a session cookie — what lets a person's
 *       door (scheduling, qits-887; a campaign criterion's approval) answer as it does for them, and
 *       exactly what {@link GoldenMasterRecordingTest} sends when it records the answer the consumer
 *       wrote against. Any other trigger (a CLI command, a service's schedule) calls as the plain
 *       {@code %test} dev user, as before.
 *   <li><b>State-param stand-ins in the body are this run's values</b> ({@link
 *       StateParamRequestBody}): a member name or value that is exactly a param's pinned example, or
 *       the golden masters' own {@code "{name}"} placeholder, becomes what the {@code @State}
 *       returned — the {@code ProviderState} generator a JSON key cannot carry ({@code
 *       transitionWork}'s body is keyed by qualified id).
 * </ul>
 *
 * <p><b>References.</b> {@link #target} also fails an interaction that lacks {@code
 * comments.references.qits-call} or {@code qits-trigger}; the verification itself ignores them.
 *
 * <p><b>Integration.</b> Plain pact-jvm, not the Quarkiverse {@code quarkus-pact-provider}
 * extension: its newest release is built against Quarkus 3.14.1 and pins pact-jvm 4.6.17 (the
 * consumers write with 4.6.21), and it puts {@code quarkus-kotlin} into the test application. All
 * it adds is parent-first class loading for pact's classes.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /**
   * The provider as a consumer pact names it: the repository name. The golden-master index keeps
   * the application name ({@link GoldenMasterRecordingTest#PROVIDER}).
   */
  static final String PROVIDER = "qits-projects-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-projects does not answer for — it answers for "
                + states.names());
      }
    }
    // THE REFERENCES. pact-jvm's verification ignores comments.references; this does not. Every
    // interaction must say which provider operation it calls (qits-call) and what on the consumer's
    // side triggers it (qits-trigger), so a consumer that drops them is caught at the provider too.
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context, ClassicHttpRequest request)
      throws IOException {
    try {
      asTheConsumerSendsIt(context, request);
      context.verifyInteraction();
    } finally {
      states.cleanUp();
    }
  }

  /** The two things done to the prepared request — see the class comment. Nothing else. */
  private static void asTheConsumerSendsIt(
      PactVerificationContext context, ClassicHttpRequest request) throws IOException {
    Interaction interaction = context.getInteraction();
    var trigger = interaction.getComments().get("references").asObject().get("qits-trigger");
    var kind = trigger.isObject() ? trigger.asObject().get("kind") : null;
    if (kind != null && kind.isString() && "ui".equals(kind.asString())) {
      request.addHeader(
          "Cookie", PersonCheck.SESSION_COOKIE + "=" + FakeSessionIntrospection.admin("dev"));
    }

    HttpEntity entity = request.getEntity();
    if (entity == null) {
      return;
    }
    Map<String, Object> pinned = new HashMap<>();
    interaction.getProviderStates().forEach(state -> pinned.putAll(state.getParams()));
    Map<String, Object> executed = context.getExecutionContext();
    Object returned = executed == null ? null : executed.get("providerState");
    if (!(returned instanceof Map<?, ?> stateValues)) {
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, ?> values = (Map<String, ?>) stateValues;
    var rewritten = StateParamRequestBody.rewrite(EntityUtils.toByteArray(entity), pinned, values);
    if (rewritten != null) {
      request.setEntity(
          new ByteArrayEntity(
              rewritten.body(),
              entity.getContentType() == null
                  ? ContentType.APPLICATION_JSON
                  : ContentType.parse(entity.getContentType())));
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.A_TICKET_ITS_AGENT_IS_WAITING_ON)
  Map<String, String> aTicketItsAgentIsWaitingOn() {
    return states.params(ProviderStates.A_TICKET_ITS_AGENT_IS_WAITING_ON);
  }

  @State(ProviderStates.A_TICKET_WITH_A_COMMENT)
  Map<String, String> aTicketWithAComment() {
    return states.params(ProviderStates.A_TICKET_WITH_A_COMMENT);
  }

  @State(ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT)
  Map<String, String> aCampaignWithMembersToEdit() {
    return states.params(ProviderStates.A_CAMPAIGN_WITH_MEMBERS_TO_EDIT);
  }

  @State(ProviderStates.AN_EPIC_WITH_A_SKETCH_TO_INLINE)
  Map<String, String> anEpicWithASketchToInline() {
    return states.params(ProviderStates.AN_EPIC_WITH_A_SKETCH_TO_INLINE);
  }

  @State(ProviderStates.A_PROJECT_EXISTS)
  Map<String, String> aProjectExists() {
    return states.params(ProviderStates.A_PROJECT_EXISTS);
  }

  @State(ProviderStates.TWO_PROJECTS_EXIST)
  Map<String, String> twoProjectsExist() {
    return states.params(ProviderStates.TWO_PROJECTS_EXIST);
  }

  @State(ProviderStates.NO_PROJECTS_EXIST)
  Map<String, String> noProjectsExist() {
    return states.params(ProviderStates.NO_PROJECTS_EXIST);
  }

  @State(ProviderStates.A_PROJECT_WITH_ONE_REPOSITORY)
  Map<String, String> aProjectWithOneRepository() {
    return states.params(ProviderStates.A_PROJECT_WITH_ONE_REPOSITORY);
  }

  @State(ProviderStates.A_PROJECT_WITH_3_REPOSITORIES)
  Map<String, String> aProjectWith3Repositories() {
    return states.params(ProviderStates.A_PROJECT_WITH_3_REPOSITORIES);
  }

  @State(ProviderStates.A_PROJECT_WITH_REFINED_WORK)
  Map<String, String> aProjectWithRefinedWork() {
    return states.params(ProviderStates.A_PROJECT_WITH_REFINED_WORK);
  }

  @State(ProviderStates.A_PROJECT_WITH_WORK_IN_EVERY_STATUS)
  Map<String, String> aProjectWithWorkInEveryStatus() {
    return states.params(ProviderStates.A_PROJECT_WITH_WORK_IN_EVERY_STATUS);
  }

  @State(ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS)
  Map<String, String> anEpicWithFeaturesAndTasks() {
    return states.params(ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS);
  }

  @State(ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS)
  Map<String, String> aCampaignWithOrderedDevelopments() {
    return states.params(ProviderStates.A_CAMPAIGN_WITH_ORDERED_DEVELOPMENTS);
  }

  @State(ProviderStates.AN_EPIC_WITH_TASKS_IN_EVERY_STATUS)
  Map<String, String> anEpicWithTasksInEveryStatus() {
    return states.params(ProviderStates.AN_EPIC_WITH_TASKS_IN_EVERY_STATUS);
  }

  @State(ProviderStates.AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED)
  Map<String, String> anEpicWithAFeatureWhoseTasksAreAllVerified() {
    return states.params(ProviderStates.AN_EPIC_WITH_A_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED);
  }

  @State(ProviderStates.AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED)
  Map<String, String> anEpicWithAVerifiedFeatureWhoseTasksAreAllVerified() {
    return states.params(ProviderStates.AN_EPIC_WITH_A_VERIFIED_FEATURE_WHOSE_TASKS_ARE_ALL_VERIFIED);
  }

  @State(ProviderStates.AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES)
  Map<String, String> anImplementingEpicWithFeaturesInMixedStatuses() {
    return states.params(ProviderStates.AN_IMPLEMENTING_EPIC_WITH_FEATURES_IN_MIXED_STATUSES);
  }

  @State(ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC)
  Map<String, String> aCampaignWithADoneAVerifiedAndAnImplementingEpic() {
    return states.params(ProviderStates.A_CAMPAIGN_WITH_A_DONE_A_VERIFIED_AND_AN_IMPLEMENTING_EPIC);
  }

  @State(ProviderStates.A_VERIFIED_EPIC)
  Map<String, String> aVerifiedEpic() {
    return states.params(ProviderStates.A_VERIFIED_EPIC);
  }

  @State(ProviderStates.A_VERIFIED_TICKET)
  Map<String, String> aVerifiedTicket() {
    return states.params(ProviderStates.A_VERIFIED_TICKET);
  }

  @State(ProviderStates.A_TICKET_OF_EVERY_TYPE)
  Map<String, String> aTicketOfEveryType() {
    return states.params(ProviderStates.A_TICKET_OF_EVERY_TYPE);
  }

  @State(ProviderStates.A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED)
  Map<String, String> aVerifiedEpicWithEveryTaskImplemented() {
    return states.params(ProviderStates.A_VERIFIED_EPIC_WITH_EVERY_TASK_IMPLEMENTED);
  }

  @State(ProviderStates.A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED)
  Map<String, String> aDoneEpicWithEveryTaskImplemented() {
    return states.params(ProviderStates.A_DONE_EPIC_WITH_EVERY_TASK_IMPLEMENTED);
  }

  @State(ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE)
  Map<String, String> aCampaignWithWorkInEveryPhase() {
    return states.params(ProviderStates.A_CAMPAIGN_WITH_WORK_IN_EVERY_PHASE);
  }

  @State(ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS)
  Map<String, String> anEpicInTwoCampaigns() {
    return states.params(ProviderStates.AN_EPIC_IN_TWO_CAMPAIGNS);
  }

  @State(ProviderStates.THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS)
  Map<String, String> theSecondCampaignOfAnEpicInTwoCampaigns() {
    return states.params(ProviderStates.THE_SECOND_CAMPAIGN_OF_AN_EPIC_IN_TWO_CAMPAIGNS);
  }

  @State(ProviderStates.A_PROJECT_WITH_NO_WORK)
  Map<String, String> aProjectWithNoWork() {
    return states.params(ProviderStates.A_PROJECT_WITH_NO_WORK);
  }

  @State(ProviderStates.A_REPOSITORY_EXISTS)
  Map<String, String> aRepositoryExists() {
    return states.params(ProviderStates.A_REPOSITORY_EXISTS);
  }

  @State(ProviderStates.A_PROJECT_WITH_PENDING_RELEASE_REQUESTS)
  Map<String, String> aProjectWithPendingReleaseRequests() {
    return states.params(ProviderStates.A_PROJECT_WITH_PENDING_RELEASE_REQUESTS);
  }

  @State(ProviderStates.A_PROJECT_WITH_NO_RELEASE_REQUESTS)
  Map<String, String> aProjectWithNoReleaseRequests() {
    return states.params(ProviderStates.A_PROJECT_WITH_NO_RELEASE_REQUESTS);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL)
  Map<String, String> aReleaseRequestAwaitingApproval() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS)
  Map<String, String> aReleaseRequestAwaitingApprovalWhileItsBuildRuns() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_AWAITING_APPROVAL_WHILE_ITS_BUILD_RUNS);
  }

  @State(ProviderStates.AN_APPROVED_RELEASE_REQUEST)
  Map<String, String> anApprovedReleaseRequest() {
    return states.params(ProviderStates.AN_APPROVED_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_DECLINED_RELEASE_REQUEST)
  Map<String, String> aDeclinedReleaseRequest() {
    return states.params(ProviderStates.A_DECLINED_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_RELEASED_RELEASE_REQUEST)
  Map<String, String> aReleasedReleaseRequest() {
    return states.params(ProviderStates.A_RELEASED_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WHOSE_PUBLISH_FAILED)
  Map<String, String> aReleaseRequestWhosePublishFailed() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WHOSE_PUBLISH_FAILED);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD)
  Map<String, String> aReleaseRequestRejectedByItsBuild() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_REJECTED_BY_ITS_BUILD);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION)
  Map<String, String> aReleaseRequestHeldByAFailedAutomation() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_HELD_BY_A_FAILED_AUTOMATION);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY)
  Map<String, String> aReleaseRequestWithAnAutomationThatDoesNotApply() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_THAT_DOES_NOT_APPLY);
  }

  @State(ProviderStates.A_WITHDRAWN_RELEASE_REQUEST)
  Map<String, String> aWithdrawnReleaseRequest() {
    return states.params(ProviderStates.A_WITHDRAWN_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_CONFLICTED_RELEASE_REQUEST)
  Map<String, String> aConflictedReleaseRequest() {
    return states.params(ProviderStates.A_CONFLICTED_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE)
  Map<String, String> aProjectWithReleaseRequestsInEveryState() {
    return states.params(ProviderStates.A_PROJECT_WITH_RELEASE_REQUESTS_IN_EVERY_STATE);
  }

  @State(ProviderStates.A_REFOLDED_RELEASE_REQUEST)
  Map<String, String> aRefoldedReleaseRequest() {
    return states.params(ProviderStates.A_REFOLDED_RELEASE_REQUEST);
  }

  @State(ProviderStates.A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS)
  Map<String, String> aProjectWithRepositoriesInComponents() {
    return states.params(ProviderStates.A_PROJECT_WITH_REPOSITORIES_IN_COMPONENTS);
  }

  @State(ProviderStates.THE_ARCHETYPE_REGISTRY)
  Map<String, String> theArchetypeRegistry() {
    return states.params(ProviderStates.THE_ARCHETYPE_REGISTRY);
  }

  @State(ProviderStates.A_REPORTED_TICKET)
  Map<String, String> aReportedTicket() {
    return states.params(ProviderStates.A_REPORTED_TICKET);
  }

  @State(ProviderStates.A_REFINED_TICKET)
  Map<String, String> aRefinedTicket() {
    return states.params(ProviderStates.A_REFINED_TICKET);
  }

  @State(ProviderStates.A_READY_FOR_DEV_TICKET)
  Map<String, String> aReadyForDevTicket() {
    return states.params(ProviderStates.A_READY_FOR_DEV_TICKET);
  }

  @State(ProviderStates.AN_IMPLEMENTING_TICKET)
  Map<String, String> anImplementingTicket() {
    return states.params(ProviderStates.AN_IMPLEMENTING_TICKET);
  }

  @State(ProviderStates.AN_IMPLEMENTED_TICKET)
  Map<String, String> anImplementedTicket() {
    return states.params(ProviderStates.AN_IMPLEMENTED_TICKET);
  }

  @State(ProviderStates.A_VERIFYING_TICKET)
  Map<String, String> aVerifyingTicket() {
    return states.params(ProviderStates.A_VERIFYING_TICKET);
  }

  @State(ProviderStates.A_DROPPED_TICKET)
  Map<String, String> aDroppedTicket() {
    return states.params(ProviderStates.A_DROPPED_TICKET);
  }

  @State(ProviderStates.A_REPORTED_EPIC)
  Map<String, String> aReportedEpic() {
    return states.params(ProviderStates.A_REPORTED_EPIC);
  }

  @State(ProviderStates.A_REFINED_EPIC)
  Map<String, String> aRefinedEpic() {
    return states.params(ProviderStates.A_REFINED_EPIC);
  }

  @State(ProviderStates.A_READY_FOR_DEV_EPIC)
  Map<String, String> aReadyForDevEpic() {
    return states.params(ProviderStates.A_READY_FOR_DEV_EPIC);
  }

  @State(ProviderStates.AN_IMPLEMENTING_EPIC)
  Map<String, String> anImplementingEpic() {
    return states.params(ProviderStates.AN_IMPLEMENTING_EPIC);
  }

  @State(ProviderStates.AN_IMPLEMENTED_EPIC)
  Map<String, String> anImplementedEpic() {
    return states.params(ProviderStates.AN_IMPLEMENTED_EPIC);
  }

  @State(ProviderStates.A_VERIFYING_EPIC)
  Map<String, String> aVerifyingEpic() {
    return states.params(ProviderStates.A_VERIFYING_EPIC);
  }

  @State(ProviderStates.A_DROPPED_EPIC)
  Map<String, String> aDroppedEpic() {
    return states.params(ProviderStates.A_DROPPED_EPIC);
  }

  @State(ProviderStates.AN_EPIC_IN_DETAIL)
  Map<String, String> anEpicInDetail() {
    return states.params(ProviderStates.AN_EPIC_IN_DETAIL);
  }

  @State(ProviderStates.A_FEATURE_IN_DETAIL)
  Map<String, String> aFeatureInDetail() {
    return states.params(ProviderStates.A_FEATURE_IN_DETAIL);
  }

  @State(ProviderStates.A_TASK_IN_DETAIL)
  Map<String, String> aTaskInDetail() {
    return states.params(ProviderStates.A_TASK_IN_DETAIL);
  }

  @State(ProviderStates.A_BUG_TICKET_IN_DETAIL)
  Map<String, String> aBugTicketInDetail() {
    return states.params(ProviderStates.A_BUG_TICKET_IN_DETAIL);
  }

  @State(ProviderStates.AN_IMPROVEMENT_TICKET_IN_DETAIL)
  Map<String, String> anImprovementTicketInDetail() {
    return states.params(ProviderStates.AN_IMPROVEMENT_TICKET_IN_DETAIL);
  }

  @State(ProviderStates.A_MAINTENANCE_TICKET_IN_DETAIL)
  Map<String, String> aMaintenanceTicketInDetail() {
    return states.params(ProviderStates.A_MAINTENANCE_TICKET_IN_DETAIL);
  }

  @State(ProviderStates.A_CAMPAIGN_IN_DETAIL)
  Map<String, String> aCampaignInDetail() {
    return states.params(ProviderStates.A_CAMPAIGN_IN_DETAIL);
  }

  @State(ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID)
  Map<String, String> noProjectWithTheGivenId() {
    return states.params(ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID)
  Map<String, String> noRepositoryWithTheGivenId() {
    return states.params(ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID);
  }
}
