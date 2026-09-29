package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.entity.Archetype;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The published payload schemas, and the pin that keeps them honest</b> (qits-548): {@code GET
 * /projects/api/entities/archetypes/{archetype}/schemas/{door}}.
 *
 * <p>A schema is only worth serving if the door it describes accepts what it says. So besides
 * pinning TICKET's and TASK's three schemas byte for byte, this class judges every archetype's
 * schema <em>by its door</em>, with bodies built from nothing but the schema itself:
 *
 * <ul>
 *   <li><b>create and transition — the drift pin.</b> A body of exactly the schema's {@code required}
 *       properties is accepted by the matching door; the same body missing any one of them is
 *       refused. A registry change that the schema builder does not follow, or a door that grows a
 *       requirement the schema does not state, fails here.
 *   <li><b>update.</b> Every property the schema lists, sent through {@code PATCH}, is accepted.
 * </ul>
 *
 * <p>A value is made from the property's fragment alone — the first word of an enum, an instant for
 * a {@code date-time}, {@code "x"} for a string — except the handful that must name a real row (a
 * project, a parent, a repository, a sibling), which come from fixtures.
 */
@QuarkusTest
class EntitySchemaApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SCHEMAS = "/projects/api/entities/archetypes/";

  private static JsonNode schema(String archetype, String door) {
    try {
      return JSON.readTree(
          given()
              .when()
              .get(SCHEMAS + archetype + "/schemas/" + door)
              .then()
              .statusCode(200)
              .extract()
              .asString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode json(String text) {
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- the pins -----------------------------------------------------------------------------------

  @Test
  void theTicketSchemasArePinned() {
    assertEquals(json(TICKET_CREATE), schema("TICKET", "create"));
    assertEquals(json(TICKET_UPDATE), schema("ticket", "update"));
    assertEquals(json(TICKET_TRANSITION), schema("Ticket", "transition"));
  }

  @Test
  void theTaskSchemasArePinned() {
    assertEquals(json(TASK_CREATE), schema("TASK", "create"));
    assertEquals(json(TASK_UPDATE), schema("TASK", "update"));
    assertEquals(json(TASK_TRANSITION), schema("TASK", "transition"));
  }

  /** The requirement sets the CLI prints, stated plainly beside the pins. */
  @Test
  void theRequiredSetsAreTheRegistrys() {
    assertEquals(
        json("[\"title\",\"ticketType\",\"impetus\",\"project\"]"),
        schema("TICKET", "create").get("required"));
    assertEquals(
        json("[\"title\",\"repositoryId\",\"parent\"]"), schema("TASK", "create").get("required"));
    assertEquals(json("[\"title\",\"project\"]"), schema("CAMPAIGN", "create").get("required"));
    assertEquals(json("[\"title\",\"status\"]"), schema("EPIC", "transition").get("required"));
    assertEquals(json("[]"), schema("EPIC", "update").get("required"));
  }

  @Test
  void anUnknownArchetypeOrDoorIsA404() {
    given().when().get(SCHEMAS + "STORY/schemas/create").then().statusCode(404);
    given().when().get(SCHEMAS + "TICKET/schemas/delete").then().statusCode(404);
  }

  // --- the drift pin: create --------------------------------------------------------------------

  @TestFactory
  Stream<DynamicTest> theCreateDoorAcceptsExactlyTheRequiredAndRefusesAnyOneLess() {
    return Stream.of(Archetype.values())
        .map(
            archetype ->
                DynamicTest.dynamicTest(
                    archetype.name(),
                    () -> {
                      Rows rows = Rows.seed("Schema create " + archetype);
                      JsonNode schema = schema(archetype.name(), "create");
                      List<String> required = strings(schema.get("required"));
                      Map<String, Object> body = new LinkedHashMap<>();
                      body.put("archetype", archetype.name());
                      for (String name : required) {
                        body.put(name, rows.value(archetype, name, schema.at("/properties/" + name)));
                      }

                      create(body).then().statusCode(201);

                      for (String name : required) {
                        Map<String, Object> less = new LinkedHashMap<>(body);
                        less.remove(name);
                        Response refused = create(less);
                        assertEquals(
                            400,
                            refused.statusCode(),
                            archetype + " create without " + name + ": " + refused.asString());
                      }
                    }));
  }

  // --- the drift pin: transition ----------------------------------------------------------------

  @TestFactory
  Stream<DynamicTest> theTransitionDoorAcceptsExactlyTheRequiredAndRefusesAnyOneLess() {
    return Stream.of(Archetype.values())
        .map(
            archetype ->
                DynamicTest.dynamicTest(
                    archetype.name(),
                    () -> {
                      Rows rows = Rows.seed("Schema transition " + archetype);
                      String id = rows.rowOf(archetype);
                      JsonNode schema = schema(archetype.name(), "transition");
                      List<String> required = strings(schema.get("required"));
                      Map<String, Object> entry = new LinkedHashMap<>();
                      entry.put("archetype", archetype.name());
                      for (String name : required) {
                        entry.put(name, rows.value(archetype, name, schema.at("/properties/" + name)));
                      }

                      transition(id, entry).then().statusCode(200);

                      for (String name : required) {
                        Map<String, Object> less = new LinkedHashMap<>(entry);
                        less.remove(name);
                        Response refused = transition(id, less);
                        // A campaign's status is guarded before the registry is asked: the door
                        // refuses to move one at all, and an absent status reads as a move to none.
                        int expected =
                            archetype == Archetype.CAMPAIGN && name.equals("status") ? 409 : 400;
                        assertEquals(
                            expected,
                            refused.statusCode(),
                            archetype + " transition without " + name + ": " + refused.asString());
                      }
                    }));
  }

  // --- update -----------------------------------------------------------------------------------

  /**
   * Every property the update schema lists is one {@code PATCH} accepts. The implemented marker goes
   * alone and after the epic is REFINED, because the freeze refuses it beside any scope edit — a
   * rule about the moment, not about the property.
   */
  @TestFactory
  Stream<DynamicTest> thePatchDoorAcceptsEveryPropertyTheUpdateSchemaLists() {
    return Stream.of(Archetype.values())
        .map(
            archetype ->
                DynamicTest.dynamicTest(
                    archetype.name(),
                    () -> {
                      Rows rows = Rows.seed("Schema update " + archetype);
                      String id = rows.rowOf(archetype);
                      JsonNode schema = schema(archetype.name(), "update");
                      Map<String, Object> body = new LinkedHashMap<>();
                      Object marker = null;
                      for (Iterator<String> names = schema.get("properties").fieldNames();
                          names.hasNext(); ) {
                        String name = names.next();
                        Object value = rows.value(archetype, name, schema.at("/properties/" + name));
                        if (name.equals("implementedAt")) {
                          marker = value;
                        } else {
                          body.put(name, value);
                        }
                      }

                      Response patched = patch(id, body);
                      assertEquals(200, patched.statusCode(), archetype + ": " + patched.asString());

                      if (marker != null) {
                        given()
                            .contentType(ContentType.JSON)
                            .body(map("target", "REFINED"))
                            .post("/projects/api/epics/" + rows.epic + "/transition")
                            .then()
                            .statusCode(200);
                        Response marked = patch(id, map("implementedAt", marker));
                        assertEquals(200, marked.statusCode(), archetype + ": " + marked.asString());
                      }
                    }));
  }

  // --- the doors ----------------------------------------------------------------------------------

  private static Response create(Map<String, Object> body) {
    return given().contentType(ContentType.JSON).body(body).when().post("/projects/api/entities");
  }

  private static Response transition(String id, Map<String, Object> entry) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of(id, entry))
        .when()
        .post("/projects/api/entities/transition");
  }

  private static Response patch(String id, Map<String, Object> body) {
    return given()
        .contentType(EntityPatchController.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch("/projects/api/entities/" + id);
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }

  /**
   * One project's worth of rows, one of every archetype, plus a sibling for each node so a {@code
   * dependsOn} has something to name.
   */
  private record Rows(
      EntityFixtures.Project project,
      String repository,
      String epic,
      String feature,
      String siblingFeature,
      String task,
      String siblingTask,
      String ticket,
      String campaign) {

    static Rows seed(String name) {
      EntityFixtures.Project project = EntityFixtures.project(name);
      String repository = EntityFixtures.repository(project.id());
      String epic = EntityFixtures.epic(project.id());
      String feature = EntityFixtures.feature(epic);
      String siblingFeature = EntityFixtures.feature(epic);
      String task = EntityFixtures.task(feature, repository);
      String siblingTask = EntityFixtures.task(feature, repository);
      return new Rows(
          project,
          repository,
          epic,
          feature,
          siblingFeature,
          task,
          siblingTask,
          EntityFixtures.ticket(project.id()),
          EntityFixtures.campaign(project.id()));
    }

    String rowOf(Archetype archetype) {
      return switch (archetype) {
        case EPIC -> epic;
        case TICKET -> ticket;
        case CAMPAIGN -> campaign;
        case FEATURE -> feature;
        case TASK -> task;
      };
    }

    /** The parent a node of {@code archetype} hangs under here. */
    String parentOf(Archetype archetype) {
      return archetype == Archetype.TASK ? feature : epic;
    }

    /** A legal value of {@code name} for {@code archetype}, from its fragment or a fixture. */
    Object value(Archetype archetype, String name, JsonNode fragment) {
      switch (name) {
        case "project":
          return project.slug();
        case "parent":
          return parentOf(archetype);
        case "repositoryId":
          return repository;
        case "dependsOn":
          return archetype == Archetype.TASK ? siblingTask : siblingFeature;
        case "membership":
          return map("parent", parentOf(archetype));
        default:
          break;
      }
      JsonNode words = fragment.get("enum");
      if (words != null) {
        return words.get(0).asText();
      }
      if ("date-time".equals(fragment.path("format").asText())) {
        return "2026-01-01T00:00:00Z";
      }
      return "x";
    }
  }

  // --- the pinned schemas -------------------------------------------------------------------------

  private static final String TICKET_CREATE =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "title" : "TICKET create",
        "description" : "The body of POST /projects/api/entities, less its archetype (\\"archetype\\": \\"TICKET\\" is added beside these). The status is minted REPORTED by the writer.",
        "type" : "object",
        "properties" : {
          "title" : {
            "type" : "string",
            "description" : "The label.",
            "pattern" : "\\\\S"
          },
          "description" : {
            "type" : "string",
            "description" : "The long-form Markdown body."
          },
          "ticketType" : {
            "type" : "string",
            "description" : "A ticket's kind.",
            "enum" : [ "BUG", "IMPROVEMENT" ]
          },
          "impetus" : {
            "type" : "string",
            "description" : "Why a ticket came about, in the reporter's words; usually one sentence.",
            "pattern" : "\\\\S"
          },
          "assignee" : {
            "type" : "string",
            "description" : "Who is looking at it; free text."
          },
          "project" : {
            "type" : "string",
            "pattern" : "\\\\S",
            "description" : "The project the new TICKET goes in: its id or its slug."
          }
        },
        "required" : [ "title", "ticketType", "impetus", "project" ],
        "additionalProperties" : false
      }
      """;

  private static final String TICKET_UPDATE =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "title" : "TICKET update",
        "description" : "A JSON merge patch (RFC 7396) of one TICKET, sent to PATCH /projects/api/entities/{id}: an absent property is left unchanged, null clears it. The status is moved through POST /projects/api/entities/{id}/status.",
        "type" : "object",
        "properties" : {
          "title" : {
            "type" : "string",
            "description" : "The label.",
            "pattern" : "\\\\S"
          },
          "description" : {
            "type" : [ "string", "null" ],
            "description" : "The long-form Markdown body."
          },
          "ticketType" : {
            "type" : "string",
            "description" : "A ticket's kind.",
            "enum" : [ "BUG", "IMPROVEMENT" ]
          },
          "impetus" : {
            "type" : [ "string", "null" ],
            "description" : "Why a ticket came about, in the reporter's words; usually one sentence.",
            "pattern" : "\\\\S"
          },
          "assignee" : {
            "type" : [ "string", "null" ],
            "description" : "Who is looking at it; free text."
          }
        },
        "required" : [ ],
        "minProperties" : 1,
        "additionalProperties" : false
      }
      """;

  private static final String TICKET_TRANSITION =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "title" : "TICKET transition",
        "description" : "The full post-state of one row turned into a TICKET, one entry of POST /projects/api/entities/transition (keyed by the row's id, with \\"archetype\\": \\"TICKET\\" beside these). An absent property is CLEARED, and an absent membership means a root.",
        "type" : "object",
        "properties" : {
          "title" : {
            "type" : "string",
            "description" : "The label.",
            "pattern" : "\\\\S"
          },
          "description" : {
            "type" : "string",
            "description" : "The long-form Markdown body."
          },
          "status" : {
            "type" : "string",
            "description" : "The lifecycle status.",
            "enum" : [ "REPORTED", "REFINED", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED" ]
          },
          "ticketType" : {
            "type" : "string",
            "description" : "A ticket's kind.",
            "enum" : [ "BUG", "IMPROVEMENT" ]
          },
          "impetus" : {
            "type" : "string",
            "description" : "Why a ticket came about, in the reporter's words; usually one sentence.",
            "pattern" : "\\\\S"
          },
          "assignee" : {
            "type" : "string",
            "description" : "Who is looking at it; free text."
          },
          "membership" : {
            "type" : "object",
            "description" : "Where the row hangs. A TICKET is a root, so this may be left out.",
            "properties" : {
              "parent" : {
                "type" : "null",
                "description" : "A TICKET is a root: it has no parent."
              },
              "position" : {
                "type" : "integer",
                "minimum" : 0,
                "description" : "Zero-based place among the parent's children; absent appends it last."
              }
            },
            "required" : [ ],
            "additionalProperties" : false
          }
        },
        "required" : [ "title", "status", "ticketType" ],
        "additionalProperties" : false
      }
      """;

  private static final String TASK_CREATE =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "title" : "TASK create",
        "description" : "The body of POST /projects/api/entities, less its archetype (\\"archetype\\": \\"TASK\\" is added beside these). The status is minted REPORTED by the writer.",
        "type" : "object",
        "properties" : {
          "title" : {
            "type" : "string",
            "description" : "The label.",
            "pattern" : "\\\\S"
          },
          "description" : {
            "type" : "string",
            "description" : "The long-form Markdown body."
          },
          "repositoryId" : {
            "type" : "string",
            "description" : "The id of the one repository a task works in; it must be in the task's project.",
            "pattern" : "\\\\S"
          },
          "dependsOn" : {
            "type" : "string",
            "description" : "The id of the sibling this one waits for — ordering, never nesting."
          },
          "parent" : {
            "type" : "string",
            "pattern" : "\\\\S",
            "description" : "The id of a FEATURE — its UUID or its qualified id (<projectSlug>-<n>)."
          }
        },
        "required" : [ "title", "repositoryId", "parent" ],
        "additionalProperties" : false
      }
      """;

  private static final String TASK_UPDATE =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "title" : "TASK update",
        "description" : "A JSON merge patch (RFC 7396) of one TASK, sent to PATCH /projects/api/entities/{id}: an absent property is left unchanged, null clears it. The status is moved through POST /projects/api/entities/{id}/status.",
        "type" : "object",
        "properties" : {
          "title" : {
            "type" : "string",
            "description" : "The label.",
            "pattern" : "\\\\S"
          },
          "description" : {
            "type" : [ "string", "null" ],
            "description" : "The long-form Markdown body."
          },
          "repositoryId" : {
            "type" : "string",
            "description" : "The id of the one repository a task works in; it must be in the task's project.",
            "pattern" : "\\\\S"
          },
          "implementedAt" : {
            "type" : [ "string", "null" ],
            "description" : "The implemented marker, an ISO-8601 instant. Moves only while the owning epic is REFINED.",
            "format" : "date-time"
          },
          "dependsOn" : {
            "type" : [ "string", "null" ],
            "description" : "The id of the sibling this one waits for — ordering, never nesting."
          }
        },
        "required" : [ ],
        "minProperties" : 1,
        "additionalProperties" : false
      }
      """;

  private static final String TASK_TRANSITION =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "title" : "TASK transition",
        "description" : "The full post-state of one row turned into a TASK, one entry of POST /projects/api/entities/transition (keyed by the row's id, with \\"archetype\\": \\"TASK\\" beside these). An absent property is CLEARED, and an absent membership means a root.",
        "type" : "object",
        "properties" : {
          "title" : {
            "type" : "string",
            "description" : "The label.",
            "pattern" : "\\\\S"
          },
          "description" : {
            "type" : "string",
            "description" : "The long-form Markdown body."
          },
          "repositoryId" : {
            "type" : "string",
            "description" : "The id of the one repository a task works in; it must be in the task's project.",
            "pattern" : "\\\\S"
          },
          "implementedAt" : {
            "type" : "string",
            "description" : "The implemented marker, an ISO-8601 instant. Moves only while the owning epic is REFINED.",
            "format" : "date-time"
          },
          "dependsOn" : {
            "type" : "string",
            "description" : "The id of the sibling this one waits for — ordering, never nesting."
          },
          "membership" : {
            "type" : "object",
            "description" : "Where the row hangs: its parent, and its place among siblings.",
            "properties" : {
              "parent" : {
                "type" : "string",
                "pattern" : "\\\\S",
                "description" : "The id of a FEATURE (its UUID)."
              },
              "position" : {
                "type" : "integer",
                "minimum" : 0,
                "description" : "Zero-based place among the parent's children; absent appends it last."
              }
            },
            "required" : [ "parent" ],
            "additionalProperties" : false
          }
        },
        "required" : [ "title", "repositoryId", "membership" ],
        "additionalProperties" : false
      }
      """;
}
