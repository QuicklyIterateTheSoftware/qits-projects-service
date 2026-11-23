package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.bus.EntityAssigned;
import eu.wohlben.qits.projects.bus.RecordingEntityAssignAnnouncer;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>An assignee change is announced once, after the commit, from every writer</b> (qits-2518):
 * the create, the PATCH, the PUT-shaped transition and the dispatch all write through {@code
 * AssigneeWrites}, so each publishes one {@link EntityAssigned} per real change and a restatement
 * publishes nothing. {@code changedByName} is the bearer's {@code name} claim.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}), because the {@code name} claim lives on a
 * bearer.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
@WithTestResource(PersonGateIdpTenant.class)
class EntityAssignedTest {

  private static final String WORK = "/projects/api/work/";

  @Inject WorkEntityService entities;
  @Inject EntityDispatchService dispatches;
  @Inject RecordingEntityAssignAnnouncer announced;

  private String projectId;
  private WorkEntity ticket;

  @BeforeEach
  void seed() {
    projectId = "assign-" + UUID.randomUUID().toString().substring(0, 8);
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
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("The totals", "they are wrong", null, "BUG", null),
                "seed")
            .entity();
    announced.reset();
  }

  /** A person's bearer named Ada Lovelace, principal {@code ada} (its preferred_username). */
  private static RequestSpecification ada() {
    return given()
        .header(
            "Authorization",
            "Bearer "
                + MockIdp.attach()
                    .token()
                    .subject("user-ada")
                    .audience("qits-platform")
                    .groups("qits:admin")
                    .claim("credential_type", "cli")
                    .claim("name", "Ada Lovelace")
                    .claim("preferred_username", "ada")
                    .mint());
  }

  private static void patch(Map<String, Object> body, String id) {
    ada().contentType(ContentType.JSON).body(body).patch(WORK + id).then().statusCode(200);
  }

  private static Map<String, Object> assignee(String value) {
    Map<String, Object> body = new HashMap<>();
    body.put("assignee", value);
    return body;
  }

  private List<EntityAssigned> published() {
    return announced.of(projectId);
  }

  @Test
  void aPatchAnnouncesTheChangeOnceWithTheCallersName() {
    patch(assignee("alice"), ticket.id);

    List<EntityAssigned> events = published();
    assertEquals(1, events.size());
    EntityAssigned event = events.get(0);
    assertEquals(ticket.id, event.entityId());
    assertEquals("TICKET", event.archetype());
    assertEquals(ticket.number, event.number());
    assertEquals(QualifiedEntityIds.render(projectId, ticket.number), event.qualifiedId());
    assertEquals("The totals", event.title());
    assertEquals("REPORTED", event.status());
    assertEquals("alice", event.assignee());
    assertNull(event.previousAssignee());
    assertTrue(event.claimed(), "a person's different assignee is a claim");
    assertEquals("ada", event.changedBy(), "the principal is the username");
    assertEquals("Ada Lovelace", event.changedByName());
    assertEquals(event.assignedAt(), event.occurredAt());
  }

  @Test
  void restatingTheSameAssigneePublishesNothing() {
    patch(assignee("alice"), ticket.id);
    patch(assignee("alice"), ticket.id);
    patch(Map.of("title", "The totals, again"), ticket.id);

    assertEquals(1, published().size(), "only the first write changed the assignee");
  }

  @Test
  void clearingPublishesANullAssigneeNamingThePreviousOne() {
    patch(assignee("alice"), ticket.id);
    patch(assignee(null), ticket.id);

    List<EntityAssigned> events = published();
    assertEquals(2, events.size());
    assertNull(events.get(1).assignee());
    assertEquals("alice", events.get(1).previousAssignee());
    assertFalse(events.get(1).claimed());
  }

  @Test
  void aCreateNamingAnAssigneeAnnouncesIt() {
    String id =
        ada()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "archetype", "TICKET",
                    "project", projectId,
                    "title", "Filed assigned",
                    "impetus", "it occurs",
                    "ticketType", "BUG",
                    "assignee", "bob"))
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    List<EntityAssigned> events = published();
    assertEquals(1, events.size());
    assertEquals(id, events.get(0).entityId());
    assertEquals("bob", events.get(0).assignee());
    assertNull(events.get(0).previousAssignee());
    assertEquals("Ada Lovelace", events.get(0).changedByName());
  }

  @Test
  void aCreateWithoutAnAssigneeAnnouncesNothing() {
    entities.create(
        Archetype.TICKET,
        projectId,
        EntityWrite.ticket("Unassigned", "it occurs", null, "BUG", null),
        "seed");

    assertEquals(List.of(), published());
  }

  @Test
  void theWholeRowEditAnnouncesAChangeAndNotARestatement() {
    Map<String, Object> row = new HashMap<>();
    row.put("archetype", "TICKET");
    row.put("title", "The totals");
    row.put("impetus", "they are wrong");
    row.put("ticketType", "BUG");
    row.put("status", "REPORTED");
    row.put("assignee", "carol");
    row.put("membership", Collections.singletonMap("parent", null));

    ada().contentType(ContentType.JSON).body(row).put(WORK + ticket.id).then().statusCode(200);
    ada().contentType(ContentType.JSON).body(row).put(WORK + ticket.id).then().statusCode(200);

    List<EntityAssigned> events = published();
    assertEquals(1, events.size(), "the second PUT restates carol");
    assertEquals("carol", events.get(0).assignee());
    assertEquals("REPORTED", events.get(0).status());
    assertEquals("Ada Lovelace", events.get(0).changedByName());
  }

  @Test
  void theBulkTransitionAnnouncesTheChange() {
    Map<String, Object> row = new HashMap<>();
    row.put("archetype", "TICKET");
    row.put("title", "The totals");
    row.put("impetus", "they are wrong");
    row.put("ticketType", "BUG");
    row.put("status", "REPORTED");
    row.put("assignee", "dave");
    row.put("membership", Collections.singletonMap("parent", null));

    ada()
        .contentType(ContentType.JSON)
        .body(Map.of(ticket.id, row))
        .post(WORK + "transition")
        .then()
        .statusCode(200);

    List<EntityAssigned> events = published();
    assertEquals(1, events.size());
    assertEquals("dave", events.get(0).assignee());
    assertEquals("ada", events.get(0).changedBy());
  }

  @Test
  void aDispatchAnnouncesItsAgentUnclaimedNamedByItsPrincipal() {
    patch(assignee("alice"), ticket.id);
    announced.reset();

    dispatches.setAssignee(ticket.id, "agent-7", "desk");
    dispatches.setAssignee(ticket.id, "agent-7", "desk");

    List<EntityAssigned> events = published();
    assertEquals(1, events.size(), "the second press restates the agent");
    EntityAssigned event = events.get(0);
    assertEquals("agent-7", event.assignee());
    assertEquals("alice", event.previousAssignee());
    assertFalse(event.claimed(), "the dispatch's write is no claim");
    assertEquals("desk", event.changedBy());
    assertEquals("desk", event.changedByName(), "no caller of that name: the principal");
  }
}
