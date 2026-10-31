package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.error.CodedRefusalException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The text edit behind the archive action (qits-1784): set or clear {@code repository.archived} in
 * a repository's {@code .config/qits/repository.yml}, <b>every other byte where it was</b>.
 *
 * <p>The file is one people write and review, with comments that explain it, so it is edited as
 * text — one line replaced, inserted or removed — and never re-serialised. What makes a textual edit
 * safe is the check after it: the original and the result are both parsed with SnakeYAML's {@link
 * SafeConstructor}, and the result must parse, must say what was asked, and must equal the original
 * in everything but {@code repository.archived}. Anything else — a flow-style {@code repository:
 * {…}}, a {@code repository} that is not a mapping, a document that does not parse — is refused with
 * a 422 ({@code ARCHIVE_EDIT_REFUSED}) and nothing is written: a person edits that file by hand.
 *
 * <p>Pure and framework-free; {@code RepositoryArchive} does the reading and the writing.
 */
public final class RepositoryArchivedEdit {

  /** The file the archived state lives in — the same one {@link QitsConfigParser} reads. */
  public static final String PATH = QitsConfigParser.CONFIG_PATH;

  /** The refusal's code, answered as {@code error} beside the message. */
  public static final String REFUSED = "ARCHIVE_EDIT_REFUSED";

  /** What an archive writes when the repository has no configuration file at all. */
  static final String CREATED =
      "# This repository's qits configuration. `repository.archived` was written by the"
          + " platform's archive action (qits-1784).\n"
          + "version: 1\n"
          + "repository:\n"
          + "  archived: true\n";

  private static final Pattern SECTION = Pattern.compile("^repository:[ \\t]*(#.*)?$");

  private static final Pattern SECTION_INLINE = Pattern.compile("^repository:[ \\t]*[^ \\t#].*$");

  private static final Pattern ARCHIVED =
      Pattern.compile("^([ \\t]+archived[ \\t]*:)([ \\t]*)([^#]*?)([ \\t]+#.*)?$");

  private RepositoryArchivedEdit() {}

  /**
   * What the edit came to.
   *
   * @param changed false when the file already says what was asked; {@code content} is then the
   *     original (null for an absent file) and nothing should be written
   * @param content the file's whole new text
   */
  public record Edit(boolean changed, String content) {}

  /** Whether {@code content} (null for an absent file) says {@code repository.archived: true}. */
  public static boolean archived(String content) {
    if (content == null) {
      return false;
    }
    return Boolean.TRUE.equals(archivedValue(repositoryOf(parse(content, "the file"))));
  }

  /**
   * The edit that makes {@code content} say {@code archived} — {@code true} for archive, absent for
   * unarchive.
   *
   * @param content the file as it stands, or null when there is none
   * @throws CodedRefusalException 422 when the file cannot be edited safely
   */
  public static Edit apply(String content, boolean archive) {
    if (content == null) {
      return archive ? new Edit(true, CREATED) : new Edit(false, null);
    }
    Map<String, Object> before = parse(content, "the file");
    Object repository = repositoryOf(before);
    Object current = archivedValue(repository);
    boolean isArchived = Boolean.TRUE.equals(current);
    if (archive == isArchived) {
      return new Edit(false, content);
    }
    String edited = archive ? archive(content) : unarchive(content);
    verify(before, edited, archive);
    return new Edit(true, edited);
  }

  // ---- the text --------------------------------------------------------------------------------

  private static String archive(String content) {
    List<String> lines = lines(content);
    int section = sectionLine(lines);
    if (section < 0) {
      // No section at all: append one. A file without a final newline gets one first.
      return content + "\nrepository:\n  archived: true\n";
    }
    int end = sectionEnd(lines, section);
    String indent = childIndent(lines, section, end);
    int existing = archivedLine(lines, section, end, indent);
    if (existing >= 0) {
      String line = lines.get(existing);
      Matcher archived = ARCHIVED.matcher(strip(line));
      archived.matches();
      String comment = archived.group(4) == null ? "" : archived.group(4);
      lines.set(existing, archived.group(1) + " true" + comment + newlineOf(line));
      return String.join("", lines);
    }
    String newline = newlineOf(lines.get(section));
    if (newline.isEmpty()) {
      // `repository:` is the last line, with no newline after it.
      lines.set(section, lines.get(section) + "\n");
      newline = "\n";
    }
    lines.add(section + 1, (indent == null ? "  " : indent) + "archived: true" + newline);
    return String.join("", lines);
  }

  private static String unarchive(String content) {
    List<String> lines = lines(content);
    int section = sectionLine(lines);
    if (section < 0) {
      throw refused("there is no block-style `repository:` section to remove `archived` from");
    }
    int end = sectionEnd(lines, section);
    int existing = archivedLine(lines, section, end, childIndent(lines, section, end));
    if (existing < 0) {
      throw refused("`repository.archived` is not a line of the `repository:` section");
    }
    lines.remove(existing);
    return String.join("", lines);
  }

