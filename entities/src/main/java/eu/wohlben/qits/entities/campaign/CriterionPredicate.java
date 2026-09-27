package eu.wohlben.qits.entities.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>What a {@link CampaignCriterion} waits for, one record per {@link CriterionKind}</b>, stored as
 * canonical JSON in {@code campaign_criterion.predicate}.
 *
 * <table>
 *   <caption>The four shapes</caption>
 *   <tr><th>kind</th><th>JSON</th></tr>
 *   <tr><td>ENTITY_STATUS</td><td>{@code {"entityId":"…","status":"VERIFIED"}}</td></tr>
 *   <tr><td>DEPLOYMENT_ACTIVE</td><td>{@code {"applicationName":"…","environmentName":null,"minimumVersion":null}}</td></tr>
 *   <tr><td>SCM_RELEASE</td><td>{@code {"repositoryName":"…","projectId":null,"minimumVersion":null}}</td></tr>
 *   <tr><td>APPROVAL</td><td>{@code {}}</td></tr>
 * </table>
 *
 * <p><b>Canonical</b> means one spelling per predicate: every key of the shape, in the order above,
 * optional ones written as {@code null} — so two equal predicates are equal strings, and a restated
 * criterion can be recognised as the same one.
 *
 * <p>{@link #decode} is the write door's validation, and it reports <b>every</b> violation at once
 * (the house rule): an unknown kind, a missing required field, a status not in {@link EntityStatus},
 * a blank {@code applicationName} or {@code repositoryName}, a {@code minimumVersion} that is not
 * dot-separated segments — and a field the shape does not have, since a misspelt optional field
 * silently dropped would be a floor nobody asked to remove. Whether an ENTITY_STATUS target is a
 * member of the campaign needs the campaign and is {@link CampaignService}'s to check.
 */
public sealed interface CriterionPredicate {

  CriterionKind kind();

  /** Another member reaches {@code status}. */
  record EntityStatusIs(String entityId, String status) implements CriterionPredicate {
    @Override
    public CriterionKind kind() {
      return CriterionKind.ENTITY_STATUS;
    }
  }

  /** {@code applicationName} goes live — in {@code environmentName} and at least {@code minimumVersion}, when set. */
  record DeploymentActive(String applicationName, String environmentName, String minimumVersion)
      implements CriterionPredicate {
    @Override
    public CriterionKind kind() {
      return CriterionKind.DEPLOYMENT_ACTIVE;
    }
  }

  /** {@code repositoryName} releases — in {@code projectId} and at least {@code minimumVersion}, when set. */
  record ScmRelease(String repositoryName, String projectId, String minimumVersion)
      implements CriterionPredicate {
    @Override
    public CriterionKind kind() {
      return CriterionKind.SCM_RELEASE;
    }
  }

  /** A person's yes; matches no event. */
  record Approval() implements CriterionPredicate {
    @Override
    public CriterionKind kind() {
      return CriterionKind.APPROVAL;
    }
  }

  /**
   * <b>Whether {@code observation} satisfies this predicate</b> — the catalogue's "matches when",
   * one arm per kind. An observation of another kind never matches.
   *
   * <ul>
   *   <li><b>ENTITY_STATUS</b>: the entity ids are equal, {@code status} is the target, and {@code
   *       statusBefore != status} — a re-announcement of a status already held satisfies nothing.
   *   <li><b>DEPLOYMENT_ACTIVE</b>: {@code applicationName} is equal; {@code environmentName} is
   *       equal if the criterion sets one; the version is {@link ReleaseVersions#atLeast} the
   *       floor if the criterion sets one — so a blank version never satisfies a floor.
   *   <li><b>SCM_RELEASE</b>: {@code repositoryName} is equal; {@code projectId} is equal if set;
   *       the floor as above.
   *   <li><b>APPROVAL</b>: never — it is latched only by a person.
   * </ul>
   */
  default boolean matches(Observation observation) {
    return switch (this) {
      case EntityStatusIs p ->
          observation instanceof Observation.EntityReached reached
              && Objects.equals(p.entityId(), reached.entityId())
              && Objects.equals(p.status(), reached.status())
              && !Objects.equals(reached.statusBefore(), reached.status());
      case DeploymentActive p ->
          observation instanceof Observation.DeploymentWentActive active
              && Objects.equals(p.applicationName(), active.applicationName())
              && (p.environmentName() == null
                  || Objects.equals(p.environmentName(), active.environmentName()))
              && ReleaseVersions.atLeast(active.version(), p.minimumVersion());
      case ScmRelease p ->
          observation instanceof Observation.Released released
              && Objects.equals(p.repositoryName(), released.repositoryName())
              && (p.projectId() == null || Objects.equals(p.projectId(), released.projectId()))
              && ReleaseVersions.atLeast(released.version(), p.minimumVersion());
      case Approval p -> false;
    };
  }

  /** The canonical JSON of this predicate — see the interface javadoc. */
  default String canonicalJson() {
    Map<String, Object> fields = new LinkedHashMap<>();
    switch (this) {
      case EntityStatusIs p -> {
        fields.put("entityId", p.entityId());
        fields.put("status", p.status());
      }
      case DeploymentActive p -> {
        fields.put("applicationName", p.applicationName());
        fields.put("environmentName", p.environmentName());
        fields.put("minimumVersion", p.minimumVersion());
      }
      case ScmRelease p -> {
        fields.put("repositoryName", p.repositoryName());
        fields.put("projectId", p.projectId());
        fields.put("minimumVersion", p.minimumVersion());
      }
      case Approval p -> {}
    }
    try {
      return Codec.JSON.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A predicate of strings failed to serialise", e);
    }
  }

  /** The canonical JSON as an ordered map — what a DTO carries on the wire. */
  default Map<String, Object> asMap() {
    return Codec.toMap(canonicalJson());
  }

  /**
   * A stored predicate, decoded. The row was written through {@link #decode}, so it is trusted: a
   * row that does not parse is a defect and throws.
   */
  static CriterionPredicate parse(CriterionKind kind, String json) {
    Decoded decoded = decode(kind.name(), Codec.toMap(json), "stored predicate");
    if (!decoded.valid()) {
      throw new IllegalStateException(String.join("; ", decoded.violations()));
    }
    return decoded.predicate();
  }

  /**
   * The outcome of {@link #decode}: the predicate when {@link #violations} is empty, else null.
   *
   * @param violations every problem found, each prefixed with the caller's {@code where}
   */
  record Decoded(CriterionPredicate predicate, List<String> violations) {
    public boolean valid() {
      return violations.isEmpty();
    }
  }

  /**
   * Validates and decodes one criterion as a caller stated it — {@code kind} its name, {@code
   * predicate} the JSON object — reporting every violation, each prefixed by {@code where} ({@code
   * "group 1, criterion 2"}).
   */
  static Decoded decode(String kind, Map<String, ?> predicate, String where) {
    List<String> violations = new ArrayList<>();
    Map<String, ?> fields = predicate == null ? Map.of() : predicate;
    CriterionKind parsed = Codec.kind(kind);
    if (parsed == null) {
      violations.add(
          where
              + ": "
              + (kind == null || kind.isBlank() ? "kind is required" : "unknown kind " + kind)
              + " (one of ENTITY_STATUS, DEPLOYMENT_ACTIVE, SCM_RELEASE, APPROVAL)");
      return new Decoded(null, List.copyOf(violations));
    }
    Codec.Reader read = new Codec.Reader(fields, where, violations);
    CriterionPredicate decoded =
        switch (parsed) {
          case ENTITY_STATUS -> {
            read.only(Set.of("entityId", "status"));
            String entityId = read.required("entityId");
            String status = read.required("status");
            if (status != null && Codec.status(status) == null) {
              violations.add(where + ": unknown status " + status);
            }
            yield new EntityStatusIs(entityId, status);
          }
          case DEPLOYMENT_ACTIVE -> {
            read.only(Set.of("applicationName", "environmentName", "minimumVersion"));
            yield new DeploymentActive(
                read.required("applicationName"),
                read.optional("environmentName"),
                read.version("minimumVersion"));
          }
          case SCM_RELEASE -> {
            read.only(Set.of("repositoryName", "projectId", "minimumVersion"));
            yield new ScmRelease(
                read.required("repositoryName"),
                read.optional("projectId"),
                read.version("minimumVersion"));
          }
          case APPROVAL -> {
            read.only(Set.of());
            yield new Approval();
          }
        };
    return violations.isEmpty()
        ? new Decoded(decoded, List.of())
        : new Decoded(null, List.copyOf(violations));
  }

  /** The JSON plumbing, kept off the interface's public surface. */
  final class Codec {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Dot-separated, non-empty segments with no whitespace: {@code 2026.930.1}. */
    private static final Pattern VERSION = Pattern.compile("[^.\\s]+(\\.[^.\\s]+)*");

    private Codec() {}

    static Map<String, Object> toMap(String json) {
      try {
        Map<String, Object> map =
            JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        return map == null ? new LinkedHashMap<>() : map;
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("A stored predicate is not JSON: " + json, e);
      }
    }

    static CriterionKind kind(String kind) {
      if (kind == null) {
        return null;
      }
      try {
        return CriterionKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    static EntityStatus status(String status) {
      try {
        return EntityStatus.valueOf(status);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    /** Reads one criterion's fields, collecting violations rather than throwing. */
    record Reader(Map<String, ?> fields, String where, List<String> violations) {

      void only(Set<String> allowed) {
        for (String key : fields.keySet()) {
          if (!allowed.contains(key)) {
            violations.add(
                where + ": unknown field " + key + (allowed.isEmpty() ? " (APPROVAL takes none)" : ""));
          }
        }
      }

      String required(String name) {
        Object value = fields.get(name);
        if (value == null) {
          violations.add(where + ": " + name + " is required");
          return null;
        }
        if (!(value instanceof String text)) {
          violations.add(where + ": " + name + " must be a string");
          return null;
        }
        if (text.isBlank()) {
          violations.add(where + ": " + name + " must not be blank");
          return null;
        }
        return text.trim();
      }

      /** An optional string; blank is the same as absent. */
      String optional(String name) {
        Object value = fields.get(name);
        if (value == null) {
          return null;
        }
        if (!(value instanceof String text)) {
          violations.add(where + ": " + name + " must be a string");
          return null;
        }
        return text.isBlank() ? null : text.trim();
      }

      String version(String name) {
        String version = optional(name);
        if (version != null && !VERSION.matcher(version).matches()) {
          violations.add(
              where + ": " + name + " " + version + " is not a version of dot-separated segments");
          return null;
        }
        return version;
      }
    }
  }
}
