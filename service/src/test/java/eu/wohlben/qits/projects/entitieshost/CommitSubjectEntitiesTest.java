package eu.wohlben.qits.projects.entitieshost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.ProjectEpicsController;
import eu.wohlben.qits.entities.api.ProjectTicketsController;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The commit-subject reader: the grammar, the resolution across both databases, and — the assertion
 * this class exists for — that a subject with no id is <b>silent</b>.
 *
 * <p>The grammar cases are plain assertions over the static {@code reference}, which needs no
 * application at all; the resolution cases go through the real REST intake so the numbers and the
 * slugs are the ones the writer actually allocated rather than fixtures agreeing with themselves.
 */
@QuarkusTest
class CommitSubjectEntitiesTest {

  @Inject CommitSubjectEntities subjects;

  // --- The grammar ------------------------------------------------------------------------------

  @Test
  void theIdSitsInTheScopeOfAConventionalCommitSubject() {
    assertEquals(
        Optional.of(new CommitSubjectEntities.QualifiedId("qits", 1337L)),
        CommitSubjectEntities.reference("fix(qits-1337): the button is the wrong colour"));
  }

  @Test
  void everyTermShapeIsAccepted() {
    for (String subject :
        List.of(
            "feat(qits-1): a",
            "fix(qits-1): a",
            "chore(qits-1): a",
            "epics/control(qits-1): a",
            "(qits-1): a",
            "feat(qits-1)!: a",
            "(qits-1)!: a",
            "fix(qits-1):a")) {
      assertEquals(
          Optional.of(new CommitSubjectEntities.QualifiedId("qits", 1L)),
          CommitSubjectEntities.reference(subject),
          subject);
    }
  }

  /** The split is on the LAST hyphen-then-digits, so a slug that ends in digits still reads. */
  @Test
  void aProjectSlugEndingInDigitsStillSplitsCorrectly() {
    assertEquals(
        Optional.of(new CommitSubjectEntities.QualifiedId("other-2", 7L)),
        CommitSubjectEntities.reference("fix(other-2-7): a"));
  }

  @Test
  void aSubjectWithNoIdIsEmpty() {
    for (String subject :
        List.of(
            "chore: bump the dependencies",
            "just some words",
            "fix(): nothing in the parens",
            "fix(qits): no number",
            "fix(-7): no slug",
            "fix(qits-7) no colon",
            "fix(qits-seven): not digits",
            "fix: tidy (qits-7): a parenthesised aside is not the scope",
            "")) {
      assertTrue(CommitSubjectEntities.reference(subject).isEmpty(), subject);
    }
    assertTrue(CommitSubjectEntities.reference(null).isEmpty());
  }

  /**
   * Only the first line counts — including when the body itself holds something id-shaped, which is
   * the ordinary case of a commit message quoting an id it is not filed under.
   */
  @Test
  void theBodyIsIgnoredEvenWhenItLooksLikeAnId() {
    assertTrue(
        CommitSubjectEntities.reference("chore: bump\n\nRelated: fix(qits-9): the other one")
            .isEmpty());
    assertEquals(
        Optional.of(new CommitSubjectEntities.QualifiedId("qits", 1L)),
        CommitSubjectEntities.reference("fix(qits-1): the real one\n\nfix(qits-9): not this"));
  }

  /** The reading and the writing halves produce the identical string. */
  @Test
  void theRenderedFormIsTheOneTheWriterProduces() {
    CommitSubjectEntities.QualifiedId id = new CommitSubjectEntities.QualifiedId("qits", 1337L);
    assertEquals("qits-1337", id.rendered());
    assertEquals(
        id.rendered(), eu.wohlben.qits.projects.api.QualifiedEntityIds.render("qits", 1337L));
  }

  // --- "no subject" is silent -------------------------------------------------------------------

  /**
   * <b>The most important assertion in this class.</b> Every commit already in history predates
   * this convention, so a subject with no id must be an ordinary answer and must leave no trace
   * anything could ever read as degraded: no warning, no error, no counted violation.
   */
  @Test
  void aSubjectWithNoIdIsNotAnErrorAndIsNotEvenMentioned() {
    try (CapturedLogs logs = new CapturedLogs(CommitSubjectEntities.class)) {
      assertTrue(subjects.resolve("chore: bump the dependencies").isEmpty());
      assertTrue(subjects.resolve("just some words").isEmpty());
      assertTrue(subjects.resolve("fix(qits-seven): malformed").isEmpty());
      assertTrue(subjects.resolve("fix(" + UUID.randomUUID() + "-7): no such project").isEmpty());
      assertEquals(List.of(), logs.messages());
    }
  }

