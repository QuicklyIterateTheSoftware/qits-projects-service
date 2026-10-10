package eu.wohlben.qits.projects.dto;

/**
 * Where a release request's <b>pre-run</b> stands at its current fold (qits-1133) — the
 * release-request automations (dependency bumps, estate pins, the entity diagram, screenshot
 * baselines) that run before QA is asked for, read as one state.
 *
 * <p><b>{@code state}</b> is one of:
 *
 * <ul>
 *   <li>{@code PENDING} — nothing is folded yet, nothing has answered about this fold, the answer
 *       could not be read, or the only moving kinds are waiting on others ({@code WAITING});
 *   <li>{@code RUNNING} — an applicable kind is REQUESTED, RUNNING, or COMMITTED and its commit is
 *       still to re-fold the request;
 *   <li>{@code FAILED} — an applicable kind failed at this fold; the request holds until a push, a
 *       re-run or a waiver;
 *   <li>{@code PASSED} — every applicable kind is fresh at this fold, or none applies, and QA has
 *       been (or is about to be) asked for;
 *   <li>{@code WAIVED} — a person waived the automations at this fold, which passes it too.
 * </ul>
 *
 * <p>Derived on every read, like every gate's answer — out of the automations ledger, the waiver at
 * this fold and, where the ledger has nothing (a restart), {@code qa_announced_sha}, which says the
 * pre-run of this fold was already found done. A word rather than a closed set on the wire, like
 * every state word on this surface.
 */
public record ReleasePreRunDto(String state) {}
