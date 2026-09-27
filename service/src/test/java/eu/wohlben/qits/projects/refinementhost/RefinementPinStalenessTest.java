package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.lang.reflect.Proxy;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The refinement projection answers the <b>pin</b> question as well as the peer one.
 *
 * <p>{@code daemonOutdated} compares a daemon against the newest one connected to this host, so it
 * answers {@code null} whenever a host has one refinement daemon — which is the common case, and
 * is why a container running a months-old image showed no warning anywhere. {@code
 * daemonVersionStale} compares it against {@code RefinementContainerFactory.imageVersion()}, the
 * image this service deploys, and {@code pinnedDaemonVersion} says what that is. The two are
 * different questions and both are asserted here, side by side, because the ticket this came from
 * was filed by somebody reading the first and expecting the second.
 *
 * <p>The registry is the real bean and the daemon is a reflective {@code WebSocketConnection} stub
 * answering the three methods a {@code Hello} reaches — the fixture {@link
 * RefinementDaemonRegistryTest} uses, for its reason: what is under test is the comparison the
 * projection makes over genuinely registered state, so a stand-in registry would prove only that
 * the stand-in agreed with itself.
 */
@QuarkusTest
public class RefinementPinStalenessTest {

  @Inject RefinementService service;
  @Inject RefinementDaemonRegistry registry;
  @Inject RefinementContainerFactory factory;

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(java.util.Map.of("title", title, "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private long refinementFor(String projectName, String epicTitle) {
    String epicId = createEpic(createProject(projectName), epicTitle);
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(java.util.Map.of())
            .when()
            .post("/projects/api/entities/" + epicId + "/refinement")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  /** A {@code WebSocketConnection} answering only what a {@code Hello} reaches on one. */
  private WebSocketConnection connection(String id) {
    return (WebSocketConnection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {WebSocketConnection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> id;
                  case "isOpen" -> Boolean.TRUE;
                  case "sendTextAndAwait" -> null;
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "connection " + id;
                  default -> null;
                });
  }

  /** Connect a daemon to {@code refinementId} announcing {@code version}. */
  private WebSocketConnection connect(long refinementId, String version) {
    return connect(refinementId, version, null);
  }

  /**
   * The same, naming a build time — which is what the <em>peer</em> comparison orders on, and the
   * only reason a fixture here ever needs one.
   */
  private WebSocketConnection connect(long refinementId, String version, String buildTime) {
    WebSocketConnection connection = connection("c-" + refinementId);
    registry.register(refinementId, connection);
    registry.onMessage(
        refinementId,
        connection,
        new Hello("refinement-" + refinementId, "repo", "main", null, 1, version, buildTime));
    return connection;
  }

  /**
   * Drop the daemon <b>and</b> its connection.
   *
   * <p>{@code forget} alone does not: it clears the rollups and leaves the entry in {@code
   * clients}, so a daemon left behind by one test is still a live peer for the next one's
   * {@code daemonOutdated} — which is the registry behaving correctly and a fixture that has to
   * clean up after itself.
   */
  private void disconnect(long refinementId, WebSocketConnection connection) {
    registry.unregister(refinementId, connection);
    registry.forget(refinementId);
  }

  private RefinementService.RefinementView view(long id) {
    return service.view(service.get(id));
  }

  @Test
  public void aDaemonOnThePinnedVersionIsNotStale() {
    long id = refinementFor("Pin Equal", "Pinned Epic");
    WebSocketConnection daemon = connect(id, factory.imageVersion());
    try {
      RefinementService.RefinementView view = view(id);

      assertEquals(factory.imageVersion(), view.pinnedDaemonVersion());
      assertEquals(factory.imageVersion(), view.daemonVersion());
      assertFalse(view.daemonVersionStale(), "the daemon is running exactly what this service pins");
    } finally {
      disconnect(id, daemon);
    }
  }

  @Test
  public void aDaemonOnAnotherVersionIsStaleEvenThoughNoPeerSaysSo() {
    long id = refinementFor("Pin Differs", "Drifted Epic");
    WebSocketConnection daemon = connect(id, "2020.101.000001");
    try {
      RefinementService.RefinementView view = view(id);

      assertTrue(view.daemonVersionStale(), "a version that is not the pin is not the image we ship");
      assertEquals(factory.imageVersion(), view.pinnedDaemonVersion());

      // The peer comparison is untouched and is exactly why this field had to exist: this is the
      // only refinement daemon connected, so there is no newer peer and daemonOutdated says
      // nothing at all about a container six years behind the pin.
      assertNull(view.daemonOutdated(), "one connected daemon is never outdated against its peers");
    } finally {
      disconnect(id, daemon);
    }
  }

  @Test
  public void aDaemonThatNamesNoVersionCountsAsBehind() {
    long nullVersion = refinementFor("Pin Null", "Unvouched Epic");
    long blankVersion = refinementFor("Pin Blank", "Blank Epic");
    WebSocketConnection unversioned = connect(nullVersion, null);
    WebSocketConnection blank = connect(blankVersion, "   ");
    try {
      // An image built without build-time filtering cannot vouch that it is the pinned one, and
      // the pin is the thing being asserted — so "unknown build" is behind, never a pass.
      assertTrue(view(nullVersion).daemonVersionStale(), "a null version cannot vouch for the pin");
      assertTrue(view(blankVersion).daemonVersionStale(), "a blank version cannot vouch either");
    } finally {
      disconnect(nullVersion, unversioned);
      disconnect(blankVersion, blank);
    }
  }

  @Test
  public void noConnectedDaemonMakesNoClaimAndStillAnswersThePin() {
    long id = refinementFor("Pin Absent", "Quiet Epic");

    RefinementService.RefinementView view = view(id);

    assertNull(view.daemonVersion(), "nothing is connected");
    assertFalse(
        view.daemonVersionStale(),
        "not stale is the absence of a claim — an unconnected daemon is never reported behind");
    assertEquals(
        factory.imageVersion(),
        view.pinnedDaemonVersion(),
        "the pin is a property of this service, answerable with no container at all");
  }

  @Test
  public void theListingAndTheDtoCarryBothFields() {
    long id = refinementFor("Pin Wire", "Wire Epic");
    WebSocketConnection daemon = connect(id, "2020.101.000001");
    try {
      given()
          .when()
          .get("/projects/api/refinements/" + id)
          .then()
          .statusCode(200)
          .body("refinement.pinnedDaemonVersion", Matchers.is(factory.imageVersion()))
          .body("refinement.daemonVersionStale", Matchers.is(true))
          // The peer field is on the wire exactly as it was: TRUE or absent, never false.
          .body("refinement.daemonOutdated", Matchers.nullValue());

      RefinementService.RefinementView listed =
          service.listByProject(service.get(id).projectId).stream()
              .filter(each -> each.refinement().id == id)
              .findFirst()
              .orElseThrow();
      assertTrue(listed.daemonVersionStale(), "the light projection answers the pin question too");
      assertEquals(factory.imageVersion(), listed.pinnedDaemonVersion());
    } finally {
      disconnect(id, daemon);
    }
  }

  /**
   * The peer comparison is exactly what it was, and this is the case it is right for: two daemons
   * connected to this host, both running the pinned image, one built before the other. The older
   * one is {@code daemonOutdated} and neither is {@code daemonVersionStale} — somebody else has a
   * newer build, and nobody is running something other than what this service deploys.
   */
  @Test
  public void thePeerComparisonStillAnswersAndIsNotTheSameQuestion() {
    long older = refinementFor("Peer Older", "Older Peer Epic");
    long newer = refinementFor("Peer Newer", "Newer Peer Epic");
    WebSocketConnection behind = connect(older, factory.imageVersion(), "2026-01-01T00:00:00Z");
    WebSocketConnection ahead = connect(newer, factory.imageVersion(), "2026-06-01T00:00:00Z");
    try {
      assertEquals(Boolean.TRUE, view(older).daemonOutdated(), "an older build has a newer peer");
      assertNull(view(newer).daemonOutdated(), "the newest peer is never outdated");

      assertFalse(view(older).daemonVersionStale(), "both are running the image this service pins");
      assertFalse(view(newer).daemonVersionStale(), "both are running the image this service pins");
    } finally {
      disconnect(older, behind);
      disconnect(newer, ahead);
    }
  }
}
