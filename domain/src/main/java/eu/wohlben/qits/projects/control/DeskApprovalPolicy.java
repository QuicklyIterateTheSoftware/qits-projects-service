package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRole;
import eu.wohlben.qits.projects.control.approval.ApprovalStanding;
import eu.wohlben.qits.projects.control.approval.Approver;
import eu.wohlben.qits.projects.control.approval.AutomationContentSource;
import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import eu.wohlben.qits.projects.control.approval.ConfigDirectorySource;
import eu.wohlben.qits.projects.control.approval.ContractChangeSource;
import eu.wohlben.qits.projects.control.approval.NoChangesSource;
import eu.wohlben.qits.projects.control.gate.ApprovalGate;
import eu.wohlben.qits.projects.control.gate.ReleaseGate;
import eu.wohlben.qits.projects.control.gate.ReleaseGateEvaluator;
import eu.wohlben.qits.projects.dto.ReleaseQualityGateDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>When the delivery desk may approve</b> (epic qits-1440). {@link ApprovalPolicy} admits {@code
 * desk} to the requirements the project's {@code front_desk.delivery.approves} lists; this class is
 * the rest of the owner's rule, asked by the approve door only when the caller approves <em>as the
 * desk</em> ({@link #ruleFor}). Every refusal is a 403 naming the rule it enforces.
 *
 * <ol>
 *   <li><b>Its own project only</b> — the request's project is the desk token's {@code project}.
 *   <li><b>Every gate before approval has passed</b> — each gate the request reports that is
 *       ordered before {@link ApprovalGate} ({@code QA_PUBLISH}, order 300): the automations and
 *       CI today. A red, pending or unknown one refuses.
 *   <li><b>{@value #MANUAL_REVIEW_RULE}</b> — for {@code manual-review}: every branch source other
 *       than the repository's main is {@code maintenance/*} or a work-item branch ({@link
 *       BranchWorkItems}) of the request's project, and every work item so named is VERIFYING,
 *       VERIFIED or DONE — VERIFYING since qits-1560, so a verify-phase request (a probe, its
 *       restore, a fix found in verification) can ship. On the wrapper, IMPLEMENTING or later
 *       since qits-1661: an item's agent files wrapper requests as steps of its work and waits for
 *       them, so waiting for VERIFIED deadlocks. It governs the wrapper and every {@code
 *       manual-review: true} repository, such as {@code qits-agent-config}. It also answers a
 *       desk-admitted {@code no-changes} (qits-1568), which adds one more condition: at least one
 *       branch source other than main and {@code maintenance/*} names a work item of the request's
 *       project. A fold that adds nothing is cleared only for the work it carries — a wrapper
 *       request for a VERIFIED item whose work lives in submodules — and an empty release with no
 *       work to resolve (main-only, or only {@code maintenance/*}) stays the owner's.
 *   <li><b>{@value #ARTIFACT_DECLARATIONS_RULE}</b> — for {@code artifact-declarations}: the two
 *       rules above and nothing more.
 *   <li><b>{@value #MEMBER_CI_GREEN_RULE}</b> (qits-1517) — for the {@code automation:*} and {@code
 *       contract:*} requirements the desk is admitted to, and (qits-1560) a {@code
 *       config-directory} one (a member request of a dispatched item, held while its work is in
 *       flight): the request's repository is not the project's wrapper
 *       ({@link RepositoryArchetype#PROJECT}), and every branch source other than main is {@code
 *       maintenance/*} or a work-item branch of the request's project whose item is IMPLEMENTING,
 *       IMPLEMENTED, VERIFYING, VERIFIED or DONE.
 * </ol>
 *
 * <p>The rule is picked by {@link #ruleFor} in that order — {@code manual-review} first, then a
 * desk-admitted {@code no-changes} (also {@value #MANUAL_REVIEW_RULE}), then {@code
 * artifact-declarations}, then {@code member-ci-green} — so one approval is held to the strictest
 * rule any requirement it satisfies carries, and {@code no-changes} never falls to the looser
 * {@value #MEMBER_CI_GREEN_RULE}. On the wrapper {@code manual-review} is always present, so a
 * wrapper fold that also changes {@code .config/qits/} or adds nothing is approved under {@value
 * #MANUAL_REVIEW_RULE}, one approval satisfying every requirement it qualifies for.
 *
 * <p><b>A desk approval carries its rule</b>: the recorded note is {@code delivery desk, rule <rule
 * id>: <the caller's note, or ->} ({@link #note}), so the trail says which of the owner's rules let
 * it through. <b>The desk never declines</b> ({@link #refuseDecline}): a caller whose only
 * qualifying role is {@code desk} asks the owner on the item's thread instead.
 *
 * <p>Domain-only: the gate answers are the request's own read, handed in by {@link
 * ReleaseRequests}, and the work items come through the {@link BranchWorkItems} port.
 */
@ApplicationScoped
public class DeskApprovalPolicy {

  private static final Logger LOG = Logger.getLogger(DeskApprovalPolicy.class);

  /** The rule a desk approval of {@code manual-review} is made under. */
  public static final String MANUAL_REVIEW_RULE = "manual-review-verified";

  /** The rule a desk approval of {@code artifact-declarations} is made under. */
  public static final String ARTIFACT_DECLARATIONS_RULE = "artifact-declarations";

  /**
   * The rule a desk approval of an {@code automation:*}, {@code contract:*} (qits-1517) or {@code
   * config-directory} (qits-1560) requirement is made under: a member request whose CI is green, of
   * work in flight.
   */
  public static final String MEMBER_CI_GREEN_RULE = "member-ci-green";

  /** The requirement {@link #MANUAL_REVIEW_RULE} answers. */
  static final String MANUAL_REVIEW = "manual-review";

  /** The requirement {@link #MANUAL_REVIEW_RULE} also answers, with a work condition (qits-1568). */
  static final String NO_CHANGES = NoChangesSource.ID;

  /** The requirement {@link #ARTIFACT_DECLARATIONS_RULE} answers. */
  static final String ARTIFACT_DECLARATIONS = "artifact-declarations";

  /** The decline door's refusal for the desk. */
  public static final String DOES_NOT_DECLINE =
      "The delivery desk does not decline; it asks the owner on the item's thread.";

  /** The branches a maintenance bump pushes, which name no work item and need none. */
  static final String MAINTENANCE_PREFIX = "maintenance/";

  /**
   * The statuses a named work item must stand at under {@link #MANUAL_REVIEW_RULE} off the wrapper
   * (on the wrapper, {@link #IN_FLIGHT} since qits-1661): VERIFYING too
   * since qits-1560, because a verify probe ships from an item under verification. The rule keeps
   * its id, which recorded notes name.
   */
  static final Set<String> SHIPPABLE = Set.of("VERIFYING", "VERIFIED", "DONE");

  /** The statuses a work item of a member request may stand at under {@link #MEMBER_CI_GREEN_RULE}. */
  static final List<String> IN_FLIGHT =
      List.of("IMPLEMENTING", "IMPLEMENTED", "VERIFYING", "VERIFIED", "DONE");

  static final String PASSED = "PASSED";

  @Inject ReleaseGateEvaluator gateEvaluator;

  @Inject Instance<BranchWorkItems> workItems;

  /**
   * The rule {@code approver} approves under, or empty where it does not approve as the desk: it
   * holds a desk role and one of the {@code concerned} requirements admits {@link
   * ApprovalRole#DESK}. {@code manual-review} first, because its rule is the strictest and one
   * approval satisfies every requirement it qualifies for; then an admitted {@code no-changes}
   * (qits-1568), under the same {@value #MANUAL_REVIEW_RULE} — it must never fall to {@value
   * #MEMBER_CI_GREEN_RULE}, and {@link #requireAllowed} adds its work condition; then {@code
   * artifact-declarations}; then {@value #MEMBER_CI_GREEN_RULE} for an admitted {@code
   * automation:*}, {@code contract:*} or {@code config-directory} (qits-1560).
   *
   * <p>A desk of another project gets its rule too, so the refusal it meets names it.
   */
  public Optional<String> ruleFor(Approver approver, Collection<ApprovalRequirement> concerned) {
    if (approver == null || !approver.isDesk()) {
      return Optional.empty();
    }
    if (admitsDesk(concerned, MANUAL_REVIEW) || admitsDesk(concerned, NO_CHANGES)) {
      return Optional.of(MANUAL_REVIEW_RULE);
    }
    if (admitsDesk(concerned, ARTIFACT_DECLARATIONS)) {
      return Optional.of(ARTIFACT_DECLARATIONS_RULE);
    }
    if (concerned.stream()
        .anyMatch(
            r -> isMemberRequirement(r.id()) && r.admits().contains(ApprovalRole.DESK))) {
      return Optional.of(MEMBER_CI_GREEN_RULE);
    }
    return Optional.empty();
  }

  private static boolean admitsDesk(Collection<ApprovalRequirement> concerned, String id) {
    return concerned.stream()
        .anyMatch(r -> r.id().equals(id) && r.admits().contains(ApprovalRole.DESK));
  }

  /**
   * An {@code automation:*}, {@code contract:*} or {@code config-directory} (qits-1560)
   * requirement, which {@link #MEMBER_CI_GREEN_RULE} answers. Asked only of a requirement admitting
   * the desk, and {@link ApprovalPolicy#deskAdmissible} has already kept the fail-closed {@code
   * config-directory} readings a person's.
   */
  private static boolean isMemberRequirement(String id) {
    return id != null
        && (id.startsWith(AutomationContentSource.ID_PREFIX)
            || id.startsWith(ContractChangeSource.ID_PREFIX)
            || id.equals(ConfigDirectorySource.ID));
  }

  /**
   * Refuses, with a 403 naming {@code rule}, a desk approval the owner's rule does not allow.
   *
   * @param gates the request's quality gates as its read answered them, at {@code readSha}
   * @param readSha the fold that read was of
   * @param sources the request's branch sources
   * @param mainBranch the repository's main, which every request folds and which names no work
   * @param archetype the request's repository's archetype, or null where it has none or its row is
   *     gone; {@link #MEMBER_CI_GREEN_RULE} refuses the wrapper ({@link RepositoryArchetype#PROJECT})
   * @param concerned the requirements the approval would satisfy, as {@link #ruleFor} was asked; a
   *     desk-admitted {@code no-changes} among them refuses a request whose branches name no work
   *     item of its project (qits-1568)
   */
  public void requireAllowed(
      String rule,
      ReleaseRequest row,
      Approver approver,
      String readSha,
      List<ReleaseQualityGateDto> gates,
      List<ReleaseRequestSource> sources,
      String mainBranch,
      RepositoryArchetype archetype,
      Collection<ApprovalRequirement> concerned) {
    String deskProject = approver.deskProject();
    if (deskProject == null || !deskProject.equals(row.projectId)) {
      throw refused(
          rule,
          row,
          "the delivery desk of project "
              + deskProject
              + " approves only its own project's requests, and this one belongs to project "
              + row.projectId);
    }
    if (readSha == null || !readSha.equals(row.mergedSha)) {
      throw new DomainException(
          409,
          "Release request "
              + row.qualifiedId
              + " moved while its gates were read; re-read it and approve the fold it is on.");
    }
    if (MEMBER_CI_GREEN_RULE.equals(rule) && archetype == RepositoryArchetype.PROJECT) {
      throw refused(
          rule,
          row,
          "its repository is the project's wrapper, which the desk approves only under rule "
              + MANUAL_REVIEW_RULE
              + ", once the work is IMPLEMENTING or later");
    }
    int approvalOrder = approvalOrder();
    Map<String, Integer> orders = new HashMap<>();
    for (ReleaseGate gate : gateEvaluator.gates()) {
      orders.put(gate.kind(), gate.order());
    }
    for (ReleaseQualityGateDto gate : gates == null ? List.<ReleaseQualityGateDto>of() : gates) {
      Integer order = orders.get(gate.kind());
      if (order != null && order < approvalOrder && !PASSED.equals(gate.state())) {
        throw refused(
            rule,
            row,
            "its "
                + gate.label()
                + " gate is "
                + gate.state()
                + ", and the desk approves only once every gate before approval has passed");
      }
    }
    int named = 0;
    if (MANUAL_REVIEW_RULE.equals(rule) && archetype == RepositoryArchetype.PROJECT) {
      // qits-1661: a wrapper request filed mid-work is a delivery step; waiting for VERIFIED
      // deadlocks with the item's agent, which waits for the request.
      named =
          requireWorkAt(
              rule, row, sources, mainBranch, Set.copyOf(IN_FLIGHT), String.join(", ", IN_FLIGHT));
    } else if (MANUAL_REVIEW_RULE.equals(rule)) {
      named =
          requireWorkAt(rule, row, sources, mainBranch, SHIPPABLE, "VERIFYING, VERIFIED or DONE");
    } else if (MEMBER_CI_GREEN_RULE.equals(rule)) {
      named =
          requireWorkAt(
              rule, row, sources, mainBranch, Set.copyOf(IN_FLIGHT), String.join(", ", IN_FLIGHT));
    }
    if (named == 0 && concerned != null && admitsDesk(concerned, NO_CHANGES)) {
      throw refused(
          rule,
          row,
          "its fold adds nothing to main and no branch source other than main or maintenance/*"
              + " names a work item of this project, and an empty release with no work to resolve"
              + " is the owner's");
    }
  }

  /**
   * Every branch source but main is {@code maintenance/*} or names a work item of the request's
   * project standing at one of {@code statuses} ({@code said} is how the refusal names them).
   *
   * @return how many branch sources name such a work item
   */
  private int requireWorkAt(
      String rule,
      ReleaseRequest row,
      List<ReleaseRequestSource> sources,
      String mainBranch,
      Set<String> statuses,
      String said) {
    int named = 0;
    for (ReleaseRequestSource source : sources == null ? List.<ReleaseRequestSource>of() : sources) {
      if (source.kind != ReleaseRequestSource.Kind.BRANCH || source.name == null) {
        continue;
      }
      String branch = source.name;
      if (branch.equals(mainBranch) || branch.startsWith(MAINTENANCE_PREFIX)) {
        continue;
      }
      Optional<BranchWorkItems.WorkItem> item = workItemOf(branch);
      if (item.isEmpty() || !row.projectId.equals(item.get().projectId())) {
        throw refused(
            rule,
            row,
            "branch "
                + branch
                + " names no work item of this project, and the desk approves only maintenance/*"
                + " and ticket/, epic/ or task/<qualified id> branches");
      }
      if (!statuses.contains(item.get().status())) {
        throw refused(
            rule,
            row,
            "work item "
                + item.get().qualifiedId()
                + " (branch "
                + branch
                + ") is "
                + item.get().status()
                + ", not "
                + said);
      }
      named++;
    }
    return named;
  }

  private Optional<BranchWorkItems.WorkItem> workItemOf(String branch) {
    if (!workItems.isResolvable()) {
      return Optional.empty();
    }
    try {
      return workItems.get().workItemOf(branch);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the work item of branch %s; the desk may not approve it", branch);
      return Optional.empty();
    }
  }

  private int approvalOrder() {
    return gateEvaluator.gates().stream()
        .filter(gate -> ApprovalGate.KIND.equals(gate.kind()))
        .mapToInt(ReleaseGate::order)
        .findFirst()
        .orElse(300);
  }

  private static DomainException refused(String rule, ReleaseRequest row, String why) {
    return new DomainException(
        403,
        "The delivery desk may not approve release request "
            + row.qualifiedId
            + " under rule "
            + rule
            + ": "
            + why
            + ".");
  }

  /** The note a desk approval is recorded with. */
  public static String note(String rule, String said) {
    return "delivery desk, rule " + rule + ": " + (said == null || said.isBlank() ? "-" : said);
  }

  /**
   * Refuses a decline by a caller whose only qualifying role is {@code desk}: of the roles it holds
   * (relative to the request), {@code desk} is the only one any of {@code requirements} admits.
   */
  public void refuseDecline(
      List<ApprovalRole> held,
      Collection<ApprovalRequirement> requirements,
      Set<String> names,
      Set<String> authors) {
    List<ApprovalRole> qualifying =
        held.stream()
            .filter(
                role ->
                    requirements.stream()
                        .anyMatch(
                            requirement ->
                                ApprovalStanding.qualifies(
                                    requirement, List.of(role), names, authors)))
            .toList();
    if (!qualifying.isEmpty()
        && qualifying.stream().allMatch(role -> role.kind() == ApprovalRole.Kind.DESK)) {
      throw new DomainException(403, DOES_NOT_DECLINE);
    }
  }
}
