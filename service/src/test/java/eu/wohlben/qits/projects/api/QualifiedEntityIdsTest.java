package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.wohlben.qits.entities.dto.EpicDto;
import eu.wohlben.qits.entities.dto.TicketDto;
import eu.wohlben.qits.projects.control.ProjectService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The renderer's two claims, asserted rather than described: <b>one lookup per listing</b>, and a
 * project that cannot be resolved leaving the field null instead of throwing.
 *
 * <p>Plain JUnit, no application: {@code QualifiedEntityIds} holds one collaborator and the whole
 * of what is under test is how many times it is asked. A {@code @TestProfile} for this would be a
 * whole Quarkus app at roughly 125 MB of retained metaspace inside a 4 GB CI step, for a question
 * that is a counter.
 */
class QualifiedEntityIdsTest {

  /** A {@link ProjectService} that answers from a map and counts how often it was asked. */
  private static final class CountingProjects extends ProjectService {

    private final Map<String, String> slugs = new HashMap<>();
    private final List<Collection<String>> calls = new ArrayList<>();

    CountingProjects with(String projectId, String slug) {
      slugs.put(projectId, slug);
      return this;
    }

    @Override
    public Map<String, String> slugsByIds(Collection<String> projectIds) {
      calls.add(List.copyOf(projectIds));
      Map<String, String> answer = new HashMap<>();
      for (String id : projectIds) {
        if (slugs.containsKey(id)) {
          answer.put(id, slugs.get(id));
        }
      }
      return answer;
    }
  }

  private static QualifiedEntityIds over(CountingProjects projects) {
    QualifiedEntityIds qualifier = new QualifiedEntityIds();
    qualifier.projectService = projects;
    return qualifier;
  }

  private static EpicDto epic(String id, String projectId, long number) {
    return new EpicDto(id, projectId, number, null, "T", "t", "REFINING", null, null, null, null, List.of());
  }

  /**
   * <b>The N+1 answer.</b> Forty epics across two projects, one lookup — and the lookup is asked
   * about the two DISTINCT project ids rather than about forty.
   */
  @Test
  void aListingIssuesExactlyOneProjectLookupHoweverLongItIs() {
    CountingProjects projects = new CountingProjects().with("p1", "one").with("p2", "two");
    List<EpicDto> epics = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      epics.add(epic("e" + i, i % 2 == 0 ? "p1" : "p2", i));
    }

    List<EpicDto> qualified = over(projects).qualifyEpics(epics);

    assertEquals(1, projects.calls.size(), "one lookup for the whole listing");
    assertEquals(List.of("p1", "p2"), List.copyOf(projects.calls.get(0)));
    assertEquals("one-0", qualified.get(0).qualifiedId());
    assertEquals("two-1", qualified.get(1).qualifiedId());
    assertEquals("one-38", qualified.get(38).qualifiedId());
  }

  /** A single get is the same call, asked about a list of one — still exactly one lookup. */
  @Test
  void oneRowIsOneLookupToo() {
    CountingProjects projects = new CountingProjects().with("p1", "qits");
    assertEquals("qits-7", over(projects).qualify(epic("e", "p1", 7)).qualifiedId());
    assertEquals(1, projects.calls.size());
  }

  /** A project id naming no project leaves the field null, and never throws. */
  @Test
  void anUnresolvableProjectLeavesTheFieldNull() {
    CountingProjects projects = new CountingProjects().with("p1", "qits");
    List<EpicDto> qualified =
        over(projects).qualifyEpics(List.of(epic("a", "p1", 1), epic("b", "gone", 2)));

    assertEquals("qits-1", qualified.get(0).qualifiedId());
    assertNull(qualified.get(1).qualifiedId());
  }

  /** An empty listing is returned unchanged, without asking the database at all. */
  @Test
  void anEmptyListingAsksNothing() {
    CountingProjects projects = new CountingProjects();
    List<EpicDto> empty = List.of();
    assertSame(empty, over(projects).qualifyEpics(empty));
    assertEquals(0, projects.calls.size());
  }

  /** The other shapes go through the same one implementation. */
  @Test
  void ticketsAreQualifiedTheSameWay() {
    CountingProjects projects = new CountingProjects().with("p1", "qits");
    TicketDto ticket =
        new TicketDto(
            "t", "p1", 42L, null, "T", "t", "BUG", "REPORTED", false, null, null, "i", null, null,
            null, List.of());
    assertEquals("qits-42", over(projects).qualifyTickets(List.of(ticket)).get(0).qualifiedId());
    assertEquals(1, projects.calls.size());
  }
}
