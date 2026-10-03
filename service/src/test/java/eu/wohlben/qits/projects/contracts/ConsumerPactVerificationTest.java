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
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
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
  void consumerPactHolds(PactVerificationContext context) {
    try {
      context.verifyInteraction();
    } finally {
      states.cleanUp();
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

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
