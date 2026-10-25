package eu.wohlben.qits.projects.agenthost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>What a front desk daemon's answer means</b> (qits-1572, shared since epic qits-1604): the one
 * reading of the daemon's {@code front-desk/*} answers, for the person's doors in {@code
 * api/ProjectFrontDeskController} and for the desk trigger sweep, which prompts the desk with no
 * person behind it.
 *
 * <p>The reading, which the door's javadoc states from the caller's side:
 *
 * <ul>
 *   <li>The daemon's <b>200, 400, 404, 409 and 429</b> with a body are its contract, passed on
 *       verbatim.
 *   <li>A <b>404 without a {@code reason}</b> is a daemon whose router does not know the route — one
 *       too old for it — and reads as a <b>409</b>: nothing is down, the spec roll resolves it. A
 *       route whose own 404 is part of its contract ({@code last-answer}: no kept command) always
 *       carries a {@code reason}, which is how the two are told apart.
 *   <li>Anything else (a <b>5xx</b> — its agent surface still booting — or an empty body), no
 *       tunnel, and a failed hop read as a <b>503</b>.
 * </ul>
 *
 * <p>{@link #classify} and {@link #unreachable} answer that as the status and body a door returns;
 * {@link #prompt} asks the daemon itself and answers a typed {@link Outcome}, for a caller that
 * acts on the answer rather than passing it on.
 */
@ApplicationScoped
public class FrontDeskPrompter {

  private static final Logger LOG = Logger.getLogger(FrontDeskPrompter.class);

  /** The daemon's prompt route, under its proxied base path (see {@code ContainerProxyPath}). */
  public static final String PROMPT_ROUTE = "front-desk/prompt";

  @Inject DaemonApiClient daemon;

  @Inject ObjectMapper json;

  /** The door's own bound on one proxied call — the same key, so the two hops agree. */
  @ConfigProperty(name = "qits.projects.front-desk.relay-timeout-ms", defaultValue = "10000")
  long timeoutMs;

  /**
   * What a door answers: the daemon's body {@code verbatim}, or a {@code reason} of this service's
   * own. Exactly one of the two is set.
   */
  public record Reply(int status, String verbatim, String reason) {

    static Reply passThrough(int status, String body) {
      return new Reply(status, body, null);
    }

    static Reply refusal(int status, String reason) {
      return new Reply(status, null, reason);
    }
  }

  /** What became of a prompt, for a caller that acts on it. */
  public sealed interface Outcome {

    /** Handed to the desk session at once. */
    record Delivered(String commandId) implements Outcome {}

    /** Taken behind a running turn, at {@code position} in the desk's queue. */
    record Queued(String commandId, Integer position) implements Outcome {}

    /**
     * Not taken, for now: {@code status} is what the door would answer (409 no kept command or a
     * daemon too old, 429 the queue full, 503 outside the contract), {@code reason} why.
     */
    record Refused(int status, String reason) implements Outcome {}

    /** The daemon could not be asked: no tunnel, or the hop failed. */
    record Unreachable(String detail) implements Outcome {}

    /** The daemon called the prompt itself malformed (400): asking again will not help. */
    record Invalid(String reason) implements Outcome {}
  }

  /**
   * The daemon's answer to {@code route} as a door answers it.
   *
   * @param notFoundIsContract whether the daemon's route itself answers 404 (last-answer: no kept
   *     command); such a 404 carries a {@code reason}, a router's does not
   */
  public Reply classify(
      String projectId, String route, boolean notFoundIsContract, DaemonApiClient.Answer answer) {
    int status = answer.status();
    String body = answer.body();
    boolean hasBody = body != null && !body.isBlank();
    if (status == 404 && !(notFoundIsContract && hasReason(body))) {
      LOG.infof(
          "The front desk daemon of project %s does not serve %s (404: %s); it predates qits-1572",
          projectId, route, body);
      return Reply.refusal(
          409,
          "The project's front desk runs a daemon too old to serve "
              + route
              + "; it is rolled onto the current one once it is quiet.");
    }
    if ((status == 200 || status == 400 || status == 404 || status == 409 || status == 429)
        && hasBody) {
      return Reply.passThrough(status, body);
    }
    LOG.infof(
        "The front desk daemon of project %s answered %d for %s outside its contract: %s",
        projectId, Integer.valueOf(status), route, body);
    return Reply.refusal(
        503,
        "The project's front desk answered "
            + status
            + (hasBody ? "" : " with no body")
            + "; it is not ready to take this yet.");
  }

  /** A daemon that could not be asked, as a door answers it: always 503. */
  public Reply unreachable(String projectId, DaemonApiClient.Unreachable e) {
    if (e.noTunnel()) {
      return Reply.refusal(503, "No front desk daemon is connected for project " + projectId + ".");
    }
    LOG.infof("Could not reach the front desk of project %s: %s", projectId, e.getMessage());
    return Reply.refusal(503, "The project's front desk could not be reached.");
  }

  /**
   * Hand {@code projectId}'s desk the prompt {@code text} and say what became of it. Never throws:
   * an interrupt is restored and answered as {@link Outcome.Unreachable}.
   */
  public Outcome prompt(String projectId, String text) {
    String body;
    try {
      body = json.writeValueAsString(Map.of("text", text));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    DaemonApiClient.Answer answer;
    try {
      answer = daemon.post(projectId, PROMPT_ROUTE, body, timeoutMs);
    } catch (DaemonApiClient.Unreachable e) {
      Reply reply = unreachable(projectId, e);
      return new Outcome.Unreachable(
          e.noTunnel() ? reply.reason() : reply.reason() + " " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome.Unreachable("Interrupted while asking the project's front desk.");
    }
    Reply reply = classify(projectId, PROMPT_ROUTE, false, answer);
    if (reply.verbatim() == null) {
      return new Outcome.Refused(reply.status(), reply.reason());
    }
    JsonNode node = readTree(reply.verbatim());
    return switch (reply.status()) {
      case 200 -> accepted(node);
      case 400 -> new Outcome.Invalid(reasonOf(node, reply.verbatim()));
      default -> new Outcome.Refused(reply.status(), reasonOf(node, reply.verbatim()));
    };
  }

  /** A 200: delivered or queued, and one that says neither is outside the contract. */
  private static Outcome accepted(JsonNode node) {
    String commandId = node == null ? null : node.path("commandId").asText(null);
    if (node != null && node.path("delivered").asBoolean(false)) {
      return new Outcome.Delivered(commandId);
    }
    if (node != null && node.path("queued").asBoolean(false)) {
      JsonNode position = node.path("position");
      return new Outcome.Queued(
          commandId, position.isNumber() ? Integer.valueOf(position.asInt()) : null);
    }
    return new Outcome.Refused(
        503, "The project's front desk answered 200 but neither delivered nor queued the prompt.");
  }

  /** The daemon's {@code reason}, else its {@code message}, else the body as it came. */
  private static String reasonOf(JsonNode node, String body) {
    if (node != null && node.hasNonNull("reason")) {
      return node.get("reason").asText();
    }
    if (node != null && node.hasNonNull("message")) {
      return node.get("message").asText();
    }
    return body;
  }

  private JsonNode readTree(String body) {
    try {
      return json.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  private boolean hasReason(String body) {
    if (body == null || body.isBlank()) {
      return false;
    }
    JsonNode node = readTree(body);
    return node != null && node.hasNonNull("reason");
  }
}
