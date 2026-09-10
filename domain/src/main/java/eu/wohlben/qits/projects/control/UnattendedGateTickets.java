package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * A red gate on a release request <b>nobody is watching</b>, put in front of a person as a ticket.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A release request a <em>person</em> opened has a person waiting on it: they watch it, and a
 * REJECTED is a thing they answer. A request the platform's maintenance robot opened has nobody —
 * it is the tail of an automated night, a bump wrote {@code maintenance/dependencies}, asked for it
 * to be released and stopped — so a red gating verdict on that fold is seen by no one at all and the
 * repository quietly stops moving. Measured on 2026-09-10: qits-deployments-platform-service was
 * bumped at 07:05, its request was REJECTED at 07:14 on a build dying in Quarkus Arc, and four hours
 * later nothing had noticed. The estate-side half of that (qits-maintenance no longer waiting
 * forever on a release that is never coming) reports the stall; this is the half that tells somebody.
 *
 * <p>The ownership test is {@code release_request.requester}: qits-maintenance deliberately sends no
 * requester, so this service attributes those requests to its machine identity, and that column is
 * the whole of the difference between "somebody is looking at this" and "nobody is".
 *
 * <h2>One ticket per stuck request, not one per verdict</h2>
 *
 * <p>A request re-folds and re-gates on every push to a participating branch, on a sibling's release
 * and on every pending tag reaching {@code main}, so one stuck repository can go red many times over.
 * A ticket per verdict would be a ticket storm on exactly the repository somebody is already trying
 * to fix. So the dedupe is carried by the caller: {@code release_request.gate_ticket_id} remembers
 * the ticket this request already has — and a request is per (repository, branch) by construction,
 * because asking to release a branch that already participates in an open request converges on it —
 * and {@link Rejection#existingTicketId()} hands it back on the next failure. An implementation
 * <b>comments</b> on that ticket while it is open and files a fresh one only when there is none or
 * the one there is has been resolved.
 *
 * <h2>Best-effort, and that is a contract</h2>
 *
 * <p><b>An implementation must never throw.</b> Filing the ticket happens beside a gate decision
 * that has already been made and beside a release that has already happened; neither may be failed,
 * re-settled or held by a ticket store having a bad day. Say so once and return. The caller keeps a
 * belt round the call anyway, the way it does round {@code ReleaseExecutor}: a throw is a port bug,
 * not a gate outcome.
 *
 * <h2>Absent is a supported configuration</h2>
 *
 * <p>Injected as {@code Instance<T>} like every port here. With no implementation present the gate
 * still rejects, still writes its sentence and still marks the request unattended on the API — what
 * is lost is only the ticket, which is what this platform did up to now.
 */
public interface UnattendedGateTickets {

  /**
   * A red gating verdict on an unattended request, as much as a ticket needs to name it.
   *
   * @param requestId the release request that was rejected — the address of the thing that stopped
   * @param projectId the project the ticket is filed on; a request whose repository has no project
   *     row has nowhere to file and is skipped by the caller
   * @param repoId the catalog repository id, for a reader who has to ask another service about it
   * @param repoName the repository's public name, or null where it has none
   * @param branches the request's named branch sources — what was being released
   * @param mergedSha the fold the gate evaluated
   * @param runId the gating run that came back red, as the ledger recorded it
   * @param status that run's terminal status — FAILED, CANCELLED, whatever the ledger holds
   * @param detail this service's own rejection sentence, verbatim, so the ticket and the request say
   *     the same thing
   * @param requester the machine identity the request is attributed to
   * @param existingTicketId the ticket this request already has, or null where it has none; see the
   *     dedupe section of the class javadoc
   */
  record Rejection(
      String requestId,
      String projectId,
      String repoId,
      String repoName,
      List<String> branches,
      String mergedSha,
      String runId,
      String status,
      String detail,
      String requester,
      String existingTicketId) {}

  /**
   * File or update the ticket for this rejection.
   *
   * <p>Never throws.
   *
   * @return the id of the ticket now carrying this failure — the existing one where a comment was
   *     added, a new one where one was filed — for the caller to remember, or empty where nothing
   *     could be written at all. An empty answer must leave the stored link alone rather than clear
   *     it: "could not file" and "there is no ticket" are different facts.
   */
  Optional<String> rejected(Rejection rejection);

  /**
   * The request that carried {@code ticketId} has released after all — said on the ticket's thread.
   *
   * <p><b>It does not resolve the ticket</b>, and that is the caller's decision rather than an
   * implementation's: a green build says the fold passes now, not that whatever a person added to
   * the thread in the meantime is handled. A machine that files and a machine that closes are two
   * different amounts of confidence, and only the first one is cheap to be wrong about.
   *
   * <p>Never throws.
   */
  void released(String ticketId, String requestId, String repoName, String version);
}
