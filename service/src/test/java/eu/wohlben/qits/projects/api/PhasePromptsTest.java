package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The six phase templates — three per archetype — asserted <b>sentence by sentence</b>, plus the
 * mapping that picks between them, the one ending they share and their length budget.
 *
 * <p>This reads like an unusual thing to test and is deliberate: these words are the entire
 * specification handed to an unattended agent, they are the only correction it will ever get, and
 * every load-bearing sentence in them is there because its absence produces a specific failure that
 * nothing else in this estate would notice. A template that quietly lost "do not integrate the
 * workspace" would produce a phase that destroys its own successor — every implement run would end
 * by integrating the workspace the verify phase needs, and the door, the port, the lifecycle and the
 * suite would all still be green. So each sentence gets an assertion, with the failure it prevents
 * named in the message.
 *
 * <p>A plain unit test rather than a {@code @QuarkusTest}: the renderer is a pure function of a
 * ticket's fields, so a boot here would buy nothing and cost a minute. {@code
 * TicketFlowDispatchTest} is where the door's use of it is pinned end to end.
 */
public class PhasePromptsTest {

  private static WorkEntity ticket(EntityStatus status) {
    WorkEntity ticket = new WorkEntity();
    ticket.id = "tkt-123";
    ticket.projectId = "prj-1";
    ticket.archetype = Archetype.TICKET;
    ticket.title = "Login button is the wrong colour";
    ticket.slug = "login-button-is-the-wrong-colour";
    ticket.ticketType = TicketType.BUG;
    ticket.status = status.name();
    ticket.impetus = "The login button renders puce on the sign-in page.";
    return ticket;
  }

  /** The qualified id each fixture is rendered with — as a caller resolves it, per archetype. */
  private static String qualifiedOf(WorkEntity entity) {
    return entity.archetype == Archetype.TICKET ? "qits-123" : "qits-9";
  }

  private static Optional<String> prompt(WorkEntity entity) {
    return PhasePrompts.promptFor(entity, qualifiedOf(entity));
  }

  private static Optional<PhasePrompts.Started> started(WorkEntity entity) {
    return PhasePrompts.startedBy(entity, qualifiedOf(entity));
  }

  private static String promptFor(EntityStatus status) {
    return prompt(ticket(status))
        .orElseThrow(() -> new AssertionError(status + " rendered no prompt"));
  }

  // ---- the mapping ----------------------------------------------------------------------------

  /**
   * The status picks the phase and nothing else does. The four that render <b>nothing</b> are half
   * of this assertion and are what the dispatch door refuses on: REFINED waits for a person to
   * schedule it (qits-887), VERIFIED and DONE are past the work and DROPPED is work that will not
   * happen, so there is no phase to start and no workspace to stand up for one.
   */
  @Test
  public void eachStatusStartsItsOwnPhaseAndFourStartNone() {
    assertTrue(promptFor(EntityStatus.REPORTED).contains("Refine ticket \""));
    assertTrue(promptFor(EntityStatus.READY_FOR_DEV).contains("Implement ticket \""));
    assertTrue(promptFor(EntityStatus.IMPLEMENTED).contains("Verify ticket \""));

    assertEquals(
        Optional.empty(),
        prompt(ticket(EntityStatus.REFINED)),
        "REFINED waits for a person to schedule it: nothing runs until somebody does");
    assertEquals(Optional.empty(), PhasePrompts.nextPhase(epic(EntityStatus.REFINED)));

    assertEquals(
        Optional.empty(),
        prompt(ticket(EntityStatus.VERIFIED)),
        "VERIFIED has no phase left to run: a person closes it");
    assertEquals(
        Optional.empty(),
        prompt(ticket(EntityStatus.DONE)),
        "DONE is closed, and reopening it is a person's move too");
    assertEquals(
        Optional.empty(),
        prompt(ticket(EntityStatus.DROPPED)),
        "DROPPED is work somebody decided against: no phase renders, and dispatching an agent onto"
            + " it would start the very work the decision was not to do");
  }

