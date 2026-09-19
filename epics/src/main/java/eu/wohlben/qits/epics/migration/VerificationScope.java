package eu.wohlben.qits.epics.migration;

import java.util.List;

/**
 * <b>What was and was not checked, in words, on every answer — including a clean one.</b>
 *
 * <p>This record is not documentation that happens to be serialised. It is the load-bearing half of
 * the result: the door's clean verdict is what authorises dropping four tables, and a person reading
 * "CLEAN" has to be able to read, in the same response, the three things a clean run does <em>not
 * </em> say. Putting those sentences only in javadoc would mean the evidence and the caveats live in
 * two places and only one of them travels to whoever presses the button.
 *
 * <p>The sentences are constants — see {@link MigrationVerification#SCOPE} — so every run carries
 * the identical wording and a diff between two runs is a diff about the estate rather than about the
 * prose.
 *
 * @param direction <b>FORWARD ONLY.</b> The reverse assertion — "no entity has an id that no old row
 *     had" — is false by design and is not made.
 * @param createdSinceCutover why counts are legitimately unequal, and in which direction
 * @param deCollidedSlugs the V10 epic/ticket slug de-collision, and why a ticket whose slugs differ
 *     is not a defect
 * @param changedSinceCutover the {@code updated_at} discriminator and exactly what it buys and costs
 * @param deletedSinceCutover why an old row with no entity row is sometimes a DELETE audit entry
 *     rather than a lost row
 * @param workBranches what "a work branch still resolves" can mean when no branch is stored in this
 *     database at all
 * @param truncation the per-category cap, stated so a truncated list is never mistaken for a short
 *     one
 * @param notChecked everything a reader might reasonably assume was covered and was not, each with
 *     its reason. An empty list here would be the most misleading thing this document could carry.
 */
public record VerificationScope(
    String direction,
    String createdSinceCutover,
    String deCollidedSlugs,
    String changedSinceCutover,
    String deletedSinceCutover,
    String workBranches,
    String truncation,
    List<String> notChecked) {}