  // --- Resolution -------------------------------------------------------------------------------

  @Test
  void aWellFormedSubjectResolvesToItsEntityWithArchetypeAndStatus() {
    String projectId = createProject("Subject Reader " + UUID.randomUUID());
    String slug = projectSlug(projectId);
    long number = createTicket(projectId, "The button is wrong");

    CommitSubjectEntities.NamedEntity named =
        subjects.resolve("fix(" + slug + "-" + number + "): the button is the wrong colour").orElseThrow();

    assertEquals(projectId, named.projectId());
    assertEquals(slug, named.projectSlug());
    assertEquals(number, named.number());
    assertEquals(slug + "-" + number, named.qualifiedId());
    assertEquals(Archetype.TICKET, named.archetype());
    assertEquals("REPORTED", named.status());
    assertEquals("The button is wrong", named.title());
  }

  @Test
  void anEpicResolvesToo() {
    String projectId = createProject("Subject Epics " + UUID.randomUUID());
    String slug = projectSlug(projectId);
    long number = createEpic(projectId, "The plan");

    CommitSubjectEntities.NamedEntity named =
        subjects.resolve("feat(" + slug + "-" + number + "): the plan").orElseThrow();

    assertEquals(Archetype.EPIC, named.archetype());
    assertEquals("REFINING", named.status());
  }

  @Test
  void aWellFormedIdNamingNoEntityIsEmpty() {
    String projectId = createProject("Subject Gaps " + UUID.randomUUID());
    String slug = projectSlug(projectId);
    assertTrue(subjects.resolve("fix(" + slug + "-999999): nothing there").isEmpty());
  }

  /**
   * <b>It resolves across projects, and that is the decision.</b> The form is project-qualified,
   * which is the whole reason it is qualified — so {@code other-7} names the other project's entity
   * 7 and is answered rather than refused. The {@code projectId} it carries is what lets a consumer
   * that cares refuse it in its own words.
   */
  @Test
  void anIdFromAnotherProjectResolvesToThatProjectsEntity() {
    String here = createProject("Subject Here " + UUID.randomUUID());
    String there = createProject("Subject There " + UUID.randomUUID());
    long hereNumber = createTicket(here, "Mine");
    long thereNumber = createTicket(there, "Theirs");

    CommitSubjectEntities.NamedEntity mine =
        subjects.resolve("fix(" + projectSlug(here) + "-" + hereNumber + "): a").orElseThrow();
    CommitSubjectEntities.NamedEntity theirs =
        subjects.resolve("fix(" + projectSlug(there) + "-" + thereNumber + "): a").orElseThrow();

    assertEquals(here, mine.projectId());
    assertEquals(there, theirs.projectId());
    assertEquals("Mine", mine.title());
    assertEquals("Theirs", theirs.title());
    // Two fresh projects each start their run at 1, so the same number names two different
    // entities — which is exactly what the qualifier is for.
    assertEquals(hereNumber, thereNumber);
  }

  // --- Fixtures ---------------------------------------------------------------------------------

  private static String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private static String projectSlug(String projectId) {
    return given()
        .when()
        .get("/projects/api/projects/" + projectId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.slug");
  }

  private static long createTicket(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectTicketsController.CreateTicketRequest(
                title, "It occurs.", null, "BUG", null))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .jsonPath()
        .getLong("ticket.number");
  }

  private static long createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectEpicsController.CreateEpicRequest(title, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .jsonPath()
        .getLong("epic.number");
  }

  /**
   * The WARNING-and-above records one class logs while this is open. {@code
   * projects/idphost/CapturedErrors} is the same device one package over; surefire installs the
   * JBoss LogManager as the JUL manager, so a JUL handler on the class's logger sees what its JBoss
   * Logging logger would write.
   */
  private static final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    private final Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
              messages.add(String.valueOf(record.getMessage()));
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    CapturedLogs(Class<?> source) {
      logger = Logger.getLogger(source.getName());
      logger.addHandler(handler);
    }

    List<String> messages() {
      return List.copyOf(messages);
    }

    @Override
    public void close() {
      logger.removeHandler(handler);
    }
  }
}
