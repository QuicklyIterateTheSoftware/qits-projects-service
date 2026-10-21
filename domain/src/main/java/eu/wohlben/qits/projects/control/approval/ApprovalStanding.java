package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>Where a fold's approval requirements stand</b>, given every decision made at that fold (epic
 * qits-1038). Pure computation over rows already read: the gate, the read and the doors all match
 * through this one class, so they can never disagree about what an approval satisfied.
 *
 * <ul>
 *   <li><b>Each approver's newest decision at the fold counts</b>, and only that one — a change of
 *       mind is a later row.
 *   <li><b>Satisfied</b>: some approver's newest row is APPROVED, its recorded roles include one the
 *       requirement admits, and — where the requirement excludes authors — the approver is not one
 *       of the request's authors. One approval satisfies every requirement it qualifies for.
 *   <li><b>Declined</b>: some approver's newest row is DECLINED and that approver qualified for at
 *       least one requirement. Lifted by a re-fold, or by the same approver approving later at the
 *       fold. (The epic's judgement call: with agents approving, "the newest row of anyone wins"
 *       would let an agent overturn a person's no.)
 * </ul>
 */
public final class ApprovalStanding {

  /**
   * One requirement and what satisfied it.
   *
   * @param satisfiedBy the newest qualifying approval, or null while the requirement is open
   * @param roles the roles that approval recorded, relative to the request; empty while open
   * @param declinedBy the newest decline by an approver who qualified for this requirement, or null
   */
  public record Entry(
      ApprovalRequirement requirement,
      ReleaseRequestApproval satisfiedBy,
      List<ApprovalRole> roles,
      ReleaseRequestApproval declinedBy) {

    public boolean satisfied() {
      return satisfiedBy != null;
    }

    /** OPEN, SATISFIED or DECLINED — a qualifying decline wins over an approval beside it. */
    public String state() {
      return declinedBy != null ? "DECLINED" : satisfied() ? "SATISFIED" : "OPEN";
    }
  }

  private final List<Entry> entries;
  private final ReleaseRequestApproval decline;
  private final ReleaseRequestApproval newest;

  private ApprovalStanding(
      List<Entry> entries, ReleaseRequestApproval decline, ReleaseRequestApproval newest) {
    this.entries = List.copyOf(entries);
    this.decline = decline;
    this.newest = newest;
  }

  /**
   * @param requirements the fold's requirements
   * @param atFold every decision at the request's current fold, any order
   * @param authors the request's authors — the principals that added its branch sources
   * @param rolesOf reads a row's recorded roles, relative to the request
   */
  public static ApprovalStanding of(
      List<ApprovalRequirement> requirements,
      Collection<ReleaseRequestApproval> atFold,
      Set<String> authors,
      Function<ReleaseRequestApproval, List<ApprovalRole>> rolesOf) {
    Map<String, ReleaseRequestApproval> newestByApprover = new LinkedHashMap<>();
    ReleaseRequestApproval newest = null;
    List<ReleaseRequestApproval> ordered = new ArrayList<>(atFold == null ? List.of() : atFold);
    ordered.sort((a, b) -> b.decidedAt.compareTo(a.decidedAt));
    for (ReleaseRequestApproval row : ordered) {
      if (newest == null) {
        newest = row;
      }
      newestByApprover.putIfAbsent(row.actor, row);
    }
    List<Entry> entries = new ArrayList<>();
    ReleaseRequestApproval decline = null;
    for (ApprovalRequirement requirement : requirements) {
      ReleaseRequestApproval satisfiedBy = null;
      List<ApprovalRole> satisfiedRoles = List.of();
      ReleaseRequestApproval declinedBy = null;
      for (ReleaseRequestApproval row : newestByApprover.values()) {
        List<ApprovalRole> roles = rolesOf.apply(row);
        if (!qualifies(requirement, roles, row.actor, authors)) {
          continue;
        }
        if (row.decision == ReleaseRequestApproval.Decision.APPROVED && satisfiedBy == null) {
          satisfiedBy = row;
          satisfiedRoles = roles;
        } else if (row.decision == ReleaseRequestApproval.Decision.DECLINED && declinedBy == null) {
          declinedBy = row;
        }
      }
      if (declinedBy != null
          && (decline == null || declinedBy.decidedAt.isAfter(decline.decidedAt))) {
        decline = declinedBy;
      }
      entries.add(new Entry(requirement, satisfiedBy, satisfiedRoles, declinedBy));
    }
    return new ApprovalStanding(entries, decline, newest);
  }

  /**
   * Whether an approver holding {@code held} (relative to the request) and named {@code name} may
   * satisfy {@code requirement}: it admits one of the roles, and the approver is not an excluded
   * author.
   */
  public static boolean qualifies(
      ApprovalRequirement requirement,
      Collection<ApprovalRole> held,
      String name,
      Set<String> authors) {
    return qualifies(requirement, held, name == null ? Set.of() : Set.of(name), authors);
  }

  /**
   * The same, for an approver known by several names (an agent's principal and its {@code sub}):
   * an author under any of them is excluded.
   */
  public static boolean qualifies(
      ApprovalRequirement requirement,
      Collection<ApprovalRole> held,
      Collection<String> names,
      Set<String> authors) {
    if (requirement.excludesAuthors()
        && authors != null
        && names != null
        && names.stream().anyMatch(authors::contains)) {
      return false;
    }
    return requirement.admits().stream().anyMatch(held::contains);
  }

  public List<Entry> entries() {
    return entries;
  }

  /** The requirements nobody has satisfied yet. */
  public List<ApprovalRequirement> open() {
    return entries.stream().filter(entry -> !entry.satisfied()).map(Entry::requirement).toList();
  }

  /** The decline that holds the fold, or null. */
  public ReleaseRequestApproval decline() {
    return decline;
  }

  /** The newest decision at the fold, by anybody — what the deprecated approver fields show. */
  public ReleaseRequestApproval newest() {
    return newest;
  }

  /** NOT_REQUIRED, DECLINED, APPROVED (every requirement satisfied) or WAITING. */
  public ReleaseRequest.ApprovalState state() {
    if (entries.isEmpty()) {
      return ReleaseRequest.ApprovalState.NOT_REQUIRED;
    }
    if (decline != null) {
      return ReleaseRequest.ApprovalState.DECLINED;
    }
    return entries.stream().allMatch(Entry::satisfied)
        ? ReleaseRequest.ApprovalState.APPROVED
        : ReleaseRequest.ApprovalState.WAITING;
  }
}