  /** The word the thread comment uses comes from the same mapping, so the two cannot disagree. */
  @Test
  public void thePhaseIsNamedFromTheSameMappingThatRendersIt() {
    assertEquals(
        "refine", started(ticket(EntityStatus.REPORTED)).orElseThrow().phase());
    assertEquals(
        "implement",
        started(ticket(EntityStatus.READY_FOR_DEV)).orElseThrow().phase());
    assertEquals(
        "verify",
        started(ticket(EntityStatus.IMPLEMENTED)).orElseThrow().phase());
    assertEquals(
        Optional.empty(),
        started(ticket(EntityStatus.VERIFIED)),
        "no phase, so nothing to name either");
    assertEquals(
        Optional.empty(),
        started(ticket(EntityStatus.DROPPED)),
        "and a dropped ticket names no phase either, which is what leaves the dispatch door and the"
            + " phase hand-off both silent without either growing a rule of its own");
  }

  /** Every template sends the agent to the live ticket rather than to what it was handed. */
  @Test
  public void everyTemplateNamesTheTicketAndSendsTheAgentToReadItLive() {
    for (EntityStatus status :
        new EntityStatus[] {
          EntityStatus.REPORTED, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED
        }) {
      String prompt = promptFor(status);
      assertTrue(prompt.contains("id tkt-123)") && prompt.contains("get_ticket"), status + ": " + prompt);
      assertTrue(prompt.contains("Login button is the wrong colour"), status + ": " + prompt);
      assertTrue(prompt.contains("login-button-is-the-wrong-colour"), status + ": " + prompt);
    }
  }

  // ---- REFINE ---------------------------------------------------------------------------------

  /**
   * The hardest job this template has. An agent that has just found the bug wants to fix it, so the
   * prohibition carries its reason: the work is handed over, not refused.
   */
  @Test
  public void refineRefusesToImplementAndSaysWhy() {
    String prompt = promptFor(EntityStatus.REPORTED);
    assertTrue(prompt.contains("Do not implement anything"), prompt);
    assertTrue(
        prompt.contains("the implement phase is a separate session that starts from what you write"),
        "with its reason: " + prompt);
  }

  /**
   * The one sentence the next phase depends on literally: the description is the implement phase's
   * brief, and the dossier is the bounded exception for what prose cannot hold.
   */
  @Test
  public void refineWritesItsResultIntoTheDescription() {
    String prompt = promptFor(EntityStatus.REPORTED);
    assertTrue(
        prompt.contains("into the ticket's description with update_ticket"),
        "the field and the tool that writes it: " + prompt);
    assertTrue(
        prompt.contains("put_dossier_page (ticketId tkt-123) only for what prose cannot hold"),
        "with the dossier as the bounded exception: " + prompt);
  }

  /**
   * The refine turns end at REFINED, which the ACCEPTANCE_CRITERIA gate refuses without criteria
   * (qits-887): so each names the property, the tool that writes it and the item rules.
   */
  @Test
  public void bothRefineTurnsNameTheAcceptanceCriteriaTheirToolAndTheItemRules() {
    String ticket = promptFor(EntityStatus.REPORTED);
    String epic = epicPromptFor(EntityStatus.REPORTED);
    assertTrue(ticket.contains("Write its acceptanceCriteria with update_ticket"), ticket);
    assertTrue(epic.contains("Write its acceptanceCriteria with update_epic"), epic);
    for (String turn : new String[] {ticket, epic}) {
      assertTrue(
          turn.contains("each one line, at most one '.', fewer than 20 spaces"),
          "the item rules: " + turn);
    }
  }

  /** The impetus is the report, not the investigation, and the three types are refined apart. */
  @Test
  public void refineReadsTheImpetusAsTheReportAndGoesFurtherThanIt() {
    String prompt = promptFor(EntityStatus.REPORTED);
    assertTrue(prompt.contains("the impetus is the report"), prompt);
    assertTrue(prompt.contains("Investigate past the impetus"), prompt);
    assertTrue(prompt.contains("For a BUG") && prompt.contains("For an IMPROVEMENT"), prompt);
    assertTrue(prompt.contains("For a MAINTENANCE ticket"), prompt);
  }

