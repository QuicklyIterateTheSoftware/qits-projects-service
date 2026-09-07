package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * Answers "what else on this platform is built on top of this repository" — the downstream closure of
 * one repository, as one ordered list of repository names.
 *
 * <h2>Where the answer comes from</h2>
 *
 * The data source is <b>qits-maintenance's dependency graph</b>: the SBOM edges it ingests plus the
 * pins it parses (a wrapper's gitlinks included), traced to the very end rather than one hop. That
 * graph is not this context's to hold — nothing here knows what a package is — so this is a port in
 * the house shape and its one implementation is an HTTP client in {@code service/…/maintenancehost/}.
 *
 * <h2>Why this context asks at all</h2>
 *
 * The motivating consumer is <b>qits-ci's queue ordering</b>. With three release requests open — a
 * frontend library, the frontend that pins it and a service that consumes the frontend — the correct
 * build order is lib, then frontend, then service, because each release renovates the next. qits-ci
 * can sequence its queue that way only if the {@code ReleaseRequestChanged} event it queues on says
 * what is downstream of the repository it names, so this answer is read at fold time and carried on
 * that event. It is <b>advisory</b> at every hop: it suggests an order and never decides whether
 * something builds.
 *
 * <h2>The three answers, and they are three</h2>
 *
 * <ul>
 *   <li>{@code Optional.empty()} — <b>could not ask</b>. No implementation, no address configured,
 *       an unreachable or refusing qits-maintenance, a route that has not shipped yet, an answer that
 *       will not parse. The caller carries no field at all and the consumer reads "unknown".
 *   <li>{@code Optional.of(List.of())} — <b>asked, and it is a leaf</b>. Nothing on this platform is
 *       built on this repository. That is real information and is deliberately not the same answer as
 *       the one above.
 *   <li>{@code Optional.of([…])} — the closure, <b>ordered nearest-first</b> (depth ascending, then
 *       name), naming repositories the way this service's catalogue names them.
 * </ul>
 *
 * <p><b>Nothing here may throw.</b> This is asked on the fold path, after the merge has already
 * landed, and a fold must never fail over an enrichment: an implementation turns every failure into
 * {@code Optional.empty()} itself rather than leaving the caller to catch it. It must also be bounded
 * — one short request, never a retry loop — because the announcement waits on it.
 *
 * <p><b>Absent is a supported configuration</b>, like every port in this package: with no
 * implementation the announcement is made exactly as it was before this port existed, which is what
 * {@code domain}'s own suite runs as. Injected as an {@code Instance<T>} for that reason.
 */
public interface DownstreamComponents {

  /**
   * The downstream closure of one repository.
   *
   * @param repoId this service's repository row id — which <b>is</b> qits-maintenance's {@code
   *     catalogId}, so an implementation addresses the far side by it and needs no name lookup
   * @param repoName the repository's public name, or null where it has none — carried for the log
   *     and as the spelling a fallback lookup would use, never as the primary key of the question
   * @return empty when the question could not be asked; an empty list when it was asked and this
   *     repository is a leaf; otherwise the downstream repository names, nearest first. Never null.
   */
  Optional<List<String>> downstreamOf(String repoId, String repoName);
}
