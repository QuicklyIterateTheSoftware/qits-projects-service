package eu.wohlben.qits.projects.entitieshost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.CommitService.SubjectLine;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectCompliance.Classification;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectCompliance.ExemptReason;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectCompliance.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The classification rule as a pure function (qits-302): merge before machine before the grammar,
 * and the guard file read the way qits-githost reads it. Plain JUnit — no application to boot.
 */
class CommitSubjectComplianceTest {

  private static final Set<String> MACHINE = Set.of("maintenance@qits.local");

  private static SubjectLine commit(int parents, String email, String subject) {
    return new SubjectLine(
        "a".repeat(40), "aaaaaaa", parents, "someone", email, "2026-10-01", subject);
  }

  @Test
  void aMergeIsExemptEvenWhenItsSubjectComplies() {
    Verdict verdict =
        CommitSubjectCompliance.classify(
            commit(2, "person@example.com", "feat(qits-1): x"), MACHINE);
    assertEquals(Classification.EXEMPT, verdict.classification());
    assertEquals(ExemptReason.MERGE, verdict.reason());
  }

  @Test
  void aMachineAuthorIsExemptBeforeTheGrammarIsAsked() {
    Verdict free =
        CommitSubjectCompliance.classify(
            commit(1, "maintenance@qits.local", "bump(dependencies): 2 dependencies"), MACHINE);
    assertEquals(Classification.EXEMPT, free.classification());
    assertEquals(ExemptReason.MACHINE, free.reason());
    Verdict complying =
        CommitSubjectCompliance.classify(
            commit(1, " Maintenance@QITS.local ", "feat(qits-1): x"), MACHINE);
    assertEquals(ExemptReason.MACHINE, complying.reason(), "case and padding do not matter");
  }

  @Test
  void aPersonsCommitCompliesOnlyWhenTheParserFindsAnId() {
    Verdict yes =
        CommitSubjectCompliance.classify(commit(1, "p@example.com", "feat(qits-1): x"), MACHINE);
    assertEquals(Classification.COMPLYING, yes.classification());
    assertNull(yes.reason());
    assertEquals(1, yes.ids().size());
    assertEquals("qits-1", yes.ids().get(0).rendered());
    for (String subject :
        new String[] {"Fix the thing", "fix: tidy (qits-7): aside", "", "feat(qits): x"}) {
      assertEquals(
          Classification.NON_COMPLYING,
          CommitSubjectCompliance.classify(commit(1, "p@example.com", subject), MACHINE)
              .classification(),
          subject);
    }
    assertEquals(
        Classification.NON_COMPLYING,
        CommitSubjectCompliance.classify(commit(0, "p@example.com", "Initial commit"), MACHINE)
            .classification(),
        "a root commit is an ordinary commit");
  }

  @Test
  void aScopeNamingSeveralIdsIsOneComplyingVerdictCarryingAllOfThem() {
    Verdict verdict =
        CommitSubjectCompliance.classify(
            commit(1, "p@example.com", "chore(qits-1, qits-2): x"), MACHINE);
    assertEquals(Classification.COMPLYING, verdict.classification());
    assertEquals(
        List.of(
            new CommitSubjectEntities.QualifiedId("qits", 1L),
            new CommitSubjectEntities.QualifiedId("qits", 2L)),
        verdict.ids());
  }

  @Test
  void theGuardFileIsReadAsTheGitHostReadsIt() {
    assertTrue(CommitSubjectCompliance.enforceTrue("enforce: true\n"));
    assertTrue(CommitSubjectCompliance.enforceTrue("# on\nenforce: true # yes\n"));
    assertTrue(CommitSubjectCompliance.enforceTrue("enforce: false\nenforce: true"));
    assertFalse(CommitSubjectCompliance.enforceTrue(""));
    assertFalse(CommitSubjectCompliance.enforceTrue("enforce: yes"));
    assertFalse(CommitSubjectCompliance.enforceTrue("enforce: True"));
    assertFalse(CommitSubjectCompliance.enforceTrue("guard:\n  enforce: true"));
    assertFalse(CommitSubjectCompliance.enforceTrue("enforce: true\nenforce: false"));
  }
}
