package eu.wohlben.qits.projects.control.approval;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * <b>A git pathspec, as qits-maintenance's automation kinds write their {@code committablePaths}</b>
 * (epic qits-1038, task qits-1046) — read here so a fold's changed paths can be matched against
 * them without asking git.
 *
 * <p>Two forms are read, and nothing else:
 *
 * <ul>
 *   <li><b>{@code :(glob)<pattern>}</b> — git's glob magic: {@code *} and {@code ?} never cross a
 *       {@code /}; {@code **}{@code /} at the start or after a {@code /} matches zero or more
 *       directories; a trailing {@code /**} matches everything inside; any other {@code **} is a
 *       plain {@code *}. {@code \x} is a literal {@code x}. A pattern with no wildcard matches the
 *       path itself and everything under it, as git's does.
 *   <li><b>a plain pathspec</b> — a prefix: {@code docs/database} matches that path and everything
 *       under it, never {@code docs/database-old}. {@code .} is the whole tree.
 * </ul>
 *
 * <p><b>Everything else is {@link Unsupported}</b>, so that a caller fails closed rather than
 * guessing: any other magic word ({@code :(icase)}, {@code :(exclude)}, {@code :(attr:…)}, {@code
 * :(top)}, {@code :(literal)}, an unknown one), the short magic signs ({@code :!}, {@code :^},
 * {@code :/}), a bracket expression in a glob, and a wildcard in a plain pathspec — git reads that as
 * an fnmatch whose {@code *} crosses {@code /}, which a prefix is not.
 */
public final class GitPathspec {

  /** The one magic word read. */
  static final String GLOB = "glob";

  private final String spec;
  private final String prefix;
  private final Pattern pattern;
  private final boolean everything;

  private GitPathspec(String spec, String prefix, Pattern pattern, boolean everything) {
    this.spec = spec;
    this.prefix = prefix;
    this.pattern = pattern;
    this.everything = everything;
  }

  /** A pathspec this matcher cannot read; the caller fails closed. */
  public static final class Unsupported extends IllegalArgumentException {
    Unsupported(String spec, String why) {
      super("pathspec '" + spec + "' " + why);
    }
  }

  /**
   * {@code spec} read.
   *
   * @throws Unsupported where it uses a form this matcher does not read — see the class javadoc
   */
  public static GitPathspec parse(String spec) {
    Objects.requireNonNull(spec, "spec");
    if (spec.startsWith(":(")) {
      int close = spec.indexOf(')');
      if (close < 0) {
        throw new Unsupported(spec, "has an unterminated magic list");
      }
      String magic = spec.substring(2, close);
      String rest = spec.substring(close + 1);
      boolean glob = false;
      for (String word : magic.split(",", -1)) {
        String trimmed = word.trim();
        if (trimmed.equals(GLOB)) {
          glob = true;
        } else {
          throw new Unsupported(
              spec, trimmed.isEmpty() ? "has an empty magic word" : "uses magic '" + trimmed + "'");
        }
      }
      return glob ? glob(spec, rest) : plain(spec, rest);
    }
    if (spec.startsWith(":")) {
      String rest = spec.substring(1);
      if (rest.startsWith(":")) {
        return plain(spec, rest.substring(1));
      }
      if (!rest.isEmpty() && !pathCharacter(rest.charAt(0))) {
        throw new Unsupported(spec, "uses short magic '" + rest.charAt(0) + "'");
      }
      return plain(spec, rest);
    }
    return plain(spec, spec);
  }

  /** Whether {@code path} — a repository-relative path, no leading {@code /} — is matched. */
  public boolean matches(String path) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    if (everything) {
      return true;
    }
    if (pattern != null) {
      return pattern.matcher(path).matches();
    }
    return path.equals(prefix)
        || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
  }

  /** The pathspec as written. */
  @Override
  public String toString() {
    return spec;
  }

  private static GitPathspec plain(String spec, String path) {
    if (path.isEmpty()) {
      throw new Unsupported(spec, "names no path");
    }
    if (path.equals(".") || path.equals("./")) {
      return new GitPathspec(spec, null, null, true);
    }
    for (char c : path.toCharArray()) {
      if (c == '*' || c == '?' || c == '[' || c == '\\') {
        throw new Unsupported(spec, "has a wildcard without :(glob)");
      }
    }
    return new GitPathspec(spec, path, null, false);
  }

  private static GitPathspec glob(String spec, String pattern) {
    if (pattern.isEmpty()) {
      throw new Unsupported(spec, "names no path");
    }
    StringBuilder regex = new StringBuilder();
    boolean wildcard = false;
    StringBuilder literal = new StringBuilder();
    int i = 0;
    int length = pattern.length();
    while (i < length) {
      char c = pattern.charAt(i);
      if (c == '*') {
        wildcard = true;
        flush(regex, literal);
        boolean doubled = i + 1 < length && pattern.charAt(i + 1) == '*';
        if (doubled) {
          int end = i + 2;
          while (end < length && pattern.charAt(end) == '*') {
            end++;
          }
          boolean atStart = i == 0 || pattern.charAt(i - 1) == '/';
          if (atStart && end == length) {
            // "**" alone, or a trailing "/**": everything (inside).
            regex.append(".*");
            i = end;
            continue;
          }
          if (atStart && pattern.charAt(end) == '/') {
            // "**/": zero or more directories.
            regex.append("(?:.*/)?");
            i = end + 1;
            continue;
          }
          // Any other run of asterisks is a plain one.
          regex.append("[^/]*");
          i = end;
          continue;
        }
        regex.append("[^/]*");
        i++;
      } else if (c == '?') {
        wildcard = true;
        flush(regex, literal);
        regex.append("[^/]");
        i++;
      } else if (c == '[') {
        throw new Unsupported(spec, "uses a bracket expression");
      } else if (c == '\\') {
        if (i + 1 >= length) {
          throw new Unsupported(spec, "ends in an escape");
        }
        literal.append(pattern.charAt(i + 1));
        i += 2;
      } else {
        literal.append(c);
        i++;
      }
    }
    if (!wildcard) {
      // A glob with nothing to expand is git's literal match: the path and everything under it.
      String path = literal.toString();
      if (path.equals(".") || path.equals("./")) {
        return new GitPathspec(spec, null, null, true);
      }
      return new GitPathspec(spec, path, null, false);
    }
    flush(regex, literal);
    return new GitPathspec(spec, null, Pattern.compile(regex.toString()), false);
  }

  private static void flush(StringBuilder regex, StringBuilder literal) {
    if (!literal.isEmpty()) {
      regex.append(Pattern.quote(literal.toString()));
      literal.setLength(0);
    }
  }

  /** Whether {@code c} starts a path rather than a short magic signature. */
  private static boolean pathCharacter(char c) {
    return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '*' || c == '?';
  }
}
