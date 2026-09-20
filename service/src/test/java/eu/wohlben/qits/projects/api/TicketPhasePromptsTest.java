package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.epics.entity.WorkEntity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The three phase templates, asserted <b>sentence by sentence</b>, plus the mapping that picks
 * between them.
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
 * TicketDispatchControllerTest} is where the door's use of it is pinned end to end.
 */
public class TicketPhasePromptsTest {

  private static WorkEntity ticket(TicketStatus status) {
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

  private static String promptFor(TicketStatus status) {
    return TicketPhasePrompts.promptFor(ticket(status))
        .orElseThrow(() -> new AssertionError(status + " rendered no prompt"));
  }

  // ---- the mapping ----------------------------------------------------------------------------

  /**
   * The status picks the phase and nothing else does. The two that render <b>nothing</b> are half
   * of this assertion and are what the dispatch door refuses on: VERIFIED and DONE are past the
   * work, so there is no phase to start and no workspace to stand up for one.
   */
  @Test
  public void eachStatusStartsItsOwnPhaseAndTwoStartNone() {
    assertTrue(promptFor(TicketStatus.REPORTED).contains("Refine ticket \""));
    assertTrue(promptFor(TicketStatus.REFINED).contains("Implement ticket \""));
    assertTrue(promptFor(TicketStatus.IMPLEMENTED).contains("Verify ticket \""));

    assertEquals(
        Optional.empty(),
        TicketPhasePrompts.promptFor(ticket(TicketStatus.VERIFIED)),
        "VERIFIED has no phase left to run: a person closes it");
    assertEquals(
        Optional.empty(),
        TicketPhasePrompts.promptFor(ticket(TicketStatus.DONE)),
        "DONE is closed, and reopening it is a person's move too");
  }

  /** The word the thread comment uses comes from the same mapping, so the two cannot disagree. */
  @Test
  public void thePhaseIsNamedFromTheSameMappingThatRendersIt() {
    assertEquals(
        "refine", TicketPhasePrompts.startedBy(ticket(TicketStatus.REPORTED)).orElseThrow().phase());
    assertEquals(
        "implement",
        TicketPhasePrompts.startedBy(ticket(TicketStatus.REFINED)).orElseThrow().phase());
    assertEquals(
        "verify",
        TicketPhasePrompts.startedBy(ticket(TicketStatus.IMPLEMENTED)).orElseThrow().phase());
    assertEquals(
        Optional.empty(),
        TicketPhasePrompts.startedBy(ticket(TicketStatus.VERIFIED)),
        "no phase, so nothing to name either");
  }

  /** Every template sends the agent to the live ticket rather than to what it was handed. */
  @Test
  public void everyTemplateNamesTheTicketAndSendsTheAgentToReadItLive() {
    for (TicketStatus status :
        new TicketStatus[] {
          TicketStatus.REPORTED, TicketStatus.REFINED, TicketStatus.IMPLEMENTED
        }) {
      String prompt = promptFor(status);
      assertTrue(prompt.contains("get_ticket (id tkt-123)"), status + ": " + prompt);
      assertTrue(prompt.contains("Login button is the wrong colour"), status + ": " + prompt);
      assertTrue(prompt.contains("login-button-is-the-wrong-colour"), status + ": " + prompt);
    }
  }

  // ---- REFINE ---------------------------------------------------------------------------------

  /**
   * The hardest job this template has. An agent that has just found the bug wants to fix it, and
   * the fix is usually small — so the prohibition is stated as the phase's boundary and carries its
   * reason, and an edit that softened it into a preference would fail here.
   */
  @Test
  public void refineRefusesToImplementAndSaysWhy() {
    String prompt = promptFor(TicketStatus.REPORTED);
    assertTrue(
        prompt.contains("DO NOT IMPLEMENT ANYTHING"),
        "the boundary of the refine phase, in the imperative: " + prompt);
    assertTrue(
        prompt.contains("no fix, no refactor, no commit"),
        "spelled out, because 'implement' is a word an agent can read narrowly");
    assertTrue(
        prompt.contains("lifecycle exists to stop"),
        "with its reason: implementing from an unwritten understanding is what this stops");
  }

  /**
   * The one sentence the next phase depends on literally. A refinement that lands as a comment or a
   * file in the tree leaves a REFINED ticket whose description is still empty, and the implement
   * phase then starts from nothing.
   */
  @Test
  public void refineWritesItsResultIntoTheDescriptionAndNowhereElse() {
    String prompt = promptFor(TicketStatus.REPORTED);
    assertTrue(
        prompt.contains("INTO THE TICKET'S DESCRIPTION with update_ticket"),
        "the field and the tool that writes it: " + prompt);
    assertTrue(
        prompt.contains("not a comment, not a file in the repository"),
        "and the tidy-looking alternatives, refused by name");
    assertTrue(
        prompt.contains("put_dossier_page"),
        "with the dossier as the bounded exception for what prose cannot hold");
  }

  /** The impetus is the report, not the investigation, and the template says both halves. */
  @Test
  public void refineReadsTheImpetusAsTheReportAndGoesFurtherThanIt() {
    String prompt = promptFor(TicketStatus.REPORTED);
    assertTrue(prompt.contains("the impetus is what was asked for"), prompt);
    assertTrue(prompt.contains("Explore the code further than the impetus goes"), prompt);
    assertTrue(
        prompt.contains("For a BUG") && prompt.contains("for an IMPROVEMENT"),
        "and the two types are refined differently: " + prompt);
  }

  // ---- IMPLEMENT ------------------------------------------------------------------------------

  /**
   * The platform's own definition of done, and the one an agent left to itself gets wrong. The
   * negatives are asserted too: merged and green are the two answers that read like done.
   */
  @Test
  public void implementSaysReleasedAndDeployedRatherThanMergedOrBuilt() {
    String prompt = promptFor(TicketStatus.REFINED);
    assertTrue(prompt.contains("RELEASING IS THE GOAL"), prompt);
    assertTrue(prompt.contains("released and deployed to the platform"), prompt);
    assertTrue(
        prompt.contains("not when it is merged, and not when the build is green"),
        "the two answers that read like done and are not: " + prompt);
  }

  /**
   * <b>The assertion this class exists for.</b> Verification happens in this workspace after the
   * release, so an implement phase that integrates destroys its own successor's ground — and the
   * instruction this template replaced said the opposite, so the sentence has to be there in the
   * imperative and with its reason.
   */
  @Test
  public void implementForbidsIntegratingTheWorkspace() {
    String prompt = promptFor(TicketStatus.REFINED);
    assertTrue(
        prompt.contains("DO NOT INTEGRATE THE WORKSPACE"),
        "a phase that integrates destroys the workspace the verify phase needs: " + prompt);
    assertTrue(
        prompt.contains("integrating it ends the workspace the next phase needs"),
        "with the reason, since the previous instruction on this door said the opposite");
    assertFalse(
        prompt.contains("integrate the workspace and see the release through"),
        "the sentence this replaces must not survive anywhere in the template");
  }

  /**
   * A thread and not a scratchpad — deliberately replacing "keep one comment current". A running
   * commentary is the record of how the work was done; one comment rewritten in place keeps only the
   * last state.
   */
  @Test
  public void implementCommentsAsTheWorkGoesRatherThanAtTheEnd() {
    String prompt = promptFor(TicketStatus.REFINED);
    assertTrue(prompt.contains("Comment as the work goes with add_ticket_comment"), prompt);
    assertTrue(
        prompt.contains("rather than writing one report at the end"),
        "the habit it replaces, named: " + prompt);
    assertFalse(
        prompt.contains("update_ticket_comment"),
        "keeping one comment current is exactly what this phase stopped doing");
    assertTrue(
        prompt.contains("say so on the thread rather than rewriting the description"),
        "and a contradiction goes on the thread, not into the brief it disagrees with");
  }

  // ---- VERIFY ---------------------------------------------------------------------------------

  /**
   * <b>The order of the two arms is part of the design.</b> Reproduce where reproducing is
   * possible; read the code only where the situation is conceptually unreproducible. An agent given
   * both in either order takes the one it can finish in a single turn, so the fallback must come
   * second — that is what this assertion pins, by index and not merely by presence.
   */
  @Test
  public void verifyPutsReproductionBeforeCodeReadingAndNamesBoth() {
    String prompt = promptFor(TicketStatus.IMPLEMENTED);
    int onThePlatform = prompt.indexOf("Verify ON THE PLATFORM");
    int byReading = prompt.indexOf("READING THE RELEVANT CODE CHANGES");
    assertTrue(onThePlatform >= 0, "the live platform is the subject: " + prompt);
    assertTrue(byReading >= 0, "and the fallback is named rather than left to be invented: " + prompt);
    assertTrue(
        onThePlatform < byReading,
        "the fallback must not be offered as the easy path, so it comes second");
    assertTrue(
        prompt.contains("conceptually unreproducible"),
        "and it is bounded by a test rather than a mood: " + prompt);
    assertTrue(
        prompt.contains("a passing test suite is not the claim being made"),
        "the negative, because the suite is the nearest thing to hand");
    assertTrue(
        prompt.contains("which of the two you did and why"),
        "and the thread has to say which arm was used, since they are different evidence");
  }

  /**
   * The failure arm is a backward transition and the only one in these three templates: there is no
   * reject verb in this lifecycle, so IMPLEMENTED → REFINED is how implementation starts again.
   * Closing stays a person's move.
   */
  @Test
  public void verifyFallsBackToRefinedAndLeavesClosingToAPerson() {
    String prompt = promptFor(TicketStatus.IMPLEMENTED);
    assertTrue(prompt.contains("transition_ticket BACK TO REFINED"), prompt);
    assertTrue(
        prompt.contains("that is how implementation starts again"),
        "the backward move is the whole failure path, and is said to be: " + prompt);
    assertTrue(
        prompt.contains("Closing the ticket is a person's move and not yours"),
        "VERIFIED is as far as this phase goes: " + prompt);
    assertFalse(prompt.contains("transition_ticket to DONE"), "an agent never closes a ticket");
  }

  // ---- the shared ending ----------------------------------------------------------------------

  /**
   * Each template names <b>one</b> forward transition target and it is its own phase's. The check is
   * on the {@code transition_ticket to X} form rather than on the status word, because every
   * template also names the status it should be <em>left</em> at — and the verify template names
   * REFINED a second time as its explicit backward move, which is asserted above as the load-bearing
   * sentence it is.
   */
  @Test
  public void eachTemplateClaimsItsOwnPhaseAndNoOther() {
    assertNamesExactlyOneForwardTarget(promptFor(TicketStatus.REPORTED), "REFINED");
    assertNamesExactlyOneForwardTarget(promptFor(TicketStatus.REFINED), "IMPLEMENTED");
    assertNamesExactlyOneForwardTarget(promptFor(TicketStatus.IMPLEMENTED), "VERIFIED");
  }

  private static void assertNamesExactlyOneForwardTarget(String prompt, String target) {
    for (TicketStatus status : TicketStatus.values()) {
      String claim = "transition_ticket to " + status.name();
      boolean isOwn = status.name().equals(target);
      assertEquals(
          isOwn,
          prompt.contains(claim),
          (isOwn ? "the phase's own claim is missing: " : "a phase claimed " + status + ": ")
              + prompt);
    }
  }

  /**
   * The ending every template shares, and the reason it is worth asserting three times: the
   * transition is the agent's claim, it is reversible, and an agent that could not finish says so on
   * the thread and <b>leaves the status where it is</b>. That is the cheap correct answer for an
   * unsure agent, and it matters more with five statuses than it did with two — a wrong forward move
   * now skips the phase that would have caught it.
   */
  @Test
  public void everyTemplateEndsWithAReversibleClaimAndAWayToNotMakeIt() {
    for (TicketStatus status :
        new TicketStatus[] {
          TicketStatus.REPORTED, TicketStatus.REFINED, TicketStatus.IMPLEMENTED
        }) {
      String prompt = promptFor(status);
      assertTrue(
          prompt.contains("the transition is your claim"),
          status + " must say the transition is the agent's own claim: " + prompt);
      assertTrue(
          prompt.contains("reversible in both directions through the same door"),
          status + " must say the claim is reversible: " + prompt);
      assertTrue(
          prompt.contains("leave the ticket " + leftAt(status)),
          status + " must leave the status where it is when it could not finish: " + prompt);
      assertTrue(
          prompt.contains("say on the thread") || prompt.contains("say so on the thread"),
          status + " must say what is missing on the thread: " + prompt);
    }
  }

  /** The status an unfinished phase leaves behind: the one it was started from. */
  private static String leftAt(TicketStatus status) {
    return status.name();
  }

  // ---- the flow-brief pointer ------------------------------------------------------------------

  /**
   * <b>No dispatched turn on this platform may open without the flow brief's pointer.</b> That is
   * the claim, and the way it is written is the point of the test: it walks <em>every</em> {@link
   * TicketStatus} rather than the three phases by name, so a fourth phase added to {@code
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
    String pointer = TicketPhasePrompts.FLOW_BRIEF_POINTER;
    assertTrue(
        pointer.contains("/workspace/docs/development-flow.md"),
        "the path is absolute, because a container's working directory is not guaranteed: "
            + pointer);
    assertTrue(
        pointer.contains("If that file is not there"),
        "and a project carrying no brief must not read as a broken instruction: " + pointer);

    for (TicketStatus status : TicketStatus.values()) {
      Optional<String> prompt = TicketPhasePrompts.promptFor(ticket(status));
      if (prompt.isEmpty()) {
        continue; // VERIFIED and DONE start no phase at all, which is asserted above.
      }
      assertTrue(
          prompt.get().startsWith(pointer + " "),
          status
              + " starts a phase, so its turn opens with the pointer — a template that skipped the"
              + " render seam would fail here: "
              + prompt.get());
    }

    assertTrue(
        EpicDispatchController.instruction(epic()).startsWith(pointer + " "),
        "and so does the epic door, from the same constant and not a copy of the words");
  }

  private static WorkEntity epic() {
    WorkEntity epic = new WorkEntity();
    epic.id = "epc-9";
    epic.projectId = "prj-1";
    epic.archetype = Archetype.EPIC;
    epic.title = "Planning domain";
    epic.slug = "planning-domain";
    epic.status = EpicStatus.IMPLEMENTATION.name();
    return epic;
  }
}
