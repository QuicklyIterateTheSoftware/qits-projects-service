package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One decision a person made about one fold of a release request, as the API answers it — an entry
 * of the approval trail rather than the request's current answer, which is what the five approval
 * fields on {@link ReleaseRequestDto} carry.
 *
 * <p><b>The two reads are different questions and that is why this record exists.</b> The request
 * answers "what is the position at the fold you are looking at", and it is a derived answer that
 * silently drops every decision made about a sha the request has moved past. The trail answers "what
 * has been said about this release, ever", superseded folds included: what somebody refused, what
 * was changed in response and what they accepted in the end are three facts, and only the second
 * read can show them. Wanting the history is the ordinary case — a reader of a wrapper release wants
 * to know whether it was waved through or argued about.
 *
 * <p><b>{@code mergedSha} is on every entry and is the load-bearing column, not decoration.</b> A
 * decision is about content and never about a request, so the sha is what says which fold was
 * judged; without it a decline followed by an approval is indistinguishable from somebody changing
 * their mind about the same code. A reader compares it against the request's current {@code
 * mergedSha} to see which entries still count — at most one of them does, the newest at that sha.
 *
 * <p>{@code decision} is {@code APPROVED} or {@code DECLINED} today, and it is a word rather than a
 * closed set for the reason every other verdict-shaped field in this package is: the vocabulary may
 * grow (a conditional yes, a delegation) and a caller that switched exhaustively on two values would
 * be the thing that broke. {@code note} is null where the decider said nothing, which an approval
 * often does and a decline rarely should.
 *
 * <p>Rows are never updated and never deleted, so an entry that has been answered is still here. The
 * list is <b>newest first</b>, the order a trail is read in.
 */
public record ReleaseRequestApprovalDto(
    String id,
    String mergedSha,
    String decision,
    String actor,
    String note,
    Instant decidedAt) {}