  // ---- IMPLEMENT ------------------------------------------------------------------------------

  /** The platform's definition of done, with the two answers that read like done and are not. */
  @Test
  public void implementSaysReleasedAndDeployedRatherThanMergedOrBuilt() {
    String prompt = promptFor(EntityStatus.READY_FOR_DEV);
    assertTrue(
        prompt.contains("Done means released and deployed, not merged and not green"), prompt);
  }

  /**
   * <b>The assertion this class exists for.</b> Verification happens in this workspace after the
   * release, so an implement phase that integrates destroys its own successor's ground.
   */
  @Test
  public void implementForbidsIntegratingTheWorkspace() {
    String prompt = promptFor(EntityStatus.READY_FOR_DEV);
    assertTrue(
        prompt.contains("Do not integrate the workspace, because verification runs here next"),
        "a phase that integrates destroys the workspace the verify phase needs: " + prompt);
    assertFalse(
        prompt.contains("integrate the workspace and see the release through"),
        "the sentence this replaced must not survive anywhere in the template");
  }

  /** A thread and not a scratchpad; a contradiction goes on it, not into the brief. */
  @Test
  public void implementCommentsAsTheWorkGoesAndKeepsTheBrief() {
    String prompt = promptFor(EntityStatus.READY_FOR_DEV);
    assertTrue(prompt.contains("Comment with add_ticket_comment as the work goes"), prompt);
    assertFalse(prompt.contains("update_ticket_comment"), prompt);
    assertTrue(
        prompt.contains("say so on the thread and do not rewrite it"),
        "a contradiction goes on the thread, not into the brief it disagrees with: " + prompt);
  }

  // ---- VERIFY ---------------------------------------------------------------------------------

  /**
   * <b>The order of the two arms is part of the design.</b> An agent given both takes the one it
   * can finish in a single turn, so the code-reading fallback comes second — pinned by index.
   */
  @Test
  public void verifyPutsReproductionBeforeCodeReadingAndNamesBoth() {
    String prompt = promptFor(EntityStatus.IMPLEMENTED);
    int onThePlatform = prompt.indexOf("check on the live platform");
    int byReading = prompt.indexOf("Fall back to reading the change");
    assertTrue(onThePlatform >= 0, prompt);
    assertTrue(byReading >= 0, prompt);
    assertTrue(onThePlatform < byReading, "the fallback comes second: " + prompt);
    assertTrue(prompt.contains("cannot be reproduced on demand"), prompt);
    assertTrue(
        prompt.contains("Say on the thread with add_ticket_comment which of the two you did"),
        "the thread says which arm was used, since they are different evidence: " + prompt);
  }

  /**
   * <b>A failed verification blocks; it never moves back</b> (qits-592). The ticket stays
   * IMPLEMENTED with what still occurs on its thread, and what happens next is a person's call —
   * as is closing.
   */
  @Test
  public void verifyBlocksOnFailureAndLeavesClosingToAPerson() {
    String prompt = promptFor(EntityStatus.IMPLEMENTED);
    assertTrue(
        prompt.contains("If it still occurs, or you could not check, block_entity with what you"
            + " found"),
        prompt);
    assertTrue(prompt.contains("closing is a person's move"), prompt);
    assertFalse(prompt.contains("transition_ticket to DONE"), "an agent never closes a ticket");
    assertFalse(prompt.contains("REFINED"), "no route back to REFINED at all: " + prompt);
  }

  // ---- the shared ending ----------------------------------------------------------------------

  /**
   * Each template names exactly <b>one</b> transition, and it is its own phase's forward claim. The
   * check is on the {@code transition_ticket to X} form, so a second transition of any kind —
   * backward included — fails it.
   */
  @Test
  public void eachTemplateClaimsItsOwnPhaseAndNoOther() {
    assertNamesExactlyOneForwardTarget(promptFor(EntityStatus.REPORTED), "ticket", "REFINED");
    assertNamesExactlyOneForwardTarget(promptFor(EntityStatus.READY_FOR_DEV), "ticket", "IMPLEMENTED");
    assertNamesExactlyOneForwardTarget(promptFor(EntityStatus.IMPLEMENTED), "ticket", "VERIFIED");
    assertNamesExactlyOneForwardTarget(epicPromptFor(EntityStatus.REPORTED), "epic", "REFINED");
    assertNamesExactlyOneForwardTarget(epicPromptFor(EntityStatus.READY_FOR_DEV), "epic", "IMPLEMENTED");
    assertNamesExactlyOneForwardTarget(epicPromptFor(EntityStatus.IMPLEMENTED), "epic", "VERIFIED");
  }

