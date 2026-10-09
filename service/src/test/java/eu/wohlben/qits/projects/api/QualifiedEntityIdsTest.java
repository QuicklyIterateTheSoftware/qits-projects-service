package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.ProjectService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

  /** A work entity in the merged shape, its qualified id not yet filled. */
  private static TransitionedEntity entity(
      String id, Archetype archetype, String projectId, long number) {
    return new TransitionedEntity(
        id, archetype, projectId, number, null, "T", "t", projectId, null, "REPORTED", null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, false,
        List.of(), null, null, null);
  }

  private static TransitionedEntity epic(String id, String projectId, long number) {
    return entity(id, Archetype.EPIC, projectId, number);
  }

  /**
   * <b>The N+1 answer.</b> Forty entities across two projects, one lookup — and the lookup is asked
   * about the two DISTINCT project ids rather than about forty.
   */
  @Test
  void aListingIssuesExactlyOneProjectLookupHoweverLongItIs() {
    CountingProjects projects = new CountingProjects().with("p1", "one").with("p2", "two");
    List<TransitionedEntity> epics = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      epics.add(epic("e" + i, i % 2 == 0 ? "p1" : "p2", i));
    }

    List<TransitionedEntity> qualified = over(projects).qualifyEntities(epics);

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
    List<TransitionedEntity> qualified =
        over(projects).qualifyEntities(List.of(epic("a", "p1", 1), epic("b", "gone", 2)));

    assertEquals("qits-1", qualified.get(0).qualifiedId());
    assertNull(qualified.get(1).qualifiedId());
  }

  /** An empty listing is returned unchanged, without asking the database at all. */
  @Test
  void anEmptyListingAsksNothing() {
    CountingProjects projects = new CountingProjects();
    List<TransitionedEntity> empty = List.of();
    assertSame(empty, over(projects).qualifyEntities(empty));
    Map<String, TransitionedEntity> none = Map.of();
    assertSame(none, over(projects).qualifyEntities(none));
    assertEquals(0, projects.calls.size());
  }

  /**
   * A transition's answer is a map, and it goes through the same one lookup: every archetype
   * alike, the keys kept exactly as they were handed in, a null slug left null there too.
   */
  @Test
  void aTransitionsMapIsQualifiedInOneLookupAndKeepsItsKeys() {
    CountingProjects projects = new CountingProjects().with("p1", "qits");
    Map<String, TransitionedEntity> written = new LinkedHashMap<>();
    written.put("qits-42", entity("t", Archetype.TICKET, "p1", 42));
    written.put("f", entity("f", Archetype.FEATURE, "p1", 43));
    written.put("g", entity("g", Archetype.TASK, "gone", 44));

    Map<String, TransitionedEntity> qualified = over(projects).qualifyEntities(written);

    assertEquals(List.of("qits-42", "f", "g"), List.copyOf(qualified.keySet()));
    assertEquals("qits-42", qualified.get("qits-42").qualifiedId());
    assertEquals("qits-43", qualified.get("f").qualifiedId());
    assertNull(qualified.get("g").qualifiedId());
    assertEquals(1, projects.calls.size());
  }
}
