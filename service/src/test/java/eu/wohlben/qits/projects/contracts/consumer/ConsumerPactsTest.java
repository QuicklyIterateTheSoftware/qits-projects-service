package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.matchers.Mismatch;
import au.com.dius.pact.core.matchers.ResponseMatching;
import au.com.dius.pact.core.model.ContentType;
import au.com.dius.pact.core.model.HttpResponse;
import au.com.dius.pact.core.model.IResponse;
import au.com.dius.pact.core.model.OptionalBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of every pact this service holds</b> (ticket qits-1149): each {@link
 * ConsumerRow} runs its real client against a pact-jvm mock server that answers what the row's
 * interaction promises, and the row's own assertions check what the client made of it.
 *
 * <p><b>A row whose provider state is not recorded yet is skipped</b>, with the state it needs as
 * the reason: the provider has not published golden masters for it, and a pact without a recorded
 * answer would be invented data. It turns live by itself when a bump of the provider's
 * golden-masters pin brings the state in.
 *
 * <p>One mock server per row, as qits-maintenance's copy does: several rows send the same request
 * from different triggers, which one shared server could not tell apart.
 */
class ConsumerPactsTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatItsClientAsksAndUnderstands() {
    ConsumerGoldenMasters masters = ConsumerGoldenMasters.classpath();
    return ConsumerContracts.all().stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.provider() + " — " + row.description() + " [" + row.state() + "]",
                    () -> {
                      Optional<String> pending = row.pending(masters);
                      Assumptions.assumeTrue(pending.isEmpty(), () -> pending.orElseThrow());
                      run(masters, row);
                    }));
  }

  /**
   * <b>Every interaction accepts the very answer it was built from</b>, matched by pact-jvm as a
   * provider verification matches it. The recording is the provider's honest answer in that state,
   * so a matcher that refuses it would fail the provider's verification of this pact: qits-1149's
   * {@code failure: null} against an object example, which "type or null" did not save.
   */
  @TestFactory
  Stream<DynamicTest> everyInteractionAcceptsTheRecordedAnswer() {
    ConsumerGoldenMasters masters = ConsumerGoldenMasters.classpath();
    return ConsumerContracts.all().stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.provider() + " — " + row.description() + " [" + row.state() + "]",
                    () -> {
                      Optional<String> pending = row.pending(masters);
                      Assumptions.assumeTrue(pending.isEmpty(), () -> pending.orElseThrow());
                      List<Mismatch> mismatches = againstRecording(masters, row);
                      assertTrue(
                          mismatches.isEmpty(),
                          row.description() + " [" + row.state() + "] refuses its own recording: "
                              + mismatches.stream().map(Mismatch::description).toList());
                    }));
  }

  /** What pact-jvm finds wrong with the recorded answer, held against the row's interaction. */
  static List<Mismatch> againstRecording(ConsumerGoldenMasters masters, ConsumerRow row) {
    ConsumerGoldenMasters.Recorded recorded = row.recorded(masters).orElseThrow();
    IResponse expected =
        ConsumerInteractions.pact(masters, List.of(row))
            .getInteractions()
            .get(0)
            .asSynchronousRequestResponse()
            .getResponse();
    HashMap<String, List<String>> headers = new HashMap<>();
    OptionalBody body = OptionalBody.missing();
    if (!row.consumes().isEmpty()) {
      headers.put("Content-Type", List.of("application/json"));
      body =
          OptionalBody.body(
              recorded.body().toString().getBytes(StandardCharsets.UTF_8), ContentType.Companion.getJSON());
    }
    HttpResponse actual = new HttpResponse(recorded.status(), headers, body);
    return ResponseMatching.responseMismatches(expected, actual);
  }

  /** (description, state) is how a pact tells interactions apart; it must not repeat. */
  @Test
  void noTwoRowsOfOneProviderShareADescriptionAndState() {
    for (List<ConsumerRow> rows : ConsumerContracts.byProvider().values()) {
      Set<String> seen = new HashSet<>();
      for (ConsumerRow row : rows) {
        assertTrue(
            seen.add(row.description() + "\u0000" + row.state()),
            row.provider() + ": (" + row.description() + ", " + row.state() + ") repeats");
      }
    }
  }

  static void run(ConsumerGoldenMasters masters, ConsumerRow row) {
    ConsumerGoldenMasters.Recorded recorded = row.recorded(masters).orElseThrow();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            ConsumerInteractions.pact(masters, List.of(row)),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              try {
                row.call().run(mockServer.getUrl(), recorded.params());
              } finally {
                // The row's client is done: its JDK HttpClient's threads go now, not at some GC.
                RowClients.closeAll();
              }
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(row.description() + " [" + row.state() + "]: " + describe(result));
    }
  }

  static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