  private static void assertNamesExactlyOneForwardTarget(
      String prompt, String noun, String target) {
    String tool = "transition_" + noun;
    assertEquals(
        1,
        occurrences(prompt, tool),
        "exactly one transition, the forward claim: " + prompt);
    for (EntityStatus status : EntityStatus.values()) {
      String claim = tool + " to " + status.name();
      boolean isOwn = status.name().equals(target);
      assertEquals(
          isOwn,
          prompt.contains(claim),
          (isOwn ? "the phase's own claim is missing: " : "a phase claimed " + status + ": ")
              + prompt);
    }
  }

  /**
   * <b>One ending for every phase of both archetypes</b> (qits-592): the forward transition, or
   * {@code block_entity}. There is no "leave the status where it is" arm and no comment-only arm —
   * a comment is invisible to everything that hands out work, so a phase that only commented went on
   * advertising itself as ready.
   */
  @Test
  public void everyTurnEndsWithTheForwardClaimOrABlock() {
    for (WorkEntity entity : everyPhaseOfBothArchetypes()) {
      String turn = prompt(entity).orElseThrow();
      String noun = entity.archetype == Archetype.TICKET ? "ticket" : "epic";
      assertTrue(turn.contains("block_entity with what"), entity.status + " " + noun + ": " + turn);
      assertTrue(
          turn.contains("transition_" + noun + " to " + forwardOf(entity.status)),
          entity.status + " " + noun + ": " + turn);
      assertFalse(turn.contains("block_ticket"), "the generic door, for both archetypes: " + turn);
      assertFalse(turn.contains("leave the " + noun), "no leave-it-where-it-is arm: " + turn);
      assertFalse(turn.contains("reversible"), "reversibility is the tool's to say: " + turn);
    }
  }

  /**
   * Each turn claims the status the served registry names as its phase's end ({@code phases[status]
   * .next.endsIn}): the page and the agent read one value.
   */
  @Test
  public void everyTurnClaimsTheServedEnd() {
    for (WorkEntity entity : everyPhaseOfBothArchetypes()) {
      String noun = entity.archetype == Archetype.TICKET ? "ticket" : "epic";
      var served =
          eu.wohlben.qits.entities.control.ArchetypeRegistryDocument.describe().archetypes().stream()
              .filter(a -> a.archetype() == entity.archetype)
              .findFirst()
              .orElseThrow()
              .phases()
              .get(entity.status)
              .next();
      assertEquals(PhasePrompts.phaseOf(entity).orElseThrow().word(), served.phase());
      assertTrue(
          prompt(entity).orElseThrow().contains("transition_" + noun + " to " + served.endsIn()),
          entity.status + " " + noun);
    }
  }

  /** <b>No template routes anything backwards</b> — a move back is a correction, not a failure path. */
  @Test
  public void noTemplateMovesBack() {
    for (WorkEntity entity : everyPhaseOfBothArchetypes()) {
      String turn = prompt(entity).orElseThrow();
      String lower = turn.toLowerCase(java.util.Locale.ROOT);
      assertFalse(turn.contains("BACK TO"), turn);
      for (EntityStatus status : EntityStatus.values()) {
        assertFalse(
            lower.contains("back to " + status.name().toLowerCase(java.util.Locale.ROOT)),
            "a backward move to " + status + ": " + turn);
      }
      assertFalse(lower.contains("move back") || lower.contains("backward"), turn);
    }
  }

