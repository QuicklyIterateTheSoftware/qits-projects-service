package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.MultiMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The ticket MCP surface: its per-connection project scoping, the thread an agent reads back, and
 * the refusals arriving as readable tool errors rather than protocol ones. The lifecycle rules
 * themselves are pinned in the epics module; what is tested here is what the agent on the other end
 * of the socket actually experiences.
 *
 * <p>The twin of {@link EpicMcpToolsTest}, with one deliberate difference asserted outright:
 * transitioning IS on this server, where the epic surface exposes no lifecycle move at all.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class TicketMcpToolsTest {

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits:system,qits-platform:system");
  }

  // --- Fixtures over REST ---------------------------------------------------

  private String createProject(String name) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  // --- MCP plumbing ---------------------------------------------------------

  /** All text content of a tool response joined — list tools emit one content item per element. */
  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  /** A streamable client on the repository server, scoped to {@code projectId} (or none). */
  private McpStreamableTestClient client(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  /**
   * A client scoped to {@code projectId} and <em>named</em>: it sends the {@code X-Qits-User} the
   * edge forwards, which is the only thing that ever produces a principal in a deployed service and
   * therefore the only thing a stamped attribution can be tested through.
   */
  private McpStreamableTestClient clientAs(String projectId, String user) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              headers.add(ProjectScope.PROJECT_HEADER, projectId);
              headers.add("X-Qits-User", user);
              headers.add("X-Qits-Roles", "qits:admin");
              return headers;
            })
        .build()
        .connect();
  }

  /** A client carrying the read-only marker an autonomous launch stamps into its MCP URL. */
  private McpStreamableTestClient readOnlyClient(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp?" + ReadOnlyRepositoryToolFilter.READ_ONLY_PARAM + "=true")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              headers.add(ProjectScope.PROJECT_HEADER, projectId);
              return headers;
            })
        .build()
        .connect();
  }

  /** Call one tool and hand its response to {@code check}. */
  private void call(String projectId, String tool, Map<String, Object> args, Check check) {
    client(projectId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  /** The same, from a session the edge has named. */
  private void callAs(
      String projectId, String user, String tool, Map<String, Object> args, Check check) {
    clientAs(projectId, user).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  /** The single-tool assertion shape, so a call site reads as one statement. */
  private interface Check {
    void accept(ToolResponse response);
  }

  /** File a ticket through the tools and return its id. */
  private String createTicket(String projectId, String title, String type) {
    String[] id = new String[1];
    call(
        projectId,
        "create_ticket",
        Map.of("title", title, "type", type, "description", "found by the agent"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("\"OPEN\""), "a new ticket must be open: " + body);
          id[0] = idIn(body);
        });
    return id[0];
  }

  /** The {@code "id"} field of a tool's JSON result — the first one, which is the row's own. */
  private static String idIn(String json) {
    int at = json.indexOf("\"id\"");
    int open = json.indexOf('"', json.indexOf(':', at) + 1);
    return json.substring(open + 1, json.indexOf('"', open + 1));
  }

  // --- Scoping --------------------------------------------------------------

  @Test
  public void rejectsTicketToolCallsWithoutAProjectHeader() {
    call(
        null,
        "list_tickets",
        Map.of(),
        response -> {
          assertTrue(response.isError(), "an unscoped session must not resolve a project");
          assertTrue(text(response).contains("not scoped to a project"));
        });
  }

  @Test
  public void listsOnlyTheScopedProjectsTickets() {
    String projectA = createProject("Tickets A");
    String projectB = createProject("Tickets B");
    String inA = createTicket(projectA, "Only in A", "BUG");
    String inB = createTicket(projectB, "Only in B", "BUG");

    call(
        projectA,
        "list_tickets",
        Map.of(),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(inA), "should list its own ticket: " + body);
          assertFalse(body.contains(inB), "must not leak the other project's ticket: " + body);
        });
  }

  @Test
  public void refusesATicketOutsideTheScopedProject() {
    String projectA = createProject("Ticket Owner");
    String ticketInA = createTicket(projectA, "Owned", "BUG");
    String projectB = createProject("Ticket Stranger");

    // Every write is checked back to the scope too, not just the read.
    for (String tool : List.of("get_ticket", "update_ticket", "add_ticket_comment")) {
      Map<String, Object> args =
          switch (tool) {
            case "add_ticket_comment" -> Map.of("ticketId", ticketInA, "body", "hello");
            case "update_ticket" -> Map.of("id", ticketInA, "title", "Stolen");
            default -> Map.of("id", ticketInA);
          };
      call(
          projectB,
          tool,
          args,
          response -> {
            assertTrue(response.isError(), "cross-project access must be refused by " + tool);
            assertTrue(text(response).contains("not found in this project"), text(response));
          });
    }
  }

  // --- Filing and working ---------------------------------------------------

  @Test
  public void filesATicketAndReadsItsThreadBack() {
    String projectId = createProject("Ticket Refinery");
    String ticketId = createTicket(projectId, "Login button does nothing", "BUG");

    call(
        projectId,
        "add_ticket_comment",
        Map.of("ticketId", ticketId, "body", "I can reproduce it"),
        response -> assertFalse(response.isError(), text(response)));
    call(
        projectId,
        "add_ticket_comment",
        Map.of("ticketId", ticketId, "body", "It is the cache"),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "get_ticket",
        Map.of("id", ticketId),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("Login button does nothing"), body);
          // A thread is a sequence, so the detail carries it in the order it was written.
          assertTrue(
              body.indexOf("I can reproduce it") < body.indexOf("It is the cache"),
              "comments must read oldest first: " + body);
        });
  }

  @Test
  public void filtersTheListByStatus() {
    String projectId = createProject("Ticket Filtered");
    String open = createTicket(projectId, "Still broken", "BUG");
    String resolved = createTicket(projectId, "Fixed", "IMPROVEMENT");
    call(
        projectId,
        "transition_ticket",
        Map.of("id", resolved, "target", "RESOLVED"),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "list_tickets",
        Map.of("status", "OPEN"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(open), "the open ticket is what the filter is for: " + body);
          assertFalse(body.contains(resolved), "a resolved ticket is not open: " + body);
        });
  }

  @Test
  public void reportsAnUnknownStatusFilterAsAToolError() {
    String projectId = createProject("Ticket Typo");
    call(
        projectId,
        "list_tickets",
        Map.of("status", "RESOLVD"),
        response -> {
          assertTrue(response.isError(), "a typo must not read as 'no tickets'");
          assertTrue(text(response).contains("Unknown ticket status"), text(response));
        });
  }

  @Test
  public void reportsAnUnknownTypeAsAToolError() {
    String projectId = createProject("Ticket Kind");
    call(
        projectId,
        "create_ticket",
        Map.of("title", "Mistyped", "type", "DEFECT"),
        response -> {
          assertTrue(response.isError(), "a type naming nothing must be refused");
          assertTrue(text(response).contains("Unknown ticket type"), text(response));
        });
  }

  // --- Editing a remark -----------------------------------------------------

  /**
   * One field of the single comment on {@code ticketId}, read back over REST: the tool result
   * carries the author but not the timestamps, and {@code updatedAt} is half of what an edit is.
   */
  private String soleComment(String ticketId, String field) {
    return authenticated()
        .when()
        .get("/projects/api/tickets/{id}/comments", ticketId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("entries[0].comment." + field);
  }

  @Test
  public void editsARemarkAndLeavesItsAuthorAlone() {
    // The front desk's own use case: an agent that came back knowing more corrects the note it
    // left, instead of stacking a contradiction under it for the next reader to arbitrate.
    String projectId = createProject("Ticket Front Desk");
    String ticketId = createTicket(projectId, "Reported by somebody", "BUG");

    String[] commentId = new String[1];
    callAs(
        projectId,
        "reporter",
        "add_ticket_comment",
        Map.of("ticketId", ticketId, "body", "It fails on Tuesdays"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          // The author is the session's principal, never a tool argument.
          assertTrue(body.contains("\"reporter\""), "the author is stamped from the header: " + body);
          commentId[0] = idIn(body);
        });
    String writtenAt = soleComment(ticketId, "updatedAt");

    callAs(
        projectId,
        "front-desk",
        "update_ticket_comment",
        Map.of("id", commentId[0], "body", "It fails on Tuesdays and on Fridays"),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("and on Fridays"), "the body must be replaced: " + body);
          assertTrue(
              body.contains("\"reporter\""),
              "an edit records who changed it in the log, never by rewriting the attribution: "
                  + body);
          assertFalse(body.contains("front-desk"), "the editor is not the author: " + body);
        });

    assertEquals("reporter", soleComment(ticketId, "author"), "the author must survive an edit");
    assertNotEquals(writtenAt, soleComment(ticketId, "updatedAt"), "an edit must move updatedAt");
  }

  @Test
  public void reportsAnUnknownCommentIdAsAToolError() {
    String projectId = createProject("Ticket Ghost Remark");
    call(
        projectId,
        "update_ticket_comment",
        Map.of("id", "no-such-comment", "body", "correcting nothing"),
        response -> {
          assertTrue(response.isError(), "an id naming no comment must not read as an edit");
          assertTrue(text(response).contains("Ticket comment not found"), text(response));
        });
  }

  @Test
  public void refusesACommentOutsideTheScopedProject() {
    String projectA = createProject("Remark Owner");
    String ticketInA = createTicket(projectA, "Owned", "BUG");
    String[] commentId = new String[1];
    call(
        projectA,
        "add_ticket_comment",
        Map.of("ticketId", ticketInA, "body", "found it here"),
        response -> {
          assertFalse(response.isError(), text(response));
          commentId[0] = idIn(text(response));
        });
    String projectB = createProject("Remark Stranger");

    // A comment is checked back to the scope through the ticket it hangs under, exactly as a ticket
    // is checked back through its project — and the refusal names the comment, which is the id the
    // caller supplied and the only one it should learn anything about.
    call(
        projectB,
        "update_ticket_comment",
        Map.of("id", commentId[0], "body", "not yours to edit"),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          assertTrue(text(response).contains("not found in this project"), text(response));
        });
  }

  // --- The lifecycle --------------------------------------------------------

  @Test
  public void resolvesAndReopensATicket() {
    String projectId = createProject("Ticket Cycle");
    String ticketId = createTicket(projectId, "Round trip", "BUG");

    call(
        projectId,
        "transition_ticket",
        Map.of("id", ticketId, "target", "RESOLVED"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("\"RESOLVED\""), text(response));
        });
    call(
        projectId,
        "transition_ticket",
        Map.of("id", ticketId, "target", "OPEN"),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("\"OPEN\""), text(response));
        });
  }

  @Test
  public void refusesAnIllegalTransitionWithAReadableToolError() {
    String projectId = createProject("Ticket Refusal");
    String ticketId = createTicket(projectId, "Already open", "BUG");

    // isError, NOT a JSON-RPC protocol error: the model has to be able to read the refusal and move
    // on inside the same turn.
    call(
        projectId,
        "transition_ticket",
        Map.of("id", ticketId, "target", "OPEN"),
        response -> {
          assertTrue(response.isError(), "a move to the status it already has must be refused");
          assertTrue(text(response).contains("cannot move from"), text(response));
        });
    call(
        projectId,
        "transition_ticket",
        Map.of("id", ticketId, "target", "CLOSED"),
        response -> {
          assertTrue(response.isError(), "a target naming no status must be refused");
          assertTrue(text(response).contains("Unknown ticket status"), text(response));
        });
  }

  @Test
  public void aResolvedTicketIsStillWritable() {
    // The whole difference from the epic surface, where a frozen epic refuses every structural
    // edit. Resolving a ticket commits to nothing, so nothing about it is frozen.
    String projectId = createProject("Ticket Thawed");
    String ticketId = createTicket(projectId, "Done for now", "BUG");
    call(
        projectId,
        "transition_ticket",
        Map.of("id", ticketId, "target", "RESOLVED"),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "update_ticket",
        Map.of("id", ticketId, "title", "Done, with a better title"),
        response -> assertFalse(response.isError(), text(response)));
    call(
        projectId,
        "add_ticket_comment",
        Map.of("ticketId", ticketId, "body", "it came back"),
        response -> assertFalse(response.isError(), text(response)));
  }

  // --- The surface ----------------------------------------------------------

  @Test
  public void exposesNoDeleteTool() {
    // Removing a ticket, or a remark somebody made, is a human act in the UI: an agent that could
    // delete what it disagrees with could erase the record of its own mistake.
    String projectId = createProject("Ticket Surface");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertFalse(names.contains("delete_ticket"), names.toString());
              assertFalse(names.contains("remove_ticket"), names.toString());
              assertFalse(names.contains("delete_ticket_comment"), names.toString());
            })
        .thenAssertResults();
  }

  @Test
  public void aReadOnlySessionSeesTheReadsAndNoneOfTheWrites() {
    // An unattended read-only run may look at the tickets — that is often what it was launched to
    // work from — and may not file, edit, comment on or resolve one, nor rewrite a remark somebody
    // else put on the thread.
    String projectId = createProject("Ticket ReadOnly");
    readOnlyClient(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              for (String mutating :
                  List.of(
                      "create_ticket",
                      "update_ticket",
                      "transition_ticket",
                      "add_ticket_comment",
                      "update_ticket_comment")) {
                assertFalse(
                    names.contains(mutating),
                    "read-only run still exposes mutating tool " + mutating + ": " + names);
              }
              assertTrue(names.contains("list_tickets"), "read tool wrongly hidden: " + names);
              assertTrue(names.contains("get_ticket"), "read tool wrongly hidden: " + names);
            })
        .thenAssertResults();
  }
}
