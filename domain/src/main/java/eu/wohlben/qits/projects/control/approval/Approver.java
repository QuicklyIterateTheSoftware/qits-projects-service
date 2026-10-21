package eu.wohlben.qits.projects.control.approval;

import java.util.List;

/**
 * <b>Who is deciding, and which approval roles this service proved they hold</b> (epic qits-1038).
 * Built by the service's {@code ApproverCheck} from a credential it verified itself — never from an
 * asserted header — and handed to the domain, which decides eligibility.
 *
 * @param name the name recorded on the decision: a person's verified name, an agent's {@code sub}
 * @param held the roles held, with absolute project ids ({@code agent} of the token's {@code project}
 *     claim); read relative to a request through {@link ApprovalRole#relativeTo}
 */
public record Approver(String name, List<ApprovalRole> held) {

  public Approver {
    held = held == null ? List.of() : List.copyOf(held);
  }

  /** A verified person. */
  public static Approver person(String name) {
    return new Approver(name, List.of(ApprovalRole.PERSON));
  }

  /** An agent of {@code projectId}. */
  public static Approver agent(String name, String projectId) {
    return new Approver(name, List.of(ApprovalRole.agentOf(projectId)));
  }

  /** The held roles as the request of {@code requestProjectId} reads them. */
  public List<ApprovalRole> heldRelativeTo(String requestProjectId) {
    return held.stream().map(role -> role.relativeTo(requestProjectId)).distinct().toList();
  }
}
