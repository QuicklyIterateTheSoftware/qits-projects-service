package eu.wohlben.qits.projects.control.approval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Who is deciding, and which approval roles this service proved they hold</b> (epic qits-1038).
 * Built by the service's {@code ApproverCheck} from a credential it verified itself — never from an
 * asserted header — and handed to the domain, which decides eligibility.
 *
 * @param name the name recorded on the decision: a person's verified name, an agent's principal —
 *     the name its token gives every other door, which is what {@code ReleaseRequestSource.addedBy}
 *     records when the agent adds a branch
 * @param held the roles held, with absolute project ids ({@code agent} of the token's {@code project}
 *     claim); read relative to a request through {@link ApprovalRole#relativeTo}
 * @param aliases other names the same caller goes by (an agent's {@code sub} where it differs from
 *     its principal) — matched against a request's authors too, so an author is excluded whichever
 *     of its names the request recorded
 */
public record Approver(String name, List<ApprovalRole> held, Set<String> aliases) {

  public Approver {
    held = held == null ? List.of() : List.copyOf(held);
    aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
  }

  public Approver(String name, List<ApprovalRole> held) {
    this(name, held, Set.of());
  }

  /** A verified person. */
  public static Approver person(String name) {
    return new Approver(name, List.of(ApprovalRole.PERSON));
  }

  /** An agent of {@code projectId}. */
  public static Approver agent(String name, String projectId) {
    return new Approver(name, List.of(ApprovalRole.agentOf(projectId)));
  }

  /** An agent of {@code projectId} named {@code name}, also known as {@code subject}. */
  public static Approver agent(String name, String subject, String projectId) {
    Set<String> aliases = new LinkedHashSet<>();
    if (subject != null && !subject.isBlank() && !subject.equals(name)) {
      aliases.add(subject);
    }
    return new Approver(name, List.of(ApprovalRole.agentOf(projectId)), aliases);
  }

  /**
   * The delivery desk of {@code projectId} (epic qits-1440): {@code agent} and {@code desk} of that
   * project, and never {@code person}. {@code agent} since qits-1596, which gave the desk the
   * owner's coordinator's rights: it satisfies (and declines) what an agent of its project may, and
   * as {@code desk} the requirements the project handed it in {@code front_desk.delivery.approves},
   * held to the owner's rule ({@code DeskApprovalPolicy}). It decides only its own project's
   * requests ({@code ReleaseRequests}), so its {@code agent} never reaches another project's
   * {@code agent@<slug>} requirement. Named by its token's {@code sub}, also known as {@code
   * principal} where the two differ.
   */
  public static Approver desk(String subject, String principal, String projectId) {
    Set<String> aliases = new LinkedHashSet<>();
    if (principal != null && !principal.isBlank() && !principal.equals(subject)) {
      aliases.add(principal);
    }
    return new Approver(
        subject,
        List.of(ApprovalRole.agentOf(projectId), ApprovalRole.deskOf(projectId)),
        aliases);
  }

  /** The project whose delivery desk this approver is, or null where it holds no desk role. */
  public String deskProject() {
    return held.stream()
        .filter(role -> role.kind() == ApprovalRole.Kind.DESK)
        .map(ApprovalRole::projectId)
        .findFirst()
        .orElse(null);
  }

  /** Whether this approver holds a desk role of any project. */
  public boolean isDesk() {
    return held.stream().anyMatch(role -> role.kind() == ApprovalRole.Kind.DESK);
  }

  /** Every name this approver goes by: {@link #name} first, then the aliases. */
  public Set<String> names() {
    Set<String> names = new LinkedHashSet<>();
    if (name != null && !name.isBlank()) {
      names.add(name);
    }
    names.addAll(aliases);
    return names;
  }

  /** The held roles as the request of {@code requestProjectId} reads them. */
  public List<ApprovalRole> heldRelativeTo(String requestProjectId) {
    return held.stream().map(role -> role.relativeTo(requestProjectId)).distinct().toList();
  }
}