  /**
   * The indentation of the section's first child — the one every direct child shares — or null for
   * a section with no children.
   */
  private static String childIndent(List<String> lines, int section, int end) {
    for (int i = section + 1; i < end; i++) {
      String body = strip(lines.get(i));
      if (!blankOrComment(body)) {
        return leadingWhitespace(body);
      }
    }
    return null;
  }

  /** The {@code archived:} line among the section's direct children, or -1. */
  private static int archivedLine(List<String> lines, int section, int end, String indent) {
    if (indent == null) {
      return -1;
    }
    for (int i = section + 1; i < end; i++) {
      String body = strip(lines.get(i));
      if (ARCHIVED.matcher(body).matches() && leadingWhitespace(body).equals(indent)) {
        return i;
      }
    }
    return -1;
  }

  /** The index of the top-level block-style {@code repository:} line, or -1 where there is none. */
  private static int sectionLine(List<String> lines) {
    int found = -1;
    for (int i = 0; i < lines.size(); i++) {
      String line = strip(lines.get(i));
      if (SECTION.matcher(line).matches()) {
        if (found >= 0) {
          throw refused("`repository:` appears more than once");
        }
        found = i;
      } else if (SECTION_INLINE.matcher(line).matches()) {
        throw refused(
            "`repository:` is not a block-style mapping (a flow-style `{…}` or a scalar), which"
                + " this action does not rewrite");
      }
    }
    return found;
  }

  /** The first line after {@code section} that starts the next top-level entry, or the end. */
  private static int sectionEnd(List<String> lines, int section) {
    for (int i = section + 1; i < lines.size(); i++) {
      String line = strip(lines.get(i));
      if (!blankOrComment(line) && !Character.isWhitespace(line.charAt(0))) {
        return i;
      }
    }
    return lines.size();
  }

  /** Lines with their terminators kept, so joining them gives the original back byte for byte. */
  private static List<String> lines(String content) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        lines.add(content.substring(start, i + 1));
        start = i + 1;
      }
    }
    if (start < content.length()) {
      lines.add(content.substring(start));
    }
    return lines;
  }

  private static String newlineOf(String line) {
    if (line.endsWith("\r\n")) {
      return "\r\n";
    }
    return line.endsWith("\n") ? "\n" : "";
  }

  private static String strip(String line) {
    return line.substring(0, line.length() - newlineOf(line).length());
  }

  private static boolean blankOrComment(String line) {
    String trimmed = line.strip();
    return trimmed.isEmpty() || trimmed.startsWith("#");
  }

  private static String leadingWhitespace(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }

  // ---- the check -------------------------------------------------------------------------------

  private static void verify(Map<String, Object> before, String edited, boolean archive) {
    Map<String, Object> after = parse(edited, "the edited file");
    Object archived = archivedValue(repositoryOf(after));
    if (archive ? !Boolean.TRUE.equals(archived) : archived != null) {
      throw refused("the edited file does not say `repository.archived` as asked");
    }
    if (!withoutArchived(before).equals(withoutArchived(after))) {
      throw refused("the edit would change more than `repository.archived`");
    }
  }

  /**
   * The document with {@code repository.archived} taken out, and a {@code repository} left empty or
   * null by that taken out too — so appending a section, and emptying one, compare equal.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> withoutArchived(Map<String, Object> document) {
    Map<String, Object> copy = new LinkedHashMap<>(document);
    Object repository = copy.get("repository");
    if (repository instanceof Map<?, ?> section) {
      Map<String, Object> rest = new LinkedHashMap<>((Map<String, Object>) section);
      rest.remove("archived");
      if (rest.isEmpty()) {
        copy.remove("repository");
      } else {
        copy.put("repository", rest);
      }
    } else if (copy.containsKey("repository") && repository == null) {
      copy.remove("repository");
    }
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String content, String what) {
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
    } catch (RuntimeException e) {
      throw refused(what + " is not valid YAML: " + firstLine(e.getMessage()));
    }
    if (root == null) {
      return new LinkedHashMap<>();
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw refused(what + " is not a YAML mapping");
    }
    return (Map<String, Object>) map;
  }

  /** The {@code repository} value: a mapping, null (absent or empty), or a refusal. */
  private static Object repositoryOf(Map<String, Object> document) {
    Object repository = document.get("repository");
    if (repository != null && !(repository instanceof Map<?, ?>)) {
      throw refused("`repository` is not a mapping");
    }
    return repository;
  }

  private static Object archivedValue(Object repository) {
    return repository instanceof Map<?, ?> section ? section.get("archived") : null;
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }

  private static CodedRefusalException refused(String why) {
    return new CodedRefusalException(
        422,
        REFUSED,
        PATH + " cannot be edited safely: " + why + ". Nothing was written; edit the file by hand.");
  }
}
