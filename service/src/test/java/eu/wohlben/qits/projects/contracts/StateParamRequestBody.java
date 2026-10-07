package eu.wohlben.qits.projects.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A pact request body's state-param stand-ins, replaced by the values this run's state
 * returned</b> — the half of pact's {@code ProviderState} generator a pact cannot express.
 *
 * <p>A consumer pins every state param in {@code providerStates[].params} (its example: {@code
 * qualifiedId = contract-00000001-1}), and the provider's {@code @State} returns the real one. For a
 * path, or a JSON value with a generator, pact-jvm swaps them itself. Two places it cannot reach:
 *
 * <ul>
 *   <li><b>A member name.</b> {@code transitionWork}'s body is keyed by qualified id, and pact has no
 *       generator for a JSON key, so the CLI's body carries the frozen {@code contract-00000001-1}
 *       as a key.
 *   <li><b>The golden masters' own placeholder.</b> A recorded request body names a param as the
 *       quoted string {@code "{name}"}, as a value or a member name ({@link
 *       GoldenMasterRecordingTest}'s {@code BODY_PARAM}); a consumer that replays the recorded body
 *       carries it as written.
 * </ul>
 *
 * <p>So, in the request body only, a JSON string — value or member name — that is <b>exactly</b>
 * {@code "{name}"} for a param the state returned, or <b>exactly</b> the consumer's pinned value of
 * a param the state returned differently, becomes the returned value. Nothing partial, nothing but
 * strings, nothing in the response: what is matched is untouched, only what is sent is made to name
 * the entities this run created. A pinned value two params share is ambiguous and fails.
 */
final class StateParamRequestBody {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The golden masters' body placeholder, the whole string: {@code {qualifiedId}}. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

  private StateParamRequestBody() {}

  /** One string swapped, for the verification log. */
  record Swap(String from, String to) {}

  /** The body with its stand-ins replaced, and what was replaced. */
  record Rewritten(byte[] body, List<Swap> swaps) {}

  /**
   * @param body the request body as the pact sends it
   * @param pinned the consumer's state params, from the interaction's provider states
   * @param returned what the provider's states returned (pact-jvm's provider-state context)
   * @return the rewritten body, or {@code null} when the body is not JSON or nothing changed
   */
  static Rewritten rewrite(byte[] body, Map<String, ?> pinned, Map<String, ?> returned)
      throws IOException {
    if (body == null || body.length == 0) {
      return null;
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (IOException notJson) {
      return null;
    }
    if (root == null || !root.isContainerNode()) {
      return null;
    }
    Map<String, String> replacements = replacements(pinned, returned);
    List<Swap> swaps = new ArrayList<>();
    JsonNode out = walk(root, replacements, returned, swaps);
    return swaps.isEmpty() ? null : new Rewritten(JSON.writeValueAsBytes(out), swaps);
  }

  /** Each pinned value the state returned differently → the returned value. */
  private static Map<String, String> replacements(Map<String, ?> pinned, Map<String, ?> returned) {
    Map<String, String> byPinned = new HashMap<>();
    Map<String, String> owner = new HashMap<>();
    for (Map.Entry<String, ?> param : pinned.entrySet()) {
      Object real = returned.get(param.getKey());
      if (param.getValue() == null || real == null) {
        continue;
      }
      String from = param.getValue().toString();
      String to = real.toString();
      if (from.equals(to)) {
        continue;
      }
      String earlier = owner.putIfAbsent(from, param.getKey());
      if (earlier != null && !Objects.equals(byPinned.get(from), to)) {
        throw new IllegalStateException(
            "The pact pins '"
                + from
                + "' for both '"
                + earlier
                + "' and '"
                + param.getKey()
                + "', which this run's state answered differently — a request body naming it is"
                + " ambiguous");
      }
      byPinned.put(from, to);
    }
    return byPinned;
  }

  private static JsonNode walk(
      JsonNode node, Map<String, String> replacements, Map<String, ?> returned, List<Swap> swaps) {
    if (node.isObject()) {
      ObjectNode out = JSON.createObjectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      Map<String, JsonNode> rebuilt = new LinkedHashMap<>();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        rebuilt.put(
            swap(field.getKey(), replacements, returned, swaps),
            walk(field.getValue(), replacements, returned, swaps));
      }
      rebuilt.forEach(out::set);
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JSON.createArrayNode();
      node.forEach(element -> out.add(walk(element, replacements, returned, swaps)));
      return out;
    }
    if (node.isTextual()) {
      String text = node.asText();
      String swapped = swap(text, replacements, returned, swaps);
      return swapped.equals(text) ? node : TextNode.valueOf(swapped);
    }
    return node;
  }

  private static String swap(
      String text, Map<String, String> replacements, Map<String, ?> returned, List<Swap> swaps) {
    Matcher placeholder = PLACEHOLDER.matcher(text);
    if (placeholder.matches() && returned.get(placeholder.group(1)) != null) {
      String to = returned.get(placeholder.group(1)).toString();
      swaps.add(new Swap(text, to));
      return to;
    }
    String to = replacements.get(text);
    if (to != null) {
      swaps.add(new Swap(text, to));
      return to;
    }
    return text;
  }
}
