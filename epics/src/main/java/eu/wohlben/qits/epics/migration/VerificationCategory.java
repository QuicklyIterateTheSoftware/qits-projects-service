package eu.wohlben.qits.epics.migration;

import java.util.List;

/**
 * One question the door asked, what it asked it of, and what came back.
 *
 * <p><b>Every category is present on every answer, including a clean one, and every category states
 * how many rows it compared.</b> That is the shape requirement this record exists to enforce: a
 * verification whose clean result is an empty list and a zero is indistinguishable from a
 * verification that ran nothing at all, and the whole purpose of this door is to be the evidence a
 * person acts on when they drop four tables. So a reader who wants to disbelieve the result can: the
 * category says what it counted, the count is a number they can check against the database
 * themselves, and a zero beside a compared count of zero is visibly a vacuous pass rather than a
 * silent one.
 *
 * @param name a stable, kebab-case key — the thing to quote when talking about a finding
 * @param kind whether findings here are defects, expected differences, or evidence; see {@link
 *     VerificationKind}
 * @param question what was asked, in one sentence, in words. It states the exemptions the SQL
 *     applies, because an exemption a reader cannot see is a check they cannot judge.
 * @param comparedRows how many rows this category actually looked at
 * @param compared what {@code comparedRows} counts, in words — "rows in Epic, Ticket, Feature and
 *     Task", "edges in entity_membership". The number alone is not evidence of anything.
 * @param findings how many rows answered the question. <b>This is the full count, never the size of
 *     {@link #sample()}</b>, which is what makes the truncation below visible instead of silent.
 * @param truncated true when {@link #sample()} is shorter than {@link #findings()}
 * @param sample the first {@link MigrationVerification#MAX_FINDINGS_PER_CATEGORY} findings in a
 *     deterministic order, so two runs against an unchanged database produce the same document
 */
public record VerificationCategory(
    String name,
    VerificationKind kind,
    String question,
    long comparedRows,
    String compared,
    long findings,
    boolean truncated,
    List<VerificationFinding> sample) {}
