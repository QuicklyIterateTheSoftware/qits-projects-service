package eu.wohlben.qits.projects.entitieshost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.CommitService.SubjectLine;
import eu.wohlben.qits.projects.entitieshost.CommitSubjectCompliance.Verdict;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The classification rule as a pure function (qits-302, qits-706): the five commit-message
 * variants, in order, first match wins, and nothing about the author. Plain JUnit — no application
 * to boot.
 *
 * <p><b>{@code commit-message-vectors.tsv} is shared with qits-githost verbatim</b>, which asserts
 * the same rows against its receive hook's {@code CommitMessageVariants}: the two readings of the
 * rule agree because both are held to one table. Never edit a row here alone.
 */
class CommitSubjectComplianceTest {

  private static final String VECTORS = "/commit-message-vectors.tsv";

  private static SubjectLine commit(int parents, String email, String subject) {
    return new SubjectLine(
        "a".repeat(40), "aaaaaaa", parents, "someone", email, "2026-10-01", subject);
  }

  /** {@code expected, parents, message} per row; {@code \n} in a message is a literal newline. */
  static Stream<Arguments> vectors() throws IOException {
    List<Arguments> rows = new ArrayList<>();
    try (InputStream in = CommitSubjectComplianceTest.class.getResourceAsStream(VECTORS)) {
      assertNotNull(in, VECTORS + " is on the test classpath");
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : text.split("\n", -1)) {
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        String[] f = line.split("\t", -1);
        assertEquals(3, f.length, "a vector row has three columns: " + line);
        rows.add(
            Arguments.of(f[0], Integer.parseInt(f[1]), f[2].replace("\\n", "\n")));
      }
    }
    assertFalse(rows.isEmpty(), "the vectors are not empty");
    return rows.stream();
  }

  @ParameterizedTest(name = "{0} <- parents={1} {2}")
  @MethodSource("vectors")
  void everySharedVectorClassifiesAsTheGitHostClassifiesIt(
      String expected, int parents, String message) {
    Optional<CommitMessageVariants> variant = CommitMessageVariants.classify(message, parents);
    assertEquals(expected, variant.map(Enum::name).orElse("NONE"), message);

    Verdict verdict = CommitSubjectCompliance.classify(commit(parents, "p@example.com", message));
    assertEquals(variant.orElse(null), verdict.variant());
    assertEquals(variant.isPresent(), verdict.complies());
    if (variant.orElse(null) == CommitMessageVariants.SUBJECT) {
      // SUBJECT must stay a strict subset of the reader: an accepted subject always names an id.
      assertFalse(CommitSubjectEntities.references(message).isEmpty(), message);
      assertEquals(CommitSubjectEntities.references(message), verdict.ids());
    } else {
      assertTrue(verdict.ids().isEmpty(), "only a SUBJECT commit carries ids: " + message);
    }
  }

  @Test
  void everyVariantsExampleFitsItAndClassifiesAsIt() {
    for (CommitMessageVariants variant : CommitMessageVariants.values()) {
      int parents = variant == CommitMessageVariants.MERGE ? 2 : 1;
      assertTrue(variant.fits(variant.example(), parents), variant.name());
      assertEquals(
          Optional.of(variant), CommitMessageVariants.classify(variant.example(), parents));
    }
  }

  @Test
  void theAuthorDecidesNothing() {
    for (String email :
        new String[] {"maintenance@qits.local", "qits-projects@qits.internal", "qits@local"}) {
      Verdict free =
          CommitSubjectCompliance.classify(commit(1, email, "Initialize the template skeleton"));
      assertNull(free.variant(), email);
      assertFalse(free.complies(), email);
    }
  }

  @Test
  void aScopeNamingSeveralIdsIsOneSubjectVerdictCarryingAllOfThem() {
    Verdict verdict =
        CommitSubjectCompliance.classify(
            commit(1, "p@example.com", "chore(qits-1, qits-2): x"));
    assertEquals(CommitMessageVariants.SUBJECT, verdict.variant());
    assertEquals(
        List.of(
            new CommitSubjectEntities.QualifiedId("qits", 1L),
            new CommitSubjectEntities.QualifiedId("qits", 2L)),
        verdict.ids());
  }

  @Test
  void onlyTheFirstLineCounts() {
    assertEquals("feat(qits-1): x", CommitMessageVariants.firstLine("feat(qits-1): x\n\nbody"));
    assertEquals("", CommitMessageVariants.firstLine(null));
    assertEquals(
        Optional.empty(),
        CommitMessageVariants.classify("Fix it\nfeat(qits-1): an id in the body", 1));
  }
}
