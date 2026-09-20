package eu.wohlben.qits.epics.api;

import eu.wohlben.qits.epics.migration.MigrationVerificationService;
import eu.wohlben.qits.epics.migration.VerificationReport;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>TEMPORARY. Deleted by the V13 cleanup, together with {@code
 * eu.wohlben.qits.epics.migration} and the four old tables it compares.</b>
 *
 * <p><b>{@code GET /projects/api/entities/migration-verification}</b> — the four frozen old tables
 * ({@code Epic}, {@code Ticket}, {@code Feature}, {@code Task}) against the unified {@code entity} /
 * {@code entity_membership} model, on whatever database this process is pointed at, answering
 * <em>every</em> discrepancy it finds.
 *
 * <p>It exists because the estate that has to be verified is the live one. Every migration test in
 * this repository starts from a database a test seeded, which proves the statements are right about
 * the rows a test wrote and says nothing about three months of real planning. A clean run of this
 * route against production is the evidence that authorises V13, which drops the recovery path; so it
 * has to be runnable on demand, repeatedly, with no deployment, and its answer has to be something a
 * person can disbelieve and check.
 *
 * <h2>It answers the discrepancies themselves, never a boolean</h2>
 *
 * <p>The operation this authorises is destructive and irreversible, so "yes" is not a useful answer
 * — the material to argue with "yes" is. Every category is present on a clean run, each carrying how
 * many rows of what kind it compared, and {@code scope} states in words what was deliberately NOT
 * checked. A count of zero beside a compared count of zero is a vacuous pass, and this shape is what
 * makes that visible rather than reassuring.
 *
 * <h2>Why the path is {@code /entities/migration-verification}</h2>
 *
 * <p>{@code /entities} is the merged model's segment — {@code EntityTransitionController}'s "Why
 * {@code /entities}" section is the argument and it applies unchanged: the unified entity is the
 * noun, and filing this under {@code /epics} or {@code /tickets} would put a question about all four
 * archetypes under one of them. It is a <b>second root resource</b> rather than a method on that
 * class because JAX-RS gives a class one {@code @Path} and these are two subjects: one is the write
 * surface of the model, the other is a temporary audit of how the model was populated, and they have
 * opposite lifetimes — the transition is permanent and this is deleted by V13. Keeping them apart is
 * what makes the deletion a file removal rather than surgery. It sits under {@code /projects} like
 * every machine surface here, so {@code quarkus.quinoa.ignored-path-prefixes} needs no line.
 *
 * <p><b>{@code GET}</b>, because it reads and writes nothing — it issues {@code select} and only
 * {@code select} — and so may be pressed as often as anybody likes against production. It is
 * deliberately not cached and stores nothing: two runs a minute apart may legitimately differ,
 * because the unified model is live and being written the whole time.
 *
 * <h2>{@code qits:admin} alone, and the class is deliberately outside {@code AgentReadAccessTest}</h2>
 *
 * <p>This is a <b>GET that an agent does not get</b>, which is the only such route in this service,
 * so the exception is argued rather than assumed. The user's standing ruling is that an agent keeps
 * every read; {@code AgentReadAccessTest} enforces it per controller class from an explicit list,
 * and this class is not on that list. Three reasons, and each one alone would be enough:
 *
 * <ul>
 *   <li><b>It is an operator's gate on a destructive migration.</b> Its whole purpose is to be the
 *       evidence a person acts on before dropping four tables. A reader of it is somebody deciding
 *       that, and no agent workflow on this platform decides it.
 *   <li><b>It reads whole frozen tables</b> — sixteen set-based scans of the whole estate per
 *       press. Every other read an agent holds here is a bounded, indexed query about the agent's
 *       own work; this one is the most expensive read in the service and is bound to nothing.
 *   <li><b>Nothing an agent does is served by it.</b> An agent plans, implements and reports on
 *       work; it has no use for a column-by-column comparison of a copy it never saw, and offering
 *       one would be offering a surface whose only possible use is confusion.
 * </ul>
 *
 * <p>The exclusion is recorded in {@code AgentReadAccessTest}'s own javadoc, naming this class and
 * this reason, because a class silently absent from that list and a class deliberately absent from
 * it look identical — and the second is the truth here. No assertion in that test moved.
 */
@Path("/entities/migration-verification")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class MigrationVerificationController {

  @Inject MigrationVerificationService verification;

  /**
   * Runs the comparison now and answers the whole report.
   *
   * <p>It returns the record type rather than a bare {@link jakarta.ws.rs.core.Response}, so the
   * native build indexes the shape — {@code PinsController}'s rule, and the failure it prevents
   * (a binary that serialises nothing while the JVM suite stays green) is invisible to every test
   * here by construction.
   */
  @GET
  @Operation(
      summary =
          "Compare the four frozen legacy planning tables against the unified entity model,"
              + " on this process's own database")
  @APIResponse(
      responseCode = "200",
      description =
          "The comparison. `verdict` is CLEAN or DISCREPANCIES; `scope` states what was"
              + " deliberately not checked; every category carries what it compared, even when it"
              + " found nothing.")
  public VerificationReport verify() {
    return verification.verify();
  }
}
