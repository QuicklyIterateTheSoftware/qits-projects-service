package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Who wrote a comment, and who may edit it</b> (qits-1448), over the wire.
 *
 * <p>An agent's bearer carries an {@code agent} claim (qits-1213); its comment is authored by that
 * agent id, and {@code authorAgentOf} names the qualified id of the work item the agent works on,
 * resolved once at write time through {@code WorkspaceAgentLookup}. Only a comment's author may
 * edit it — an admin and another agent of the same project included — and every comment on the wire
 * says whether the caller reading it may.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}, shared with {@link ScheduleNeedsAPersonDoorTest}
 * so the two boot one application), because the claim lives on a validated bearer: a forwarded
 * header pair carries no token and so no claim.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class WorkCommentAuthorshipTest {

  @Inject WorkEntityService entities;
  @Inject EntityCommentService comments;
  @Inject RecordingWorkspaceAgentLookup lookup;

  private String projectId;
  private WorkEntity ticket;

  /** The work item agent A works on: the {@code qits-1402} of the epic's example. */
  private WorkEntity agentsWork;

  private final String agentA = UUID.randomUUID().toString();
  private final String agentB = UUID.randomUUID().toString();

  @BeforeEach
  void seed() {
    lookup.reset();
    projectId = "authorship-" + UUID.randomUUID().toString().substring(0, 8);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
            });
    ticket =
        entities
            .create(Archetype.TICKET, projectId, ticketWrite("The thread"), "seed")
            .entity();
    agentsWork =
        entities
            .create(Archetype.TICKET, projectId, ticketWrite("Agent A's own"), "seed")
            .entity();
  }

  private static EntityWrite ticketWrite(String title) {
    return EntityWrite.ticket(title, "it occurs", null, "BUG", null);
  }

  // --- callers ---------------------------------------------------------------------------------

  private static RequestSpecification person(String name, String role) {
    return given().header("X-Qits-User", name).header("X-Qits-Roles", role);
  }

  private RequestSpecification agent(String subject, String agentId) {
    MockIdp.TokenBuilder token =
        MockIdp.attach()
            .token()
            .subject(subject)
            .audience("qits-platform")
            .groups("qits:agent")
            .claim("credential_type", "cli")
            .claim("context_kind", "agent-container")
            .claim("project", projectId);
    if (agentId != null) {
      token = token.claim("agent", agentId);
    }
    return given().header("Authorization", "Bearer " + token.mint());
  }

  private String qualified(WorkEntity row) {
    return QualifiedEntityIds.render(projectId, row.number);
  }

  private ValidatableResponse post(RequestSpecification caller, String body) {
    return caller
        .contentType(ContentType.JSON)
        .body(Map.of("body", body))
        .when()
        .post("/projects/api/work/" + ticket.id + "/comments")
        .then();
  }

  private ValidatableResponse patch(RequestSpecification caller, String commentId, String body) {
    return caller
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(Map.of("body", body))
        .when()
        .patch("/projects/api/work/" + ticket.id + "/comments/" + commentId)
        .then();
  }

  private List<Map<String, Object>> thread(RequestSpecification caller) {
    return caller
        .when()
        .get("/projects/api/work/" + ticket.id + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .path("entries.comment");
  }

  // --- the author ------------------------------------------------------------------------------

  @Test
  void anAgentsCommentIsAuthoredByItsAgentAndNamesTheAgentsWorkItem() {
    lookup.stage(agentA, agentsWork.id);

    post(agent("tok-workspace-agent-a", agentA), "from agent A")
        .statusCode(200)
        .body("comment.author", equalTo(agentA))
        .body("comment.authorAgentOf", equalTo(qualified(agentsWork)))
        .body("comment.editable", equalTo(true));

    assertEquals(List.of(agentA), lookup.asked(), "the agent's work item is asked once");
    Map<String, Object> read = thread(person("alice", "qits:admin")).get(0);
    assertEquals(agentA, read.get("author"));
    assertEquals(qualified(agentsWork), read.get("authorAgentOf"), "and resolved again on read");
  }

  @Test
  void aLookupThatCannotSayStillWritesTheCommentNamingNoWorkItem() {
    // Nothing staged: the port answers "could not say".
    post(agent("tok-workspace-agent-a", agentA), "unresolved")
        .statusCode(200)
        .body("comment.author", equalTo(agentA))
        .body("comment.authorAgentOf", nullValue());

    // A port that breaks its contract costs nothing either.
    lookup.willThrow(new IllegalStateException("a bug in the port"));
    post(agent("tok-workspace-agent-a", agentA), "the port threw")
        .statusCode(200)
        .body("comment.author", equalTo(agentA))
        .body("comment.authorAgentOf", nullValue());

    assertEquals(2, comments.listComments(ticket.id).size());
  }

  @Test
  void withoutTheClaimTheAuthorIsThePrincipal() {
    post(person("alice", "qits:admin"), "a person's remark")
        .statusCode(200)
        .body("comment.author", equalTo("alice"))
        .body("comment.authorAgentOf", nullValue())
        .body("comment.editable", equalTo(true));
    post(agent("tok-workspace-agent-old", null), "an agent bearer without the claim")
        .statusCode(200)
        .body("comment.author", equalTo("tok-workspace-agent-old"))
        .body("comment.authorAgentOf", nullValue());
    post(agent("tok-workspace-agent-bad", "not-a-uuid"), "a claim that is no uuid")
        .statusCode(200)
        .body("comment.author", equalTo("tok-workspace-agent-bad"));
    assertEquals(List.of(), lookup.asked(), "no agent, nothing asked");
  }

  // --- the edit --------------------------------------------------------------------------------

  @Test
  void onlyTheAuthorMayEdit() {
    String byAlice =
        post(person("alice", "qits:admin"), "alice's words").statusCode(200).extract()
            .path("comment.id");
    String byAgentA =
        post(agent("tok-workspace-agent-a", agentA), "agent A's words").statusCode(200).extract()
            .path("comment.id");

    // Another person, an admin at that.
    patch(person("bob", "qits:admin"), byAlice, "bob was here")
        .statusCode(403)
        .body("message", containsString("Only the comment's author may edit it."));
    // An admin on an agent's comment.
    patch(person("bob", "qits:admin"), byAgentA, "bob was here").statusCode(403);
    // Another agent of the same project, and the same agent's credential name with another agent.
    patch(agent("tok-workspace-agent-b", agentB), byAgentA, "B was here").statusCode(403);
    patch(agent("tok-workspace-agent-b", agentB), byAlice, "B was here").statusCode(403);

    assertEquals(
        List.of("alice's words", "agent A's words"),
        comments.listComments(ticket.id).stream().map(c -> c.body).toList(),
        "nothing was written");

    patch(person("alice", "qits:admin"), byAlice, "alice, revised")
        .statusCode(200)
        .body("comment.body", equalTo("alice, revised"))
        .body("comment.author", equalTo("alice"))
        .body("comment.editable", equalTo(true));
    // The author is the agent, whichever credential it holds now.
    patch(agent("tok-workspace-agent-a2", agentA), byAgentA, "agent A, revised")
        .statusCode(200)
        .body("comment.body", equalTo("agent A, revised"))
        .body("comment.author", equalTo(agentA));
  }

  @Test
  void aNonAuthorIsRefusedBeforeThePatchIsJudged() {
    String byAlice =
        post(person("alice", "qits:admin"), "kept").statusCode(200).extract().path("comment.id");

    person("bob", "qits:admin")
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body("{\"colour\":\"red\"}")
        .when()
        .patch("/projects/api/work/" + ticket.id + "/comments/" + byAlice)
        .then()
        .statusCode(403);
    person("alice", "qits:admin")
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body("{\"editable\":true}")
        .when()
        .patch("/projects/api/work/" + ticket.id + "/comments/" + byAlice)
        .then()
        .statusCode(400)
        .body("message", containsString("editable is server-owned"));
  }

  /** A comment an agent wrote under its token subject before qits-1448 stays its to edit. */
  @Test
  void anAgentMayEditALegacyCommentStampedWithItsTokenSubject() {
    String legacy =
        comments.addComment(ticket.id, "written before the claim", "tok-workspace-agent-a").id;

    patch(agent("tok-workspace-agent-a", agentA), legacy, "corrected")
        .statusCode(200)
        .body("comment.body", equalTo("corrected"))
        .body("comment.author", equalTo("tok-workspace-agent-a"));
    patch(agent("tok-workspace-agent-b", agentB), legacy, "not mine").statusCode(403);
  }

  @Test
  void aCommentWithNoAuthorIsEditableByNobody() {
    String unattributed = comments.addComment(ticket.id, "nobody's", null).id;

    patch(person("alice", "qits:admin"), unattributed, "mine now?").statusCode(403);
    assertEquals(false, thread(person("alice", "qits:admin")).get(0).get("editable"));
  }

  @Test
  void editableIsTrueOnlyOnTheCallersOwnComments() {
    post(person("alice", "qits:admin"), "alice").statusCode(200);
    post(person("bob", "qits:admin"), "bob").statusCode(200);
    lookup.stage(agentA, agentsWork.id);
    post(agent("tok-workspace-agent-a", agentA), "agent A").statusCode(200);

    assertEquals(
        List.of(true, false, false),
        thread(person("alice", "qits:admin")).stream().map(c -> c.get("editable")).toList());
    assertEquals(
        List.of(false, true, false),
        thread(person("bob", "qits:admin")).stream().map(c -> c.get("editable")).toList());
    assertEquals(
        List.of(false, false, true),
        thread(agent("tok-workspace-agent-a", agentA)).stream()
            .map(c -> c.get("editable"))
            .toList());
  }
}
