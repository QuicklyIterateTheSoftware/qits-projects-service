package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The four predicate shapes, their canonical JSON and the write door's validation. Plain JUnit. */
class CriterionPredicateTest {

  private static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }

  @Test
  void eachShapeHasOneCanonicalSpellingThatRoundTrips() {
    assertEquals(
        "{\"entityId\":\"e1\",\"status\":\"VERIFIED\"}",
        new CriterionPredicate.EntityStatusIs("e1", "VERIFIED").canonicalJson());
    assertEquals(
        "{\"applicationName\":\"qits-projects\",\"environmentName\":null,\"minimumVersion\":\"2026.930.1\"}",
        new CriterionPredicate.DeploymentActive("qits-projects", null, "2026.930.1")
            .canonicalJson());
    assertEquals(
        "{\"repositoryName\":\"qits-qits\",\"projectId\":\"p\",\"minimumVersion\":null}",
        new CriterionPredicate.ScmRelease("qits-qits", "p", null).canonicalJson());
    assertEquals("{}", new CriterionPredicate.Approval().canonicalJson());

    // Key order on the way in does not matter; the stored spelling does not vary.
    CriterionPredicate.Decoded decoded =
        CriterionPredicate.decode(
            "SCM_RELEASE", map("minimumVersion", null, "repositoryName", "qits-qits"), "c");
    assertTrue(decoded.valid(), decoded.violations().toString());
    String json = decoded.predicate().canonicalJson();
    assertEquals(
        "{\"repositoryName\":\"qits-qits\",\"projectId\":null,\"minimumVersion\":null}", json);
    assertEquals(decoded.predicate(), CriterionPredicate.parse(CriterionKind.SCM_RELEASE, json));
  }

  @Test
  void everyViolationIsReportedAtOnce() {
    CriterionPredicate.Decoded decoded =
        CriterionPredicate.decode(
            "ENTITY_STATUS", map("status", "FINISHED", "entity", "e1"), "group 1, criterion 1");
    assertEquals(
        List.of(
            "group 1, criterion 1: unknown field entity",
            "group 1, criterion 1: entityId is required",
            "group 1, criterion 1: unknown status FINISHED"),
        decoded.violations());
  }

  @Test
  void theRulesOfTheCatalogue() {
    assertTrue(
        CriterionPredicate.decode("NOPE", Map.of(), "c").violations().get(0).contains("unknown kind NOPE"));
    assertTrue(
        CriterionPredicate.decode(null, Map.of(), "c").violations().get(0).contains("kind is required"));
    assertEquals(
        List.of("c: applicationName must not be blank"),
        CriterionPredicate.decode("DEPLOYMENT_ACTIVE", map("applicationName", " "), "c")
            .violations());
    assertEquals(
        List.of("c: repositoryName is required"),
        CriterionPredicate.decode("SCM_RELEASE", Map.of(), "c").violations());
    assertEquals(
        List.of("c: minimumVersion 2026..1 is not a version of dot-separated segments"),
        CriterionPredicate.decode(
                "DEPLOYMENT_ACTIVE",
                map("applicationName", "qits-ci", "minimumVersion", "2026..1"),
                "c")
            .violations());
    assertEquals(
        List.of("c: unknown field note (APPROVAL takes none)"),
        CriterionPredicate.decode("APPROVAL", map("note", "x"), "c").violations());
    assertTrue(CriterionPredicate.decode("APPROVAL", null, "c").valid());
  }
}
