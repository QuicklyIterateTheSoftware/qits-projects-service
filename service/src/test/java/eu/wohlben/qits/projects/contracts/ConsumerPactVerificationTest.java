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
import org.junit.jupiter.api.Assumptions;
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
 * application over real HTTP ({@link HttpTestTarget} at the test port), as {@link
 * ContractBearers#consumerCaller()} where the pact carries no credential, exactly as {@link
 * GoldenMasterRecordingTest}'s REST-assured calls run.
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
 *       door (scheduling, qits-887) answer as it does for them, and
 *       exactly what {@link GoldenMasterRecordingTest} sends when it records the answer the consumer
 *       wrote against. Any other trigger (a CLI command, a service's schedule) calls as the plain
 *       consumer caller. A state that returns an {@code authorization} param
 *       grants it ({@link ContractBearers}), so the pact's {@code Authorization} header arrives as the machine identity the edge
 *       forwards.
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
 * <p><b>A pinned pact that is wrong is named in {@link #KNOWN_WRONG}, by its jar's version</b>
 * (qits-maintenance-service's pattern), and its interaction is reported as skipped with the reason
 * — never answered by bending the provider to it. The entry is for that version alone: the bump
 * that carries the consumer's fix verifies the interaction for real, and a bump that does not fix
 * it goes red here.
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

  /**
   * A pinned pact interaction this provider cannot honour, and why: the consumer must fix it.
   * {@code state}, when not null, narrows the entry to the interaction set up in that provider
   * state — for a description a consumer reuses across several interactions (qits-150). {@code
   * description}, when null, names every interaction of that version set up in {@code state}: a
   * provider state this provider retired (qits-1899), every interaction of which asks for what is
   * gone.
   */
  record KnownWrong(
      String consumer, String version, String description, String state, String reason) {

    KnownWrong(String consumer, String version, String description, String reason) {
      this(consumer, version, description, null, reason);
    }

    /** Every interaction of {@code consumer}'s {@code version} set up in the retired {@code state}. */
    static KnownWrong inRetiredState(String consumer, String version, String state) {
      return new KnownWrong(consumer, version, null, state, RETIRED + ": the state is gone");
    }

    boolean names(Pact pact, Interaction interaction) {
      return consumer.equals(pact.getConsumer().getName())
          && (description == null || description.equals(interaction.getDescription()))
          && String.valueOf(pact.getSource()).contains("/" + version + "/")
          && (state == null
              || interaction.getProviderStates().stream()
                  .anyMatch(each -> state.equals(each.getName())));
    }
  }

  /** The reason every qits-1899 entry below gives: campaigns, milestones and dependsOn retired. */
  static final String RETIRED = "qits-1899: retired";

  /** The landing app's rollback version that still reads campaigns, milestones and dependsOn. */
  private static final String LANDING_ROLLBACK = "2026.1105.153734";

  /** The CLI's rollback version that still reads campaigns, milestones and dependsOn. */
  private static final String CLI_ROLLBACK = "2026.1105.225130";

  /**
   * The pinned, deployed or rollback consumer pact interactions this provider cannot honour. Empty
   * when every consumer version the gate verifies holds; an entry stays only while its version is
   * pinned, deployed or a rollback target of its consumer.
   *
   * <p>qits-landing-app's 2026.1104.81059 (pinned, and the pact of both its deployed versions,
   * 2026.1104.164813 and 2026.1104.131632) moves a VERIFIED ticket to DONE through the status door
   * from its finish and move buttons, expecting 200. Since qits-150 no caller moves a ticket to
   * DONE — the platform does, when the wrapper release request carrying its branch is finalized —
   * so the door answers 409 and the golden master records that. Each entry stays until the landing
   * app stops offering the move and re-records, and that release replaces this version everywhere.
   *
   * <p><b>qits-1899 (qits-1908) retired campaigns, milestones and {@code dependsOn}.</b> Two
   * consumer versions still deployed as rollback targets read them: qits-landing-app's {@value
   * #LANDING_ROLLBACK} (the pact of its rollback 2026.1106.123813) and
   * qits-platform-access-monorepo's {@value #CLI_ROLLBACK} (the pact of its rollback
   * 2026.1106.125540). Every interaction they record in a state this provider no longer answers
   * for is named by its state, and the reads they pin the retired fields in by their description
   * — the board's and the item page's {@code milestones}, {@code unmetMilestones} and {@code
   * dependsOn}, the members read, the registry's CAMPAIGN row and the schema's one-entry tag enum.
   * qits-projects-frontend's 2026.1106.155548 (qits-1907, pinned once qits-maintenance's bump
   * lands; the frontend has no deployed version of its own) still pins the CAMPAIGN row of {@code an
   * epic in detail}'s listing (pact-jvm reads an {@code arrayContains} variant by its position, not
   * its {@code index}, so the skipped index 0 is the CAMPAIGN row) and each kind's {@code
   * permitted} list at its length with {@code DEPENDS_ON}; it re-records them. Each entry goes once
   * its version is neither pinned, deployed nor a rollback target.
   *
   * <p>qits-githost-frontend's 2026.1106.155106 (pinned, and also deployed inside
   * qits-githost-service 2026.1106.164015) and qits-projects-frontend's 2026.1106.155548 (pinned)
   * carry {@code @qits/ui-components}' {@code WORK_BRIEFS} block, whose query lost its provider-state
   * generator: it sends the golden master's example ids as a literal query instead of the state's
   * generated ids ({@code @qits/ui-components} 2026.1106.153549 {@code masters.ts}
   * {@code queryFromParams}), so the briefs answer comes back empty. qits-1897 fixes the library and
   * re-records both. Each entry stays until its version is neither pinned, deployed nor a rollback
   * target.
   */
  static final List<KnownWrong> KNOWN_WRONG =
      List.of(
          new KnownWrong(
              "qits-landing-app",
              "2026.1104.81059",
              "finish-ticket: setWorkStatus",
              "a verified ticket",
              "moves a ticket to DONE; since qits-150 the platform alone sets a ticket DONE"),
          new KnownWrong(
              "qits-landing-app",
              "2026.1104.81059",
              "move-work-item: setWorkStatus",
              "a verified ticket",
              "moves a ticket to DONE; since qits-150 the platform alone sets a ticket DONE"),
          // qits-landing-app's rollback: the retired states, then the reads pinning retired fields.
          KnownWrong.inRetiredState(
              "qits-landing-app", LANDING_ROLLBACK, "a ticket waiting on an unmet milestone"),
          KnownWrong.inRetiredState(
              "qits-landing-app", LANDING_ROLLBACK, "a ticket waiting on a met milestone"),
          KnownWrong.inRetiredState(
              "qits-landing-app", LANDING_ROLLBACK, "a campaign member waiting on a milestone"),
          KnownWrong.inRetiredState("qits-landing-app", LANDING_ROLLBACK, "a campaign in detail"),
          KnownWrong.inRetiredState(
              "qits-landing-app",
              LANDING_ROLLBACK,
              "a campaign with a done, a verified and an implementing epic"),
          KnownWrong.inRetiredState(
              "qits-landing-app", LANDING_ROLLBACK, "a campaign with ordered developments"),
          KnownWrong.inRetiredState(
              "qits-landing-app", LANDING_ROLLBACK, "a campaign with work in every phase"),
          KnownWrong.inRetiredState(
              "qits-landing-app", LANDING_ROLLBACK, "an epic in two campaigns"),
          KnownWrong.inRetiredState(
              "qits-landing-app",
              LANDING_ROLLBACK,
              "the second campaign of an epic in two campaigns"),
          new KnownWrong(
              "qits-landing-app",
              LANDING_ROLLBACK,
              "show-project-work-board: listProjectWork",
              RETIRED + ": pins milestones and unmetMilestones on every entry, and a CAMPAIGN row"),
          new KnownWrong(
              "qits-landing-app",
              LANDING_ROLLBACK,
              "show-project-work-board: listWorkMembers",
              RETIRED + ": reads a campaign's members"),
          new KnownWrong(
              "qits-landing-app",
              LANDING_ROLLBACK,
              "show-work-item: getWork",
              RETIRED + ": pins dependsOn, milestones and unmetMilestones"),
          new KnownWrong(
              "qits-landing-app",
              LANDING_ROLLBACK,
              "show-project-work: listProjectWork",
              "a project with refined work",
              RETIRED + ": pins milestones and unmetMilestones on every entry"),
          // qits-platform-access-monorepo's rollback, the same way.
          KnownWrong.inRetiredState(
              "qits-platform-access-monorepo", CLI_ROLLBACK, "a ticket waiting on an unmet milestone"),
          KnownWrong.inRetiredState(
              "qits-platform-access-monorepo", CLI_ROLLBACK, "a ticket waiting on a met milestone"),
          KnownWrong.inRetiredState(
              "qits-platform-access-monorepo",
              CLI_ROLLBACK,
              "a campaign member waiting on a milestone"),
          KnownWrong.inRetiredState(
              "qits-platform-access-monorepo", CLI_ROLLBACK, "a campaign with members to edit"),
          KnownWrong.inRetiredState(
              "qits-platform-access-monorepo", CLI_ROLLBACK, "a campaign in detail"),
          new KnownWrong(
              "qits-platform-access-monorepo",
              CLI_ROLLBACK,
              "qits work create: getWorkArchetypeSchema",
              RETIRED + ": pins the tag enum at one entry; CONDITION joined it"),
          new KnownWrong(
              "qits-platform-access-monorepo",
              CLI_ROLLBACK,
              "qits work update: getWorkArchetypeSchema",
              RETIRED + ": pins the tag enum at one entry; CONDITION joined it"),
          new KnownWrong(
              "qits-platform-access-monorepo",
              CLI_ROLLBACK,
              "qits work details: getWork",
              "an epic in detail",
              RETIRED + ": pins dependsOn"),
          new KnownWrong(
              "qits-platform-access-monorepo",
              CLI_ROLLBACK,
              "qits work transition: getWork",
              "a ticket with a comment",
              RETIRED + ": pins dependsOn"),
          new KnownWrong(
              "qits-platform-access-monorepo",
              CLI_ROLLBACK,
              "qits work status: listWorkArchetypes",
              "the archetype registry",
              RETIRED + ": pins the CAMPAIGN row of the registry"),
          // qits-projects-frontend's qits-1907 pact, pinned once the bump lands.
          new KnownWrong(
              "qits-projects-frontend",
              "2026.1106.181420",
              "show-work-item-actions: listWorkArchetypes",
              "the archetype registry",
              RETIRED + ": pins each kind's permitted list at its length with DEPENDS_ON"),
          new KnownWrong(
              "qits-projects-frontend",
              "2026.1106.155548",
              "show-work-desk: listProjectWork",
              "an epic in detail",
              RETIRED + ": pins the CAMPAIGN row of the listing (arrayContains by position)"),
          new KnownWrong(
              "qits-projects-frontend",
              "2026.1106.155548",
              "show-work-item-actions: listWorkArchetypes",
              "the archetype registry",
              RETIRED + ": pins each kind's permitted list at its length with DEPENDS_ON"),
          // qits-1897: @qits/ui-components' WORK_BRIEFS block lost its query generator.
          new KnownWrong(
              "qits-githost-frontend",
              "2026.1106.155106",
              "show-work-brief: listProjectWorkBriefs",
              "a project with work to brief",
              "sends the golden master's example ids as a literal query instead of the state's"
                  + " generated ids (@qits/ui-components 2026.1106.153549 masters.ts"
                  + " queryFromParams), so the briefs answer is empty"),
          new KnownWrong(
              "qits-projects-frontend",
              "2026.1106.155548",
              "show-work-brief: listProjectWorkBriefs",
              "a project with work to brief",
              "sends the golden master's example ids as a literal query instead of the state's"
                  + " generated ids (@qits/ui-components 2026.1106.153549 masters.ts"
                  + " queryFromParams), so the briefs answer is empty"));

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    // First, so a known-wrong interaction in a state this provider retired (qits-1899) is skipped
    // for its reason rather than failed for the state.
    for (KnownWrong wrong : KNOWN_WRONG) {
      if (wrong.names(pact, interaction)) {
        Assumptions.abort(
            consumer + " " + wrong.version() + " '" + interaction.getDescription() + "': "
                + wrong.reason());
      }
    }
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
    if (!request.containsHeader("Authorization")) {
      // An interaction without a credential of its own is the consumer's caller, named rather than
      // left to a forward-auth dev user (epic qits-543).
      request.setHeader("Authorization", ContractBearers.consumerCaller());
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

  @State(ProviderStates.A_TICKET_ITS_AGENT_WAITS_ON_A_DECLARED_WAIT)
  Map<String, String> aTicketItsAgentWaitsOnADeclaredWait() {
    return states.params(ProviderStates.A_TICKET_ITS_AGENT_WAITS_ON_A_DECLARED_WAIT);
  }

  @State(ProviderStates.A_TICKET_WITH_A_COMMENT)
  Map<String, String> aTicketWithAComment() {
    return states.params(ProviderStates.A_TICKET_WITH_A_COMMENT);
  }

  @State(ProviderStates.A_TICKET_THAT_NEEDS_ANOTHER)
  Map<String, String> aTicketThatNeedsAnother() {
    return states.params(ProviderStates.A_TICKET_THAT_NEEDS_ANOTHER);
  }

  @State(ProviderStates.AN_EPIC_WITH_A_SKETCH_TO_INLINE)
  Map<String, String> anEpicWithASketchToInline() {
    return states.params(ProviderStates.AN_EPIC_WITH_A_SKETCH_TO_INLINE);
  }

  @State(ProviderStates.A_TICKET_WITH_A_DESIGN)
  Map<String, String> aTicketWithADesign() {
    return states.params(ProviderStates.A_TICKET_WITH_A_DESIGN);
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

  @State(ProviderStates.A_PROJECT_WITH_WORK_TO_BRIEF)
  Map<String, String> aProjectWithWorkToBrief() {
    return states.params(ProviderStates.A_PROJECT_WITH_WORK_TO_BRIEF);
  }

  @State(ProviderStates.A_PROJECT_WITH_WORK_IN_EVERY_STATUS)
  Map<String, String> aProjectWithWorkInEveryStatus() {
    return states.params(ProviderStates.A_PROJECT_WITH_WORK_IN_EVERY_STATUS);
  }

  @State(ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS)
  Map<String, String> anEpicWithFeaturesAndTasks() {
    return states.params(ProviderStates.AN_EPIC_WITH_FEATURES_AND_TASKS);
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

  @State(ProviderStates.A_PROJECT_WITH_NO_WORK)
  Map<String, String> aProjectWithNoWork() {
    return states.params(ProviderStates.A_PROJECT_WITH_NO_WORK);
  }

  @State(ProviderStates.A_PROJECT_WITH_IDEAS)
  Map<String, String> aProjectWithIdeas() {
    return states.params(ProviderStates.A_PROJECT_WITH_IDEAS);
  }

  @State(ProviderStates.THREE_IDEAS)
  Map<String, String> threeIdeas() {
    return states.params(ProviderStates.THREE_IDEAS);
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

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_CHECKED_BY_QA)
  Map<String, String> aReleaseRequestWithAnAutomationCheckedByQa() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_CHECKED_BY_QA);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_HELD_BY_AN_UPSTREAM_RELEASE)
  Map<String, String> aReleaseRequestHeldByAnUpstreamRelease() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_HELD_BY_AN_UPSTREAM_RELEASE);
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

  @State(ProviderStates.A_PROJECT_WHOSE_WORK_HAS_WORKSPACES)
  Map<String, String> aProjectWhoseWorkHasWorkspaces() {
    return states.params(ProviderStates.A_PROJECT_WHOSE_WORK_HAS_WORKSPACES);
  }

  @State(ProviderStates.A_REPORTED_TICKET)
  Map<String, String> aReportedTicket() {
    return states.params(ProviderStates.A_REPORTED_TICKET);
  }

  @State(ProviderStates.THREE_REPORTED_TICKETS)
  Map<String, String> threeReportedTickets() {
    return states.params(ProviderStates.THREE_REPORTED_TICKETS);
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

  @State(ProviderStates.AN_ITEM_THAT_OTHER_ITEMS_NAME_AS_THEIR_SUPERSEDED_BY)
  Map<String, String> anItemThatOtherItemsNameAsTheirSupersededBy() {
    return states.params(ProviderStates.AN_ITEM_THAT_OTHER_ITEMS_NAME_AS_THEIR_SUPERSEDED_BY);
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

  @State(ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID)
  Map<String, String> noProjectWithTheGivenId() {
    return states.params(ProviderStates.NO_PROJECT_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID)
  Map<String, String> noRepositoryWithTheGivenId() {
    return states.params(ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.AN_AGENT_LAUNCH_IMAGE_IN_USE)
  Map<String, String> anAgentLaunchImageInUse() {
    return states.params(ProviderStates.AN_AGENT_LAUNCH_IMAGE_IN_USE);
  }

  @State(ProviderStates.A_PROJECT_AND_AN_UNADOPTED_REPOSITORY_ON_THE_GIT_HOST)
  Map<String, String> aProjectAndAnUnadoptedRepositoryOnTheGitHost() {
    return states.params(ProviderStates.A_PROJECT_AND_AN_UNADOPTED_REPOSITORY_ON_THE_GIT_HOST);
  }

  @State(ProviderStates.A_PROJECT_TO_CREATE_A_REPOSITORY_IN)
  Map<String, String> aProjectToCreateARepositoryIn() {
    return states.params(ProviderStates.A_PROJECT_TO_CREATE_A_REPOSITORY_IN);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE)
  Map<String, String> aRepositoryWithABranchToRelease() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_RELEASE);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_OPEN_TO_ANOTHER_BRANCH)
  Map<String, String> aReleaseRequestOpenToAnotherBranch() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_OPEN_TO_ANOTHER_BRANCH);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_RELEASED_RELEASE_REQUESTS)
  Map<String, String> aRepositoryWithReleasedReleaseRequests() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_RELEASED_RELEASE_REQUESTS);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_TAGGED_RELEASE_WHOSE_REQUEST_WENT_OBSOLETE_MID_PUBLISH)
  Map<String, String> aTaggedReleaseObsoletedMidPublish() {
    return states.params(
        ProviderStates.A_REPOSITORY_WITH_A_TAGGED_RELEASE_WHOSE_REQUEST_WENT_OBSOLETE_MID_PUBLISH);
  }

  @State(ProviderStates.A_FROZEN_RELEASE_REQUEST_WITH_A_QUEUED_NEXT_REQUEST)
  Map<String, String> aFrozenReleaseRequestWithAQueuedNextRequest() {
    return states.params(ProviderStates.A_FROZEN_RELEASE_REQUEST_WITH_A_QUEUED_NEXT_REQUEST);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_RELEASE_NOT_MERGED_TO_MAIN)
  Map<String, String> aRepositoryWithAReleaseNotMergedToMain() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_RELEASE_NOT_MERGED_TO_MAIN);
  }

  @State(ProviderStates.THE_ARCHETYPE_REGISTRYS_UPDATE_SCHEMA)
  Map<String, String> theArchetypeRegistrysUpdateSchema() {
    return states.params(ProviderStates.THE_ARCHETYPE_REGISTRYS_UPDATE_SCHEMA);
  }

  @State(ProviderStates.THE_ARCHETYPE_REGISTRYS_TRANSITION_SCHEMA)
  Map<String, String> theArchetypeRegistrysTransitionSchema() {
    return states.params(ProviderStates.THE_ARCHETYPE_REGISTRYS_TRANSITION_SCHEMA);
  }

  @State(ProviderStates.A_TICKET_WITH_A_DISPATCHED_AGENT)
  Map<String, String> aTicketWithADispatchedAgent() {
    return states.params(ProviderStates.A_TICKET_WITH_A_DISPATCHED_AGENT);
  }

  @State(ProviderStates.REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS)
  Map<String, String> repositoriesWithDecommissionableTags() {
    return states.params(ProviderStates.REPOSITORIES_WITH_DECOMMISSIONABLE_TAGS);
  }


  @State(ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_IS_RUNNING)
  Map<String, String> aProjectWhoseFrontDeskIsRunning() {
    return states.params(ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_IS_RUNNING);
  }

  @State(ProviderStates.A_PROJECT_WHOSE_DESK_RECEIVED_A_TRIGGER_MESSAGE)
  Map<String, String> aProjectWhoseDeskReceivedATriggerMessage() {
    return states.params(ProviderStates.A_PROJECT_WHOSE_DESK_RECEIVED_A_TRIGGER_MESSAGE);
  }

  @State(ProviderStates.A_PROJECT_WHOSE_DESK_WAS_ADDRESSED_IN_A_COMMENT)
  Map<String, String> aProjectWhoseDeskWasAddressedInAComment() {
    return states.params(ProviderStates.A_PROJECT_WHOSE_DESK_WAS_ADDRESSED_IN_A_COMMENT);
  }

  @State(ProviderStates.A_PROJECT_WITH_AN_OPEN_ISSUE)
  Map<String, String> aProjectWithAnOpenIssue() {
    return states.params(ProviderStates.A_PROJECT_WITH_AN_OPEN_ISSUE);
  }

  @State(ProviderStates.AN_IDEA_ENABLED_BY_ANOTHER_IDEA)
  Map<String, String> anIdeaEnabledByAnotherIdea() {
    return states.params(ProviderStates.AN_IDEA_ENABLED_BY_ANOTHER_IDEA);
  }

  @State(ProviderStates.BRANCH_TICKET_QITS_9001_IS_RELEASED_IN_QITS_DEMO_SERVICE)
  Map<String, String> branchTicketQits9001IsReleasedInQitsDemoService() {
    return states.params(ProviderStates.BRANCH_TICKET_QITS_9001_IS_RELEASED_IN_QITS_DEMO_SERVICE);
  }

  @State(ProviderStates.A_PROJECT_WITH_VERIFIED_TICKETS_IN_EVERY_WRAPPER_VERDICT)
  Map<String, String> aProjectWithVerifiedTicketsInEveryWrapperVerdict() {
    return states.params(ProviderStates.A_PROJECT_WITH_VERIFIED_TICKETS_IN_EVERY_WRAPPER_VERDICT);
  }

  @State(ProviderStates.A_REPOSITORY_WHOSE_BACKUP_TWIN_HOLDS_UNRELATED_HISTORY)
  Map<String, String> aRepositoryWhoseBackupTwinHoldsUnrelatedHistory() {
    return states.params(ProviderStates.A_REPOSITORY_WHOSE_BACKUP_TWIN_HOLDS_UNRELATED_HISTORY);
  }

  @State(ProviderStates.THE_DEVELOPMENT_GUIDE_SHIPS)
  Map<String, String> theDevelopmentGuideShips() {
    return states.params(ProviderStates.THE_DEVELOPMENT_GUIDE_SHIPS);
  }

  // qits-1934: the states every remaining operation is recorded on.
  @State(ProviderStates.A_PROJECT_SLUG_THAT_NAMES_NO_PROJECT)
  Map<String, String> aProjectSlugThatNamesNoProject() {
    return states.params(ProviderStates.A_PROJECT_SLUG_THAT_NAMES_NO_PROJECT);
  }

  @State(ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_CAN_BE_LEASED)
  Map<String, String> aProjectWhoseFrontDeskCanBeLeased() {
    return states.params(ProviderStates.A_PROJECT_WHOSE_FRONT_DESK_CAN_BE_LEASED);
  }

  @State(ProviderStates.NO_REFINEMENT_WITH_THE_GIVEN_ID)
  Map<String, String> noRefinementWithTheGivenId() {
    return states.params(ProviderStates.NO_REFINEMENT_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.A_REFINEMENT_ROOM_WITH_A_PROMPT_DRAFT)
  Map<String, String> aRefinementRoomWithAPromptDraft() {
    return states.params(ProviderStates.A_REFINEMENT_ROOM_WITH_A_PROMPT_DRAFT);
  }

  @State(ProviderStates.A_REPOSITORY_WHOSE_MAIN_ALREADY_SAYS_IT_IS_ARCHIVED)
  Map<String, String> aRepositoryWhoseMainAlreadySaysItIsArchived() {
    return states.params(ProviderStates.A_REPOSITORY_WHOSE_MAIN_ALREADY_SAYS_IT_IS_ARCHIVED);
  }

  @State(ProviderStates.A_REPOSITORY_WHOSE_BACKUP_TWIN_WAS_RESET)
  Map<String, String> aRepositoryWhoseBackupTwinWasReset() {
    return states.params(ProviderStates.A_REPOSITORY_WHOSE_BACKUP_TWIN_WAS_RESET);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_SECOND_BRANCH)
  Map<String, String> aRepositoryWithASecondBranch() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_SECOND_BRANCH);
  }

  @State(ProviderStates.NO_TECHNICAL_PROCESS_WITH_THE_GIVEN_ID)
  Map<String, String> noTechnicalProcessWithTheGivenId() {
    return states.params(ProviderStates.NO_TECHNICAL_PROCESS_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.A_CONTAINER_REPORTED_ITS_HARNESS_CAPABILITIES)
  Map<String, String> aContainerReportedItsHarnessCapabilities() {
    return states.params(ProviderStates.A_CONTAINER_REPORTED_ITS_HARNESS_CAPABILITIES);
  }

  @State(ProviderStates.EVERY_AGENT_SURFACE_AT_ITS_SHIPPED_DEFAULT)
  Map<String, String> everyAgentSurfaceAtItsShippedDefault() {
    return states.params(ProviderStates.EVERY_AGENT_SURFACE_AT_ITS_SHIPPED_DEFAULT);
  }

  @State(ProviderStates.AN_AGENT_SURFACE_WITH_A_SAVED_REVISION)
  Map<String, String> anAgentSurfaceWithASavedRevision() {
    return states.params(ProviderStates.AN_AGENT_SURFACE_WITH_A_SAVED_REVISION);
  }

  @State(ProviderStates.AN_EXTERNAL_MCP_SERVER_IN_THE_CATALOG)
  Map<String, String> anExternalMcpServerInTheCatalog() {
    return states.params(ProviderStates.AN_EXTERNAL_MCP_SERVER_IN_THE_CATALOG);
  }
}
