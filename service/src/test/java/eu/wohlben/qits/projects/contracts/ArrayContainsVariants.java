package eu.wohlben.qits.projects.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A response's {@code arrayContains} variants, made dense so pact-jvm pairs each with its own
 * element</b> (qits-1897).
 *
 * <p>A V4 {@code arrayContains} matcher lists its variants, each naming the expected element it is
 * about by {@code index}; an element no variant names is not checked at all. pact-rust (which writes
 * the consumers' pacts) honours that index. pact-jvm's verifier (4.7.5, {@code Matchers.compareLists})
 * does not: it walks the variants list and pairs the <i>n</i>-th variant's rules with the
 * <i>n</i>-th expected element, whatever index the variant names. A pact whose variants are 0, 1, 2…
 * verifies the same either way; one whose variants skip an index (qits-projects-frontend
 * 2026.1106.155548's {@code listProjectWork} in "an epic in detail" names 1–7 of eight elements)
 * gets every later element checked against its neighbour's rules — a task without the {@code
 * repositoryId} regex its own variant carries — and fails on a body the provider answers correctly.
 *
 * <p>So, in each interaction's response only, an array whose {@code arrayContains} variants are not
 * exactly 0…n−1 in order is rewritten to what pact-rust checks: its elements become the named ones
 * in variant order, and the variants are renumbered 0…n−1. An element no variant named is dropped,
 * which is what it already was to the consumer — unchecked. Nothing else in the pact changes; a
 * matcher path this does not resolve (a wildcard, a missing node) is left as it is.
 */
final class ArrayContainsVariants {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One path segment: {@code .name}, {@code ['name']} or {@code [3]}. */
  private static final Pattern SEGMENT =
      Pattern.compile("\\.([A-Za-z_$][A-Za-z0-9_$-]*)|\\['([^']*)']|\\[(\\d+)]");

  private ArrayContainsVariants() {}

  /** The pact JSON with every sparse response {@code arrayContains} made dense; as is otherwise. */
  static String densify(String pactJson) {
    JsonNode pact;
    try {
      pact = JSON.readTree(pactJson);
    } catch (IOException e) {
      throw new UncheckedIOException("Reading a pact", e);
    }
    boolean changed = false;
    JsonNode interactions = pact.path("interactions");
    for (JsonNode interaction : interactions) {
      if (interaction.path("response") instanceof ObjectNode response) {
        changed |= densifyResponse(response);
      }
    }
    if (!changed) {
      return pactJson;
    }
    try {
      return JSON.writeValueAsString(pact);
    } catch (IOException e) {
      throw new UncheckedIOException("Writing a pact", e);
    }
  }

  private static boolean densifyResponse(ObjectNode response) {
    JsonNode rules = response.path("matchingRules").path("body");
    JsonNode body = response.path("body");
    // V4 wraps the body as {content, contentType, encoded}; V3 has it bare.
    JsonNode root = body.has("content") && body.has("contentType") ? body.get("content") : body;
    if (!rules.isObject() || root.isMissingNode()) {
      return false;
    }
    boolean changed = false;
    Iterator<Map.Entry<String, JsonNode>> paths = rules.fields();
    while (paths.hasNext()) {
      Map.Entry<String, JsonNode> rule = paths.next();
      List<JsonNode> contains = new ArrayList<>();
      for (JsonNode matcher : rule.getValue().path("matchers")) {
        if ("arrayContains".equals(matcher.path("match").asText())) {
          contains.add(matcher);
        }
      }
      if (contains.size() != 1 || !(contains.get(0).path("variants") instanceof ArrayNode variants)) {
        continue;
      }
      if (resolve(root, rule.getKey()) instanceof ArrayNode expected) {
        changed |= densify(expected, variants);
      }
    }
    return changed;
  }

  private static boolean densify(ArrayNode expected, ArrayNode variants) {
    List<JsonNode> ordered = new ArrayList<>();
    variants.forEach(ordered::add);
    ordered.sort(Comparator.comparingInt(variant -> variant.path("index").asInt(-1)));
    boolean dense = true;
    for (int i = 0; i < variants.size(); i++) {
      dense &= variants.get(i).path("index").asInt(-1) == i;
    }
    if (dense) {
      return false;
    }
    List<JsonNode> elements = new ArrayList<>();
    for (JsonNode variant : ordered) {
      int index = variant.path("index").asInt(-1);
      if (index < 0 || index >= expected.size()) {
        return false; // a variant naming no element: leave the pact as the consumer wrote it
      }
      elements.add(expected.get(index));
    }
    expected.removeAll();
    elements.forEach(expected::add);
    variants.removeAll();
    for (int i = 0; i < ordered.size(); i++) {
      ((ObjectNode) ordered.get(i)).put("index", i);
      variants.add(ordered.get(i));
    }
    return true;
  }

  /** The node at a plain matcher path ({@code $.a['b'][0]}), or null for anything else. */
  private static JsonNode resolve(JsonNode root, String path) {
    if (!path.startsWith("$")) {
      return null;
    }
    JsonNode node = root;
    Matcher segment = SEGMENT.matcher(path);
    int at = 1;
    while (at < path.length()) {
      if (!segment.find(at) || segment.start() != at) {
        return null;
      }
      if (segment.group(3) != null) {
        node = node.path(Integer.parseInt(segment.group(3)));
      } else {
        node = node.path(segment.group(1) != null ? segment.group(1) : segment.group(2));
      }
      at = segment.end();
    }
    return node.isMissingNode() ? null : node;
  }
}
