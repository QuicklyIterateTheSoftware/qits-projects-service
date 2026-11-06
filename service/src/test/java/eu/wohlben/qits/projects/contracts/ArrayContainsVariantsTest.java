package eu.wohlben.qits.projects.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** What {@link ArrayContainsVariants} rewrites in a pact, and what it leaves alone. */
class ArrayContainsVariantsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A V4 pact answering {@code {entities: [a, b, c]}} with arrayContains variants at {@code indexes}. */
  private static String pact(int... indexes) {
    StringBuilder variants = new StringBuilder();
    for (int index : indexes) {
      if (!variants.isEmpty()) {
        variants.append(',');
      }
      variants
          .append("{\"index\":")
          .append(index)
          .append(",\"rules\":{\"$.n\":{\"combine\":\"AND\",\"matchers\":[{\"match\":\"type\",\"v\":")
          .append(index)
          .append("}]}}}");
    }
    return "{\"interactions\":[{\"response\":{\"status\":200,"
        + "\"body\":{\"content\":{\"entities\":[{\"n\":\"a\"},{\"n\":\"b\"},{\"n\":\"c\"}]},"
        + "\"contentType\":\"application/json\",\"encoded\":false},"
        + "\"matchingRules\":{\"body\":{\"$.entities\":{\"combine\":\"AND\",\"matchers\":"
        + "[{\"match\":\"arrayContains\",\"variants\":["
        + variants
        + "]}]}}}}}],\"metadata\":{\"pactSpecification\":{\"version\":\"4.0\"}}}";
  }

  private static JsonNode response(String pact) throws Exception {
    return JSON.readTree(pact).path("interactions").path(0).path("response");
  }

  @Test
  void aDenseVariantListIsLeftAsWritten() {
    String dense = pact(0, 1, 2);
    assertSame(dense, ArrayContainsVariants.densify(dense));
  }

  @Test
  void aVariantListSkippingAnElementKeepsOnlyTheNamedElementsRenumbered() throws Exception {
    JsonNode response = response(ArrayContainsVariants.densify(pact(1, 2)));

    assertEquals(
        "[{\"n\":\"b\"},{\"n\":\"c\"}]",
        response.path("body").path("content").path("entities").toString());
    JsonNode variants =
        response.path("matchingRules").path("body").path("$.entities").path("matchers").path(0)
            .path("variants");
    assertEquals(2, variants.size());
    // each variant keeps its own rules (v = the index the consumer gave it) and its element
    assertEquals(0, variants.path(0).path("index").asInt());
    assertEquals(1, variants.path(0).path("rules").path("$.n").path("matchers").path(0).path("v").asInt());
    assertEquals(1, variants.path(1).path("index").asInt());
    assertEquals(2, variants.path(1).path("rules").path("$.n").path("matchers").path(0).path("v").asInt());
  }

  @Test
  void variantsOutOfOrderAreSortedWithTheirElements() throws Exception {
    JsonNode response = response(ArrayContainsVariants.densify(pact(2, 0)));

    assertEquals(
        "[{\"n\":\"a\"},{\"n\":\"c\"}]",
        response.path("body").path("content").path("entities").toString());
    JsonNode variants =
        response.path("matchingRules").path("body").path("$.entities").path("matchers").path(0)
            .path("variants");
    assertEquals(0, variants.path(0).path("rules").path("$.n").path("matchers").path(0).path("v").asInt());
    assertEquals(2, variants.path(1).path("rules").path("$.n").path("matchers").path(0).path("v").asInt());
  }

  @Test
  void aVariantNamingNoElementLeavesThePactAlone() {
    String beyond = pact(1, 5);
    assertSame(beyond, ArrayContainsVariants.densify(beyond));
  }
}
