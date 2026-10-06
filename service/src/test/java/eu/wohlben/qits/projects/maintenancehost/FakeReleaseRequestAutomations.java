package eu.wohlben.qits.projects.maintenancehost;

import eu.wohlben.qits.projects.control.ReleaseRequestAutomations;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's {@link ReleaseRequestAutomations}: an ordinary bean, which beats the {@code
 * @DefaultBean} {@link HttpReleaseRequestAutomations} at the port's injection point, so no test asks
 * qits-maintenance anything. {@link FakeDownstreamComponents}' shape one class over, including the
 * rule that costs the most to rediscover — <b>everything is read through methods and nothing is a
 * public field</b>, because the injected reference is a CDI client proxy and a proxy does not proxy
 * field access.
 *
 * <p><b>It is configured and answers "no kind applies" by default</b>, because that is the posture
 * in which every release-request test that is not about automations behaves exactly as it did
 * before the gate existed: an empty list is a FRESH note and the request releases on the same pass.
 * A test about the gate scripts kinds per repository name ({@link #answer}) — the same states for
 * the trigger and the read, at whatever fold is asked — and the three other postures are one call
 * each: {@link #answerNothing()} (unreachable), {@link #unconfigure()} (no address at all) and
 * {@link #refuseReruns}.
 */
@ApplicationScoped
public class FakeReleaseRequestAutomations implements ReleaseRequestAutomations {

  /** One trigger, exactly as the port declares it. */
  public record Asked(
      String repositoryName,
      String requestId,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches) {}

  /** One re-run ask. */
  public record Rerun(String repositoryName, String requestId, String kind) {}

  private final List<Asked> asked = Collections.synchronizedList(new ArrayList<>());

  private final List<String> reads = Collections.synchronizedList(new ArrayList<>());

  private final List<Rerun> reruns = Collections.synchronizedList(new ArrayList<>());

  /** Per repository name: kind → state, in the order scripted. */
  private final Map<String, Map<String, String>> scripted = new ConcurrentHashMap<>();

  private volatile boolean reachable = true;

  private volatile boolean configured = true;

  private volatile Run rerunAnswer;

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  /** The asks about one request, oldest first. */
  public List<Asked> askedAbout(String requestId) {
    return asked().stream().filter(ask -> ask.requestId().equals(requestId)).toList();
  }

  /** The request ids the status read was asked about, in order. */
  public List<String> reads() {
    return List.copyOf(reads);
  }

  public List<Rerun> reruns() {
    return List.copyOf(reruns);
  }

  /**
   * From now on {@code kind} of {@code repositoryName} stands at {@code state} — on the trigger and
   * on the read, at any fold. Label is the kind's own words capitalised.
   */
  public void answer(String repositoryName, String kind, String state) {
    scripted.computeIfAbsent(repositoryName, name -> new LinkedHashMap<>()).put(kind, state);
  }

  /** Answer "could not ask" from here on: unreachable, refusing, unparseable. */
  public void answerNothing() {
    reachable = false;
  }

  /** No address: the gate is not configured at all. */
  public void unconfigure() {
    configured = false;
  }

  /** The next re-runs are refused with this status and sentence. */
  public void refuseReruns(int status, String detail) {
    rerunAnswer = Run.refused(status, detail);
  }

  public void reset() {
    asked.clear();
    reads.clear();
    reruns.clear();
    scripted.clear();
    reachable = true;
    configured = true;
    rerunAnswer = null;
  }

  @Override
  public boolean configured() {
    return configured;
  }

  @Override
  public Optional<Answer> request(
      String repositoryName,
      String requestId,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem) {
    asked.add(
        new Asked(
            repositoryName,
            requestId,
            foldSha,
            previousFoldSha,
            changedSincePrevious == null ? null : List.copyOf(changedSincePrevious),
            List.copyOf(sourceBranches)));
    return reachable ? Optional.of(answerAt(repositoryName, requestId, foldSha)) : Optional.empty();
  }

  @Override
  public Optional<Answer> status(String repositoryName, String requestId, String foldSha) {
    reads.add(requestId);
    return reachable ? Optional.of(answerAt(repositoryName, requestId, foldSha)) : Optional.empty();
  }

  @Override
  public Run rerun(String repositoryName, String requestId, String kind) {
    reruns.add(new Rerun(repositoryName, requestId, kind));
    Run scriptedRun = rerunAnswer;
    if (scriptedRun != null) {
      return scriptedRun;
    }
    return reachable ? Run.accepted("bump-" + UUID.randomUUID()) : Run.unreachable("down");
  }

  private Answer answerAt(String repositoryName, String requestId, String foldSha) {
    List<Automation> entries = new ArrayList<>();
    (repositoryName == null ? Map.<String, String>of() : scripted.getOrDefault(repositoryName, Map.of()))
        .forEach(
            (kind, state) ->
                entries.add(
                    new Automation(
                        kind,
                        label(kind),
                        state,
                        null,
                        "bump-" + kind,
                        "FRESH".equals(state) ? List.of() : List.of("run-" + kind),
                        null,
                        null,
                        Instant.now())));
    return new Answer(requestId, foldSha, entries);
  }

  private static String label(String kind) {
    String words = kind.replace('-', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
