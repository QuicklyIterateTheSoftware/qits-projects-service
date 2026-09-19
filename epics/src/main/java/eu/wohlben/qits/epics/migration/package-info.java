/**
 * <b>TEMPORARY BY DESIGN. DELETE THIS WHOLE PACKAGE WITH THE FOUR OLD TABLES, IN V13.</b>
 *
 * <p>This package is the migration verification door: the comparison of the four frozen old tables
 * ({@code Epic}, {@code Ticket}, {@code Feature}, {@code Task}) against the unified {@code entity} /
 * {@code entity_membership} model, run on demand against whatever database the process is pointed
 * at. A clean run is the evidence that authorises {@code V13__drop_legacy_planning_tables.sql}; the
 * moment that migration lands, every class here compares a table that no longer exists and the whole
 * package — plus {@code service/…/epics/api/MigrationVerificationController} and {@code
 * epics/src/test/…/migration/MigrationVerificationTest} — goes with it. It is listed in
 * {@code docs/unified-entity-model.md}'s "Where the code is" table under that instruction so the
 * cleanup task does not have to hunt for it.
 *
 * <p><b>It is main code and not a test, which is the whole point.</b> The estate a test can build is
 * the estate a test put there; what has to be verified is the estate three months of use put in the
 * live database, and the only thing that can read that is a door on the running process. That is
 * also why it is here rather than in {@code control/}: {@code control/} is where the module's live
 * rules live, and a reader who finds the verification beside {@code EpicService} would reasonably
 * take it for one of them.
 *
 * <p><b>It mirrors {@code epics/src/test/…/epics/migration/}</b>, which already holds the migration
 * tests, so the package name a reader looks under for "the code about the migration" answers on both
 * source roots.
 *
 * <p>The three things it deliberately does <em>not</em> assert — the reverse direction, the V10
 * slug de-collision and the surplus of rows created since the cutover — are argued in {@link
 * eu.wohlben.qits.epics.migration.MigrationVerification} and are carried, in words, on every answer
 * it gives ({@link eu.wohlben.qits.epics.migration.VerificationScope}).
 */
package eu.wohlben.qits.epics.migration;
