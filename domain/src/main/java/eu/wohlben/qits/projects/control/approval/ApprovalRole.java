package eu.wohlben.qits.projects.control.approval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>Who may approve</b> (epic qits-1038). Rendered and parsed as {@code person}, {@code agent},
 * {@code agent@<project slug>}, {@code desk} or {@code desk@<project slug>}.
 *
 * <ul>
 *   <li>{@code person} — a person this service verified itself, holding {@code qits:admin} ({@code
 *       PersonCheck}). It is the only way a person counts.
 *   <li>{@code agent} — an agent of the <b>request's own</b> project: {@link #projectId} is null.
 *   <li>{@code agent@<slug>} — an agent of the named project, so a requirement can ask project A's
 *       agent to review a request in project B. {@link #projectId} holds the project's id; the slug
 *       is only how it is written down.
 *   <li>{@code desk} — the delivery desk of the request's own project (epic qits-1440): a {@code
 *       desk-delivery} token carrying {@code qits:desk}. Project-scoped exactly as {@code agent} is,
 *       and {@code desk@<slug>} is another project's desk. No source admits it; {@code
 *       ApprovalPolicy} adds it, in one place, to the requirements the project's {@code
 *       front_desk.delivery.approves} lists. A desk token holds {@code agent} of its project too.
 * </ul>
 *
 * <p>An approver <b>holds</b> roles and a requirement <b>admits</b> them. Both sides are compared
 * <b>relative to the request</b> ({@link #relativeTo}): an agent of the request's own project holds
 * {@code agent}, whatever project id its token names, so {@code agent} and {@code agent@<own slug>}
 * are one role and never two spellings that disagree.
 *
 * <p>Project is the only scope (the epic's judgement call): an agent's token names no repository
 * and no work item.
 *
 * @param projectId null for the request's own project; always null for {@link Kind#PERSON}
 */
public record ApprovalRole(Kind kind, String projectId) {

  /** The kinds of approver. */
  public enum Kind {
    PERSON,
    AGENT,
    /** The project's delivery desk (epic qits-1440); scoped by project like {@link #AGENT}. */
    DESK
  }

  /** A verified person. */
  public static final ApprovalRole PERSON = new ApprovalRole(Kind.PERSON, null);

  /** An agent of the request's own project. */
  public static final ApprovalRole AGENT = new ApprovalRole(Kind.AGENT, null);

  /** The delivery desk of the request's own project. */
  public static final ApprovalRole DESK = new ApprovalRole(Kind.DESK, null);

  static final String PERSON_WORD = "person";
  static final String AGENT_WORD = "agent";
  static final String DESK_WORD = "desk";
  static final char PROJECT_SEPARATOR = '@';

  public ApprovalRole {
    Objects.requireNonNull(kind, "kind");
    if (kind == Kind.PERSON || (projectId != null && projectId.isBlank())) {
      projectId = null;
    }
  }

  /** An agent of {@code projectId}, which may be the request's own project or another. */
  public static ApprovalRole agentOf(String projectId) {
    return new ApprovalRole(Kind.AGENT, projectId);
  }

  /** The delivery desk of {@code projectId}, which may be the request's own project or another. */
  public static ApprovalRole deskOf(String projectId) {
    return new ApprovalRole(Kind.DESK, projectId);
  }

  /**
   * This role as the request of {@code requestProjectId} reads it: an agent of that very project is
   * {@link #AGENT}, its desk {@link #DESK}. Every comparison is made between relative roles.
   */
  public ApprovalRole relativeTo(String requestProjectId) {
    if (kind != Kind.PERSON && projectId != null && projectId.equals(requestProjectId)) {
      return new ApprovalRole(kind, null);
    }
    return this;
  }

  /**
   * The written form: {@code person}, {@code agent}, {@code agent@<slug>}, {@code desk} or {@code
   * desk@<slug>}, where {@code slugOf} turns the project id into the slug a person reads (and
   * answers the id itself where it knows no slug).
   */
  public String render(Function<String, String> slugOf) {
    if (kind == Kind.PERSON) {
      return PERSON_WORD;
    }
    String word = kind == Kind.DESK ? DESK_WORD : AGENT_WORD;
    if (projectId == null) {
      return word;
    }
    String slug = slugOf == null ? null : slugOf.apply(projectId);
    return word + PROJECT_SEPARATOR + (slug == null || slug.isBlank() ? projectId : slug);
  }

  /** {@link #render} with the project id standing for itself. */
  @Override
  public String toString() {
    return render(Function.identity());
  }

  /**
   * The role {@code text} writes, or null where it names none. {@code projectIdOf} turns a slug
   * back into the project's id (and may answer the text itself where no project has that slug).
   */
  public static ApprovalRole parse(String text, Function<String, String> projectIdOf) {
    if (text == null) {
      return null;
    }
    String word = text.trim();
    if (word.equals(PERSON_WORD)) {
      return PERSON;
    }
    if (word.equals(AGENT_WORD)) {
      return AGENT;
    }
    if (word.equals(DESK_WORD)) {
      return DESK;
    }
    for (Kind scoped : List.of(Kind.AGENT, Kind.DESK)) {
      String prefix = (scoped == Kind.DESK ? DESK_WORD : AGENT_WORD) + PROJECT_SEPARATOR;
      if (word.startsWith(prefix) && word.length() > prefix.length()) {
        String slug = word.substring(prefix.length());
        String id = projectIdOf == null ? slug : projectIdOf.apply(slug);
        return new ApprovalRole(scoped, id == null || id.isBlank() ? slug : id);
      }
    }
    return null;
  }

  /**
   * The roles a space- or comma-separated list writes, in order and without repeats; a word naming
   * no role is skipped.
   */
  public static List<ApprovalRole> parseAll(String text, Function<String, String> projectIdOf) {
    Set<ApprovalRole> roles = new LinkedHashSet<>();
    if (text != null) {
      for (String word : text.split("[\\s,]+")) {
        ApprovalRole role = parse(word, projectIdOf);
        if (role != null) {
          roles.add(role);
        }
      }
    }
    return List.copyOf(roles);
  }

  /** Each role rendered, sorted and without repeats — the order every answer prints them in. */
  public static List<String> renderAll(
      Collection<ApprovalRole> roles, Function<String, String> slugOf) {
    List<String> words = new ArrayList<>();
    if (roles != null) {
      for (ApprovalRole role : roles) {
        String word = role.render(slugOf);
        if (!words.contains(word)) {
          words.add(word);
        }
      }
    }
    words.sort(null);
    return List.copyOf(words);
  }
}