  /**
   * <b>The length budget</b> (qits-592): each phase turn is at most 900 characters, not counting the
   * flow-brief pointer and its separating space, and not counting the substituted title, type,
   * qualified id, slug and id — each occurrence of each. Realistic values, so a long title cannot
   * hide in the budget.
   */
  @Test
  public void everyPhaseTurnFitsTheBudget() {
    String pointer = PhasePrompts.FLOW_BRIEF_POINTER;
    StringBuilder report = new StringBuilder();
    boolean over = false;
    for (WorkEntity entity : everyPhaseOfBothArchetypes()) {
      entity.id = "0f9c2e1a-7b3d-4c55-9e21-3a8b6d4f1c07";
      entity.title =
          entity.archetype == Archetype.TICKET
              ? "Phase prompts are too long and route failures through backward transitions"
              : "Retire the in-process executor: the platform host is a runner like any other";
      entity.slug = "phase-prompts-are-too-long-and-route-failures-through-b";
      String turn = prompt(entity).orElseThrow();
      assertTrue(turn.startsWith(pointer + " "), turn);
      String phaseTurn = turn.substring(pointer.length() + 1);
      String qualified = qualifiedOf(entity);
      int substituted =
          occurrences(phaseTurn, qualified) * qualified.length()
              + occurrences(phaseTurn, entity.title) * entity.title.length()
              + occurrences(phaseTurn, entity.slug) * entity.slug.length()
              + occurrences(phaseTurn, entity.id) * entity.id.length();
      if (entity.ticketType != null) {
        String type = "(" + entity.ticketType.name() + ", ";
        substituted += occurrences(phaseTurn, type) * entity.ticketType.name().length();
      }
      int counted = turn.length() - pointer.length() - 1 - substituted;
      report
          .append(entity.archetype)
          .append(' ')
          .append(entity.status)
          .append(": ")
          .append(counted)
          .append(" counted, ")
          .append(phaseTurn.length())
          .append(" raw\n")
          .append(phaseTurn)
          .append("\n\n");
      over |= counted > 900;
    }
    System.out.println("PHASE PROMPT BUDGET\n" + report);
    assertFalse(over, "a phase turn is over 900 characters:\n" + report);
  }

  private static WorkEntity[] everyPhaseOfBothArchetypes() {
    return new WorkEntity[] {
      ticket(EntityStatus.REPORTED),
      ticket(EntityStatus.READY_FOR_DEV),
      ticket(EntityStatus.IMPLEMENTED),
      epic(EntityStatus.REPORTED),
      epic(EntityStatus.READY_FOR_DEV),
      epic(EntityStatus.IMPLEMENTED)
    };
  }

