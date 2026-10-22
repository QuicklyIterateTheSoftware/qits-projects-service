package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.control.approval.ApprovalRequirement;
import eu.wohlben.qits.projects.control.approval.ApprovalRole;
import eu.wohlben.qits.projects.control.approval.ApprovalStanding;
import eu.wohlben.qits.projects.control.approval.Approver;
import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import eu.wohlben.qits.projects.control.gate.ApprovalGate;
import eu.wohlben.qits.projects.control.gate.ReleaseGate;
import eu.wohlben.qits.projects.control.gate.ReleaseGateEvaluator;
import eu.wohlben.qits.projects.dto.ReleaseQualityGateDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
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
 *       BranchWorkItems}) of the request's project, and every work item so named is VERIFIED or
 *       DONE.
 * </ol>
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

  /** The requirement {@link #MANUAL_REVIEW_RULE} answers. */
  static final String MANUAL_REVIEW = "manual-review";

  /** The requirement {@link #ARTIFACT_DECLARATIONS_RULE} answers. */
  static final String ARTIFACT_DECLARATIONS = "artifact-declarations";

  /** The decline door's refusal for the desk. */
  public static final String DOES_NOT_DECLINE =
      "The delivery desk does not decline; it asks the owner on the item's thread.";

  /** The branches a maintenance bump pushes, which name no work item and need none. */
  static final String MAINTENANCE_PREFIX = "maintenance/";

  /** The statuses a named work item must stand at. */
  static final Set<String> SHIPPABLE = Set.of("VERIFIED", "DONE");

  static final String PASSED = "PASSED";

  @Inject ReleaseGateEvaluator gateEvaluator;

  @Inject Instance<BranchWorkItems> workItems;

  /**
   * The rule {@code approver} approves under, or empty where it does not approve as the desk: it
   * holds a desk role and one of the {@code concerned} requirements admits {@link
   * ApprovalRole#DESK}. {@code manual-review} first, because its rule is the stricter one and one
   * approval satisfies every requirement it qualifies for.
   *
   * <p>A desk of another project gets its rule too, so the refusal it meets names it.
   */
  public Optional<String> ruleFor(Approver approver, Collection<ApprovalRequirement> concerned) {
    if (approver == null || !approver.isDesk()) {
      return Optional.empty();
    }
    if (admitsDesk(concerned, MANUAL_REVIEW)) {
      return Optional.of(MANUAL_REVIEW_RULE);
    }
    if (admitsDesk(concerned, ARTIFACT_DECLARATIONS)) {
      return Optional.of(ARTIFACT_DECLARATIONS_RULE);
    }
    return Optional.empty();
  }

  private static boolean admitsDesk(Collection<ApprovalRequirement> concerned, String id) {
    return concerned.stream()
        .anyMatch(r -> r.id().equals(id) && r.admits().contains(ApprovalRole.DESK));
  }

  /**
   * Refuses, with a 403 naming {@code rule}, a desk approval the owner's rule does not allow.
   *
   * @param gates the request's quality gates as its read answered them, at {@code readSha}
   * @param readSha the fold that read was of
   * @param sources the request's branch sources
   * @param mainBranch the repository's main, which every request folds and which names no work
   */
  public void requireAllowed(
      String rule,
      ReleaseRequest row,
      Approver approver,
      String readSha,
      List<ReleaseQualityGateDto> gates,
      List<ReleaseRequestSource> sources,
      String mainBranch) {
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
    if (MANUAL_REVIEW_RULE.equals(rule)) {
      requireVerifiedWork(rule, row, sources, mainBranch);
    }
  }

  private void requireVerifiedWork(
      String rule, ReleaseRequest row, List<ReleaseRequestSource> sources, String mainBranch) {
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
      if (!SHIPPABLE.contains(item.get().status())) {
        throw refused(
            rule,
            row,
            "work item "
                + item.get().qualifiedId()
                + " (branch "
                + branch
                + ") is "
                + item.get().status()
                + ", not VERIFIED or DONE");
      }
    }
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
