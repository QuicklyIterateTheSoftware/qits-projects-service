package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>Whether a fold's {@code .config/qits/} change only declares what a release publishes</b> — the
 * rule {@link ArtifactDeclarationSource} and {@link ConfigDirectorySource} split the directory by, in
 * one place so the two can never both answer or both stay silent.
 *
 * <p>It holds when the only entry under the directory is {@value #PATH}, added or modified (never
 * deleted or renamed), and the file before and after parse as YAML mappings that differ only in:
 *
 * <ul>
 *   <li><b>comments and blank lines</b> — the YAML reading does not see them at all;
 *   <li><b>{@code artifacts:}</b> — a list whose entries are mappings; every entry the fold adds or
 *       changes names a {@code type} of {@code maven}, {@code npm}, {@code oci} or {@code docs};
 *   <li><b>{@code contracts:}</b> — a mapping of {@code application:}, {@code golden-masters:} and
 *       {@code pacts:}, whose entries are {@code <provider>: { packages: [...] }}; any other key there
 *       must be left exactly as it was.
 * </ul>
 *
 * <p>Every other top-level key must be equal before and after — so a slot ({@code release-request:},
 * {@code release:}), the {@code archetype:} or {@code userflows:} changing is never a declaration.
 * A file that will not parse is not one either: it is the directory's ordinary person requirement,
 * which is not a failure to read but a reading.
 */
final class ReleaseDeclarations {

  /** The one file a declaration lives in. */
  static final String PATH = ConfigDirectorySource.DIRECTORY + "release.yml";

  static final String ARTIFACTS = "artifacts";
  static final String CONTRACTS = "contracts";
  static final String PACTS = "pacts";
  static final String PACKAGES = "packages";

  /** The artifact types a declaration may add. */
  static final Set<String> DECLARABLE_TYPES = Set.of("maven", "npm", "oci", "docs");

  /** The keys a {@code contracts:} block may change. */
  static final Set<String> CONTRACT_KEYS = Set.of("application", "golden-masters", PACTS);

  private ReleaseDeclarations() {}

  /**
   * Whether {@code touched} — the fold's entries under the directory — is a declaration only.
   *
   * @throws FoldUnreadable when a side of the file could not be read
   */
  static boolean declarationOnly(ApprovalSubject subject, List<CommitFileChangeDto> touched) {
    if (touched.size() != 1) {
      return false;
    }
    CommitFileChangeDto file = touched.get(0);
    if (!PATH.equals(file.path())
        || (file.oldPath() != null && !PATH.equals(file.oldPath()))
        || !("MODIFIED".equals(file.changeType()) || "ADDED".equals(file.changeType()))) {
      return false;
    }
    String before = "ADDED".equals(file.changeType()) ? null : subject.blob(file.oldSha());
    String after = subject.blob(file.newSha());
    if (after == null) {
      return false;
    }
    return declarationOnly(before, after);
  }

  /** The rule over the file's two contents; {@code before} null for a file the fold adds. */
  static boolean declarationOnly(String before, String after) {
    Map<?, ?> old = mapping(before);
    Map<?, ?> now = mapping(after);
    if (old == null || now == null) {
      return false;
    }
    Set<Object> keys = new java.util.HashSet<>(old.keySet());
    keys.addAll(now.keySet());
    for (Object key : keys) {
      if (ARTIFACTS.equals(key) || CONTRACTS.equals(key)) {
        continue;
      }
      if (!Objects.equals(old.get(key), now.get(key))) {
        return false;
      }
    }
    return artifactsDeclareOnly(old.get(ARTIFACTS), now.get(ARTIFACTS))
        && contractsDeclareOnly(old.get(CONTRACTS), now.get(CONTRACTS));
  }

  private static boolean artifactsDeclareOnly(Object before, Object after) {
    if (Objects.equals(before, after) || after == null) {
      return true;
    }
    if (!(after instanceof List<?> entries)) {
      return false;
    }
    List<?> old = before instanceof List<?> list ? list : List.of();
    for (Object entry : entries) {
      if (old.contains(entry)) {
        continue;
      }
      if (!(entry instanceof Map<?, ?> artifact)
          || !(artifact.get("type") instanceof String type)
          || !DECLARABLE_TYPES.contains(type)) {
        return false;
      }
    }
    return true;
  }

  private static boolean contractsDeclareOnly(Object before, Object after) {
    if (Objects.equals(before, after)) {
      return true;
    }
    Map<?, ?> old = before instanceof Map<?, ?> map ? map : Map.of();
    Map<?, ?> now;
    if (after == null) {
      now = Map.of();
    } else if (after instanceof Map<?, ?> map) {
      now = map;
    } else {
      return false;
    }
    Set<Object> keys = new java.util.HashSet<>(old.keySet());
    keys.addAll(now.keySet());
    for (Object key : keys) {
      if (Objects.equals(old.get(key), now.get(key))) {
        continue;
      }
      if (!(key instanceof String name) || !CONTRACT_KEYS.contains(name)) {
        return false;
      }
      if (PACTS.equals(name) && !pactsDeclareOnly(old.get(key), now.get(key))) {
        return false;
      }
    }
    return true;
  }

  private static boolean pactsDeclareOnly(Object before, Object after) {
    if (after == null) {
      return true;
    }
    if (!(after instanceof Map<?, ?> now)) {
      return false;
    }
    Map<?, ?> old = before instanceof Map<?, ?> map ? map : Map.of();
    for (Map.Entry<?, ?> pact : now.entrySet()) {
      if (Objects.equals(old.get(pact.getKey()), pact.getValue())) {
        continue;
      }
      if (!(pact.getValue() instanceof Map<?, ?> declared)
          || !declared.keySet().equals(Set.of(PACKAGES))
          || !(declared.get(PACKAGES) instanceof List<?>)) {
        return false;
      }
    }
    return true;
  }

  /** The file as a mapping: empty for no file or an empty one, null where it is not a mapping. */
  private static Map<?, ?> mapping(String content) {
    if (content == null || content.isBlank()) {
      return Map.of();
    }
    try {
      Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
      if (root == null) {
        return Map.of();
      }
      return root instanceof Map<?, ?> map ? map : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
