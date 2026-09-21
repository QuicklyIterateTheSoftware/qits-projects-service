package eu.wohlben.qits.projects.control;

/**
 * One {@code .gitmodules} path, read under the project's single grammar.
 *
 * <p>The wrapper is the project's configuration and its paths are what that configuration says, so
 * there is exactly one reading of a path and this is it — the reconcile derives a row's facts from
 * it, and the create flow places a new entry with it.
 *
 * <p>The grammar is {@code components/<component>/<name>}: three segments, the first of them the
 * literal {@code components}, the second naming the technical component the entry belongs to, the
 * third the repository's addressable name. <b>The path says which component, never what kind</b> —
 * the kind is read off the name's role suffix ({@link
 * eu.wohlben.qits.projects.entity.RepositoryArchetype#fromRepositoryName}), which is why nothing
 * here derives an archetype any more.
 *
 * <p>Anything else is <b>not a path</b> and comes back null: {@code services/x} and {@code vendor/x}
 * (the retired archetype layout and anything shaped like it), {@code components/orphan} with nothing
 * under it, {@code components/a/b/c}, a single segment, a trailing slash, blank, null. There is no
 * second grammar to fall back to, and guessing what one of those means would be inventing taxonomy;
 * the reconcile reports such an entry as a skip naming the grammar it failed to match.
 *
 * <p>Pure and static, like {@link WrapperGitmodules}: no CDI, no git, no IO.
 *
 * @param path the whole path, trimmed
 * @param component the component the second segment names
 * @param name the last segment — the repository's addressable name, what {@code ../<name>.git}
 *     resolves to
 */
public record WrapperPath(String path, String component, String name) {

  /** The first segment every wrapper path starts with. */
  public static final String COMPONENTS_DIRECTORY = "components";

  /**
   * {@code path} read as {@code components/<component>/<name>}, or null when it is not one — see
   * the class doc for the list of things that are not.
   */
  public static WrapperPath parse(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String trimmed = path.trim();
    // -1 keeps the trailing empty segment a trailing slash produces, so "components/x/" is a
    // three-segment split with a blank name and is refused below rather than read as "components/x".
    String[] segments = trimmed.split("/", -1);
    if (segments.length != 3
        || !COMPONENTS_DIRECTORY.equals(segments[0])
        || segments[1].isBlank()
        || segments[2].isBlank()) {
      return null;
    }
    return new WrapperPath(trimmed, segments[1], segments[2]);
  }

  /** The mount directory a component entry takes: {@code components/<component>}. */
  public static String componentDirectory(String component) {
    return COMPONENTS_DIRECTORY + "/" + component;
  }
}
