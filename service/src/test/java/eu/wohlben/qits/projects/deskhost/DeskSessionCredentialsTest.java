package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials.Credential;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The list a desk's daemon is handed (qits-1482), off any container: which token stands under
 * {@code front-desk} with the delivery loop on and off, {@code desk-agents} always on the session
 * token, one key per refinement room holding a token, and a key with no token left out.
 */
class DeskSessionCredentialsTest {

  private static Project project(boolean delivery) {
    Project project = new Project();
    project.id = "p-1";
    project.deliveryEnabled = delivery;
    return project;
  }

  private static FrontDesk desk(boolean session, boolean delivery) {
    FrontDesk desk = new FrontDesk();
    desk.projectId = "p-1";
    desk.tokenId = "t-1";
    desk.tokenValue = "qits_tok_daemon";
    desk.tokenSubject = "tok-daemon";
    if (session) {
      desk.sessionTokenId = "s-1";
      desk.sessionTokenValue = "qits_tok_session";
      desk.sessionTokenSubject = "tok-session";
    }
    if (delivery) {
      desk.deliveryTokenId = "d-1";
      desk.deliveryTokenValue = "qits_tok_delivery";
      desk.deliveryTokenSubject = "tok-delivery";
    }
    return desk;
  }

  private static Refinement room(String entityId, boolean token) {
    Refinement room = new Refinement();
    room.entityId = entityId;
    room.projectId = "p-1";
    if (token) {
      room.tokenId = "r-" + entityId;
      room.tokenValue = "qits_tok_room_" + entityId;
      room.tokenSubject = "tok-room-" + entityId;
    }
    return room;
  }

  private static Map<String, Credential> byKey(SessionCredentials frame) {
    return frame.credentials().stream()
        .collect(Collectors.toMap(Credential::key, credential -> credential));
  }

  @Test
  void theLoopOnPutsTheDeliveryTokenUnderTheFrontDeskAndTheSessionTokenUnderTheAgents() {
    Map<String, Credential> keys =
        byKey(DeskSessionCredentials.compose(project(true), desk(true, true), List.of()));

    assertEquals(2, keys.size());
    assertEquals("qits_tok_delivery", keys.get(SessionCredentials.FRONT_DESK_KEY).token());
    assertEquals("tok-delivery", keys.get(SessionCredentials.FRONT_DESK_KEY).subject());
    assertEquals("qits_tok_session", keys.get(SessionCredentials.DESK_AGENTS_KEY).token());
    assertEquals(
        "tok-session",
        keys.get(SessionCredentials.DESK_AGENTS_KEY).subject(),
        "a person-started session never holds qits:desk");
  }

  @Test
  void theLoopOffPutsTheSessionTokenUnderBothDeskKeys() {
    // A delivery token still on the row (the loop just went off, the revoke not yet landed) is not
    // what the front desk runs on: the setting decides, not the column.
    Map<String, Credential> keys =
        byKey(DeskSessionCredentials.compose(project(false), desk(true, true), List.of()));

    assertEquals("qits_tok_session", keys.get(SessionCredentials.FRONT_DESK_KEY).token());
    assertEquals("qits_tok_session", keys.get(SessionCredentials.DESK_AGENTS_KEY).token());
  }

  @Test
  void theLoopOnWithNoDeliveryTokenYetFallsBackToTheSessionToken() {
    Map<String, Credential> keys =
        byKey(DeskSessionCredentials.compose(project(true), desk(true, false), List.of()));

    assertEquals("tok-session", keys.get(SessionCredentials.FRONT_DESK_KEY).subject());
    assertEquals("tok-session", keys.get(SessionCredentials.DESK_AGENTS_KEY).subject());
  }

  @Test
  void aKeyWithNoTokenIsLeftOutSoTheDaemonFailsClosed() {
    Map<String, Credential> noSession =
        byKey(DeskSessionCredentials.compose(project(true), desk(false, true), List.of()));
    assertEquals(
        List.of(SessionCredentials.FRONT_DESK_KEY),
        List.copyOf(noSession.keySet()),
        "the loop's token still stands; desk-agents has nothing and is omitted");

    assertTrue(
        DeskSessionCredentials.compose(project(false), desk(false, true), List.of())
            .credentials()
            .isEmpty(),
        "loop off and no session token: nothing at all");
    assertTrue(
        DeskSessionCredentials.compose(project(true), null, List.of()).credentials().isEmpty(),
        "no desk row: no desk keys");

    FrontDesk blank = desk(true, false);
    blank.sessionTokenValue = "  ";
    assertTrue(
        DeskSessionCredentials.compose(project(false), blank, List.of()).credentials().isEmpty(),
        "a blank token is no token");
  }

  @Test
  void eachRefinementRoomHoldingATokenIsKeyedByItsEntityId() {
    SessionCredentials frame =
        DeskSessionCredentials.compose(
            project(false),
            desk(true, false),
            List.of(room("e-1", true), room("e-2", false), room("e-3", true)));
    Map<String, Credential> keys = byKey(frame);

    assertEquals(
        List.of(
            SessionCredentials.FRONT_DESK_KEY, SessionCredentials.DESK_AGENTS_KEY, "e-1", "e-3"),
        frame.credentials().stream().map(Credential::key).toList());
    assertEquals("qits_tok_room_e-1", keys.get("e-1").token());
    assertEquals("tok-room-e-3", keys.get("e-3").subject());
    assertFalse(keys.containsKey("e-2"), "a room still waiting for its mint is left out");
  }

  @Test
  void theDaemonsOwnTokenIsNeverInTheList() {
    SessionCredentials frame =
        DeskSessionCredentials.compose(project(true), desk(true, true), List.of(room("e-1", true)));
    frame
        .credentials()
        .forEach(credential -> assertFalse(credential.token().equals("qits_tok_daemon")));
    assertFalse(frame.toString().contains("qits_tok_"), "a logged frame carries no token value");
  }
}
