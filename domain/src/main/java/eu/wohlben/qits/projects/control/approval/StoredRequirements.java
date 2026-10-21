package eu.wohlben.qits.projects.control.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * <b>The content requirements of one fold as {@code release_request.approval_content_reasons} holds
 * them</b> — a JSON array of {@code {id, reason, admits, excludesAuthors, detail}}, {@code admits} as
 * role words with the project <b>id</b> after the {@code @} (storage, not display). Built as a
 * Jackson tree, so nothing needs registering for the native image.
 *
 * <p>A value from before epic qits-1038 is an array of <b>strings</b> (the old reasons). It reads as
 * "not read yet" — {@link #read} answers empty — so the fold is read again and stored in the new
 * shape, failing closed into a person meanwhile, exactly as a fold nobody has read does.
 */
public final class StoredRequirements {

  private static final ObjectMapper JSON = new ObjectMapper();

  private StoredRequirements() {}

  /** {@code requirements} as stored. */
  public static String write(List<ApprovalRequirement> requirements) {
    ArrayNode array = JSON.createArrayNode();
    for (ApprovalRequirement requirement : requirements) {
      ObjectNode node = array.addObject();
      node.put("id", requirement.id());
      node.put("reason", requirement.reason());
      ArrayNode admits = node.putArray("admits");
      requirement.admits().forEach(role -> admits.add(role.toString()));
      node.put("excludesAuthors", requirement.excludesAuthors());
      if (requirement.detail() != null) {
        node.put("detail", requirement.detail());
      }
    }
    try {
      return JSON.writeValueAsString(array);
    } catch (Exception e) {
      throw new IllegalStateException("Could not write approval requirements", e);
    }
  }

  /** The requirements {@code json} holds, or empty where it holds none in this shape. */
  public static Optional<List<ApprovalRequirement>> read(String json) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode node = JSON.readTree(json);
      if (node == null || !node.isArray()) {
        return Optional.empty();
      }
      List<ApprovalRequirement> requirements = new ArrayList<>(node.size());
      for (JsonNode entry : node) {
        if (!entry.isObject() || !entry.hasNonNull("id") || !entry.hasNonNull("reason")) {
          return Optional.empty();
        }
        List<ApprovalRole> admits = new ArrayList<>();
        entry
            .path("admits")
            .forEach(
                word -> {
                  ApprovalRole role = ApprovalRole.parse(word.asText(), Function.identity());
                  if (role != null) {
                    admits.add(role);
                  }
                });
        if (admits.isEmpty()) {
          return Optional.empty();
        }
        requirements.add(
            new ApprovalRequirement(
                entry.get("id").asText(),
                entry.get("reason").asText(),
                admits,
                entry.path("excludesAuthors").asBoolean(false),
                entry.hasNonNull("detail") ? entry.get("detail").asText() : null));
      }
      return Optional.of(List.copyOf(requirements));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