  private static String forwardOf(String status) {
    return switch (EntityStatus.valueOf(status)) {
      case REPORTED -> "REFINED";
      case READY_FOR_DEV -> "IMPLEMENTED";
      case IMPLEMENTED -> "VERIFIED";
      default -> throw new AssertionError(status + " starts no phase");
    };
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  // ---- the flow-brief pointer ------------------------------------------------------------------

  /**
   * <b>No dispatched turn on this platform may open without the flow brief's pointer.</b> That is
   * the claim, and the way it is written is the point of the test: it walks <em>every</em> {@link
   * EntityStatus} rather than the three phases by name, so a fourth phase added to {@code
   * Phase.render}'s switch — or a fourth status that starts one — is covered on the day it is added
   * and cannot silently ship a turn without the pointer. The epic door is the fourth instruction on
   * the platform and is asserted here beside the three, against the <b>same constant</b>, which is
   * what makes "byte-identical" a fact rather than a review note: a second literal anywhere fails
   * this.
   *
   * <p>It is asserted as a <b>prefix</b>, not merely as present, because the brief it points at is
   * context for everything that follows and a pointer buried mid-turn is a pointer read after the
   * instructions it was meant to frame.
   */
  @Test
  public void everyDispatchedInstructionOpensWithTheOneFlowBriefPointer() {
    String pointer = PhasePrompts.FLOW_BRIEF_POINTER;
    assertTrue(
        pointer.contains("/workspace/docs/development-flow.md"),
        "the path is absolute, because a container's working directory is not guaranteed: "
            + pointer);
    assertTrue(
        pointer.contains("If that file is not there"),
        "and a project carrying no brief must not read as a broken instruction: " + pointer);

    for (EntityStatus status : EntityStatus.values()) {
      Optional<String> prompt = prompt(ticket(status));
      if (prompt.isEmpty()) {
        continue; // VERIFIED, DONE and DROPPED start no phase at all, which is asserted above.
      }
      assertTrue(
          prompt.get().startsWith(pointer + " "),
          status
              + " starts a phase, so its turn opens with the pointer — a template that skipped the"
              + " render seam would fail here: "
              + prompt.get());
    }

    for (EntityStatus status : EntityStatus.values()) {
      Optional<String> prompt = prompt(epic(status));
      prompt.ifPresent(
          turn ->
              assertTrue(
                  turn.startsWith(pointer + " "),
                  "and so does every epic phase, from the same seam and the same constant: "
                      + turn));
    }
  }

  private static WorkEntity epic(EntityStatus status) {
    WorkEntity epic = new WorkEntity();
    epic.id = "epc-9";
    epic.projectId = "prj-1";
    epic.archetype = Archetype.EPIC;
    epic.title = "Planning domain";
    epic.slug = "planning-domain";
    epic.status = status.name();
    return epic;
  }

  private static String epicPromptFor(EntityStatus status) {
    return prompt(epic(status))
        .orElseThrow(() -> new AssertionError("epic at " + status + " rendered no prompt"));
  }

  // ---- qits-394: one phase rule, words per archetype -------------------------------------------

  /**
   * <b>An epic and a ticket at the same status take the same path and get different words.</b> The
   * phase is the status's alone — the mapping never looks at the archetype — while the template is
   * the archetype's: a ticket's refine phase writes a description, an epic's writes a plan.
   */
  @Test
  public void anEpicAndATicketAtTheSameStatusStartTheSamePhaseWithDifferentWords() {
    for (EntityStatus status :
        new EntityStatus[] {
          EntityStatus.REPORTED,
          EntityStatus.READY_FOR_DEV,
          EntityStatus.IMPLEMENTING,
          EntityStatus.IMPLEMENTED
        }) {
      PhasePrompts.Started ticketRun = started(ticket(status)).orElseThrow();
      PhasePrompts.Started epicRun = started(epic(status)).orElseThrow();
      assertEquals(ticketRun.phase(), epicRun.phase(), status + " starts one phase for both");
      assertEquals(
          PhasePrompts.nextPhase(ticket(status)),
          PhasePrompts.nextPhase(epic(status)),
          "and the SPA is served the same word for both");
      assertFalse(
          ticketRun.instruction().equals(epicRun.instruction()),
          "but the words are the archetype's own at " + status);
      assertTrue(epicRun.instruction().contains("get_epic"), epicRun.instruction());
      assertFalse(epicRun.instruction().contains("get_ticket"), epicRun.instruction());
      assertFalse(ticketRun.instruction().contains("transition_epic"), ticketRun.instruction());
    }
  }

  /**
   * The three that start nothing start nothing for an epic too, and a feature or a task starts no
   * phase at any status: it holds one of its own since qits-763, but no phase runs on a piece.
   */
  @Test
  public void anEpicPastTheWorkAndAFeatureStartNoPhase() {
    for (EntityStatus status :
        new EntityStatus[] {EntityStatus.VERIFIED, EntityStatus.DONE, EntityStatus.DROPPED}) {
      assertEquals(Optional.empty(), prompt(epic(status)), status.name());
      assertEquals(Optional.empty(), PhasePrompts.nextPhase(epic(status)), status.name());
    }
    for (Archetype piece : new Archetype[] {Archetype.FEATURE, Archetype.TASK}) {
      for (EntityStatus status : EntityStatus.values()) {
        WorkEntity row = epic(status);
        row.archetype = piece;
        assertEquals(Optional.empty(), PhasePrompts.nextPhase(row), piece + " at " + status);
        assertEquals(Optional.empty(), prompt(row), piece + " at " + status);
      }
    }
  }

  /**
   * The epic refine turn: the plan is three things — description, tree, dossier — written into the
   * epic, nothing implemented, and the freeze is the agent's claim.
   */
  @Test
  public void theEpicRefineTurnWritesThePlanIntoTheEpicAndFreezesIt() {
    String turn = epicPromptFor(EntityStatus.REPORTED);
    assertTrue(turn.contains("Refine epic \"Planning domain\""), turn);
    for (String tool :
        new String[] {"update_epic", "add_feature", "add_task", "put_dossier_page, epicId epc-9"}) {
      assertTrue(turn.contains(tool), "the refine phase is told to write with " + tool + ": " + turn);
    }
    assertTrue(turn.contains("each task naming one repository and its dependsOn links"), turn);
    assertTrue(turn.contains("Do not implement anything"), turn);
    assertTrue(turn.contains("transition_epic to REFINED, which freezes the scope"), turn);
  }

  /**
   * The epic implement turn: the frozen brief with corrections on the thread, dependsOn order,
   * mark_task_implementing as each starts and mark_task_implemented as each lands — each moving the
   * task's own status (qits-763) — a task verified on its own with transition_task, released per
   * repository, no integration, and the claim to IMPLEMENTED — which carries every unmarked task.
   */
  @Test
  public void theEpicImplementTurnMarksReleasesAndClaimsImplemented() {
    String turn = epicPromptFor(EntityStatus.READY_FOR_DEV);
    assertTrue(turn.contains("Implement epic \"Planning domain\""), turn);
    assertTrue(turn.contains("get_epic: its tree and dossier are the brief"), turn);
    assertTrue(turn.contains("Both are read-only now"), turn);
    assertTrue(turn.contains("add_comment (entityId epc-9) as the work goes"), turn);
    assertTrue(turn.contains("in dependsOn order"), turn);
    assertTrue(turn.contains("mark_task_implementing when you start one"), turn);
    assertTrue(turn.contains("mark_task_implemented as it lands"), turn);
    assertTrue(turn.contains("each moves that task's status"), turn);
    assertTrue(turn.contains("Verify a task on its own"), turn);
    assertTrue(turn.contains("transition_task it to VERIFIED"), turn);
    assertTrue(turn.contains("released and deployed, through a release request per repository"), turn);
    assertTrue(turn.contains("not merged and not green"), turn);
    assertTrue(turn.contains("Do not integrate the workspace"), turn);
    assertTrue(turn.contains("When every task is marked, transition_epic to IMPLEMENTED"), turn);
    assertTrue(turn.contains("carries any unmarked task to IMPLEMENTED"), turn);
  }

  /**
   * <b>IMPLEMENTING resumes the implement phase</b> (qits-749): a press on an entity whose
   * implementation was started is handed the same implement turn as at READY_FOR_DEV, not a 409.
   */
  @Test
  public void implementingStartsTheImplementPhaseWithTheSameTurnAsReadyForDev() {
    assertEquals(Optional.of("implement"), PhasePrompts.nextPhase(ticket(EntityStatus.IMPLEMENTING)));
    assertEquals(Optional.of("implement"), PhasePrompts.nextPhase(epic(EntityStatus.IMPLEMENTING)));
    assertEquals(epicPromptFor(EntityStatus.READY_FOR_DEV), epicPromptFor(EntityStatus.IMPLEMENTING));
  }

  /** The epic verify turn: the live platform first, the code second, VERIFIED or a block. */
  @Test
  public void theEpicVerifyTurnConfirmsLiveAndClaimsVerifiedOrBlocks() {
    String turn = epicPromptFor(EntityStatus.IMPLEMENTED);
    assertTrue(turn.contains("Verify epic \"Planning domain\""), turn);
    assertTrue(
        turn.indexOf("check on the live platform, feature by feature")
            < turn.indexOf("Fall back to reading the change"),
        "reproducing comes first and the fallback second: " + turn);
    assertTrue(turn.contains("transition_epic to VERIFIED; closing is a person's move"), turn);
    assertTrue(turn.contains("If something does not hold, or you could not check, block_entity"), turn);
    assertTrue(turn.contains("add_comment (entityId epc-9)"), turn);
    assertFalse(turn.contains("REFINED"), "no route back to REFINED: " + turn);
  }

  /**
   * <b>An epic's findings go on its thread, never into a report</b> (qits-551): every epic phase
   * names {@code add_comment} against the epic's own id.
   */
  @Test
  public void everyEpicTemplateRecordsOnTheThreadAndNeverInAReport() {
    for (EntityStatus status :
        new EntityStatus[] {
          EntityStatus.REPORTED, EntityStatus.READY_FOR_DEV, EntityStatus.IMPLEMENTED
        }) {
      String turn = epicPromptFor(status);
      assertTrue(
          turn.contains("add_comment (entityId epc-9)"),
          status + " must name add_comment on the epic's own thread: " + turn);
      assertFalse(turn.contains("in your report"), status + ": " + turn);
    }
  }

  // ---- qits-301: the qualified id and the commit-subject convention ----------------------------

  /**
   * <b>Every phase of both archetypes names its entity by qualified id</b>, beside the slug and the
   * row id, in the header's parenthesis — the one place the agent learns the id it is to put in its
   * commit subjects.
   */
  @Test
  public void everyTurnNamesTheEntityByItsQualifiedIdBesideSlugAndId() {
    for (WorkEntity entity : everyPhaseOfBothArchetypes()) {
      String turn = prompt(entity).orElseThrow();
      String header =
          entity.archetype == Archetype.TICKET
              ? "(BUG, qits-123, slug login-button-is-the-wrong-colour, id tkt-123)"
              : "(qits-9, slug planning-domain, id epc-9)";
      assertTrue(turn.contains(header), entity.archetype + " " + entity.status + ": " + turn);
    }
  }

  /**
   * <b>Both implement turns give the commit-subject form literally, with the real id</b>, worded as
   * the convention rather than as a rule: most repositories refuse nothing yet, and an agent told it
   * will be refused looks for the refusal instead of writing the subject.
   */
  @Test
  public void bothImplementTurnsGiveTheCommitSubjectFormWithTheRealId() {
    String ticketTurn = promptFor(EntityStatus.READY_FOR_DEV);
    assertTrue(
        ticketTurn.contains(
            "Each commit subject names the work: term(qits-123): message, e.g. feat(qits-123): add"
                + " the export."),
        ticketTurn);
    String epicTurn = epicPromptFor(EntityStatus.READY_FOR_DEV);
    assertTrue(
        epicTurn.contains(
            "Each commit subject names the work: term(qits-9): message, e.g. feat(qits-9): add the"
                + " export."),
        epicTurn);
    for (String turn : new String[] {ticketTurn, epicTurn}) {
      String lower = turn.toLowerCase(java.util.Locale.ROOT);
      assertFalse(
          lower.contains("refuse") || lower.contains("rejected") || lower.contains("must name"),
          "the convention, not an enforced rule: " + turn);
    }
  }

  /**
   * <b>No qualified id degrades cleanly</b>: a row with no number or no project slug is named by
   * slug and id exactly as before qits-301 — no {@code null} in the words, and no commit-subject
   * sentence, since there is no id to put in one.
   */
  @Test
  public void aRowWithNoQualifiedIdIsNamedAsBeforeAndGetsNoCommitSentence() {
    for (WorkEntity entity : everyPhaseOfBothArchetypes()) {
      for (String missing : new String[] {null, "", "  "}) {
        String turn = PhasePrompts.promptFor(entity, missing).orElseThrow();
        String where = entity.archetype + " " + entity.status + " [" + missing + "]: " + turn;
        assertFalse(turn.contains("null"), where);
        assertFalse(turn.contains("term("), where);
        assertFalse(turn.contains("commit subject"), where);
        assertFalse(turn.contains(", , "), where);
        String header =
            entity.archetype == Archetype.TICKET
                ? "(BUG, slug login-button-is-the-wrong-colour, id tkt-123)"
                : "(slug planning-domain, id epc-9)";
        assertTrue(turn.contains(header), where);
      }
    }
  }
}
