package eu.wohlben.qits.epics.migration;

import java.time.Instant;
import java.util.List;

/**
 * The whole answer: the verdict, the caveats that qualify it, and every category with its compared
 * count and its findings.
 *
 * <p><b>It is not a boolean and must never be reduced to one.</b> The operation this document
 * authorises is destructive and irreversible — V13 drops the four tables that are the recovery path
 * — so what a person needs is not "yes" but the material to disagree with "yes". Hence the
 * categories are all present on a clean run, each carrying what it compared; hence {@link #scope()}
 * travels with the verdict rather than under it; and hence there is no summary sentence that could
 * be read instead of the body.
 *
 * @param generatedAt when the comparison ran. Nothing here is cached or stored: the door is a read,
 *     it may be pressed as often as anybody likes, and two runs a minute apart may legitimately
 *     differ because the unified model is live and being written the whole time.
 * @param verdict {@code CLEAN} when no {@link VerificationCategory.Kind#DISCREPANCY} category
 *     answered, {@code DISCREPANCIES} otherwise. Nothing else is ever spelled here.
 * @param discrepancies the total across the discrepancy categories — the number that has to be zero
 *     before V13 is written
 * @param scope what was and was not checked; read it before reading the verdict
 * @param categories every question asked, in a fixed order: the census first, then the checks, then
 *     the expected-difference categories. Order is stable so two runs diff cleanly.
 */
public record VerificationReport(
    Instant generatedAt,
    String verdict,
    long discrepancies,
    VerificationScope scope,
    List<VerificationCategory> categories) {

  /** No discrepancy category answered. It does <em>not</em> mean no category answered. */
  public static final String CLEAN = "CLEAN";

  /** At least one discrepancy category answered; the cleanup is not authorised. */
  public static final String DISCREPANCIES = "DISCREPANCIES";

  /**
   * Builds the report around a finished list of categories, deriving the verdict from them rather
   * than taking it as an argument — a verdict passed in beside the categories is a second answer to
   * one question and is free to disagree with the body it heads.
   */
  static VerificationReport of(Instant generatedAt, VerificationScope scope,
      List<VerificationCategory> categories) {
    long discrepancies =
        categories.stream()
            .filter(category -> category.kind() == VerificationKind.DISCREPANCY)
            .mapToLong(VerificationCategory::findings)
            .sum();
    return new VerificationReport(
        generatedAt,
        discrepancies == 0 ? CLEAN : DISCREPANCIES,
        discrepancies,
        scope,
        List.copyOf(categories));
  }
}
