package eu.wohlben.qits.projects.contracts.consumer;

import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.targethost.HttpSystemLeases;
import eu.wohlben.qits.projects.targethost.SystemBearer;
import eu.wohlben.qits.projects.targethost.SystemLeases;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>What qits-projects asks qits-system, and why</b> (epic qits-1481, qits-1551): a front desk's
 * DESK lease, through {@link HttpSystemLeases}. Bound to the states qits-system records for its
 * DESK tenant under this service's bearer — {@code a queued desk lease}, {@code a running desk
 * lease}, {@code an unused desk work volume} — and {@code a declared target} for a legacy desk's
 * pin.
 *
 * <ul>
 *   <li>{@code createLease} — {@code DeskLeases.start}: a wanted desk with no lease;
 *   <li>{@code getLease}, {@code listLeases} — the reconcile ({@code TargetLeaseEvents});
 *   <li>{@code replaceLeaseSpec} — a roll ({@code FrontDeskSpecRoll});
 *   <li>{@code endLease} — a desk desired stopped, and a removal;
 *   <li>{@code purgeWorkVolume} — a removed desk's volume, once its lease is ENDED;
 *   <li>{@code listTargets} — a legacy desk's first lease, pinned to the target on its old node.
 * </ul>
 *
 * <p>The create asks for a desk the state does not hold a lease for (qits-system holds one live
 * lease per owner ref): the project id below, as the recording asked for it.
 */
final class SystemContract {

  static final String PROVIDER = "qits-system-service";
  static final String APP = "qits-system";

  static final String A_QUEUED_DESK_LEASE = "a queued desk lease";
  static final String A_RUNNING_DESK_LEASE = "a running desk lease";
  static final String AN_UNUSED_DESK_WORK_VOLUME = "an unused desk work volume";
  static final String A_DECLARED_TARGET = "a declared target";

  /** The desk the create asks a lease for. */
  static final String NEW_DESK = "00000000-0000-4000-8000-000000000004";

  private static final Map<String, String> AUTH = Map.of("Authorization", "{authorization}");

  private static final String SPEC =
      "{\"image\":\"registry.qits.wohlben.eu/qits/project-agent:2026.1\","
          + "\"env\":{\"QITS_TOKEN\":\"qits_tok_lease\"},\"user\":\"1000\","
          + "\"limits\":{\"memory\":\"8g\"}}";

  /** The lease answer's fields this service reads; {@code detail} and {@code targetId} may be null. */
  private static final List<String> LEASE =
      List.of("$.id", "$.ownerRef", "$.slotType", "$.state", "$.targetId", "$.detail", "$.specHash");

  private static final List<String> LEASES =
      List.of(
          "$[*].id",
          "$[*].ownerRef",
          "$[*].slotType",
          "$[*].state",
          "$[*].targetId",
          "$[*].detail",
          "$[*].specHash",
          "$[*].requestedAt");

  private static final Trigger START = Trigger.schedule("DeskLeases.start");
  private static final Trigger RECONCILE = Trigger.schedule("TargetLeaseEvents.reconcile");
  private static final Trigger ROLL = Trigger.schedule("FrontDeskSpecRoll.roll");
  private static final Trigger STOP = Trigger.operation("stopAgentContainer");
  private static final Trigger REMOVE = Trigger.operation("deleteAgentContainer");

  private SystemContract() {}

  /** {@link HttpSystemLeases} against the mock server, its bearer the state's own. */
  private static SystemLeases leases(String base, Map<String, String> p) {
    String authorization = p.get("authorization");
    SystemBearer bearer = () -> Optional.ofNullable(authorization);
    return Fields.with(
        new HttpSystemLeases(RowClients.clients()), "systemUrl", base, "bearer", bearer);
  }

  private static SystemLeases.LeaseSpec spec() {
    return new SystemLeases.LeaseSpec(
        "registry.qits.wohlben.eu/qits/project-agent:2026.1",
        Map.of("QITS_TOKEN", "qits_tok_lease"),
        "1000",
        new SystemLeases.Limits("8g", null, null, null, null));
  }

  private static UUID lease(Map<String, String> p) {
    return UUID.fromString(p.get("leaseId"));
  }

  private static ConsumerRow row(
      String operationId,
      String state,
      String method,
      String path,
      String body,
      List<String> consumes,
      int status,
      Trigger trigger,
      ConsumerRow.Call call,
      String needs) {
    return new ConsumerRow(
            PROVIDER, APP, operationId, state, method, path, Map.of(),
            body == null ? null : json(body), consumes, status, trigger, call, needs)
        .withHeaders(AUTH);
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          row(
                  "createLease",
                  A_QUEUED_DESK_LEASE,
                  "POST",
                  "/system/api/leases",
                  "{\"slotType\":\"DESK\",\"ownerRef\":\"" + NEW_DESK + "\",\"workKey\":\"desk-"
                      + NEW_DESK + "\",\"priority\":0,\"spec\":" + SPEC + "}",
                  LEASE,
                  201,
                  START,
                  (base, p) -> {
                    SystemLeases.Lease lease =
                        leases(base, p)
                            .create(new SystemLeases.LeaseRequest(NEW_DESK, spec(), null, null));
                    assertNotNull(lease.id());
                    assertEquals(SystemLeases.LeaseState.QUEUED, lease.state());
                  },
                  "POST /system/api/leases answering 201 with the QUEUED lease")
              .withNullable("$.targetId", "$.detail"),
          row(
                  "getLease",
                  A_QUEUED_DESK_LEASE,
                  "GET",
                  "/system/api/leases/{leaseId}",
                  null,
                  LEASE,
                  200,
                  RECONCILE,
                  (base, p) ->
                      assertEquals(
                          SystemLeases.LeaseState.QUEUED,
                          leases(base, p).get(lease(p)).orElseThrow().state()),
                  "GET /system/api/leases/{leaseId} answering 200 with the lease")
              .withNullable("$.targetId", "$.detail"),
          row(
                  "getLease",
                  A_RUNNING_DESK_LEASE,
                  "GET",
                  "/system/api/leases/{leaseId}",
                  null,
                  LEASE,
                  200,
                  RECONCILE,
                  (base, p) -> {
                    SystemLeases.Lease lease = leases(base, p).get(lease(p)).orElseThrow();
                    assertEquals(SystemLeases.LeaseState.RUNNING, lease.state());
                    assertNotNull(lease.targetId());
                  },
                  "GET /system/api/leases/{leaseId} answering 200 with the RUNNING lease")
              .withNullable("$.detail"),
          row(
                  "listLeases",
                  A_RUNNING_DESK_LEASE,
                  "GET",
                  "/system/api/leases",
                  null,
                  LEASES,
                  200,
                  RECONCILE,
                  (base, p) -> assertFalse(leases(base, p).list().isEmpty()),
                  "GET /system/api/leases answering 200 with this service's leases")
              .withNullable("$[*].targetId", "$[*].detail"),
          row(
                  "replaceLeaseSpec",
                  A_RUNNING_DESK_LEASE,
                  "PUT",
                  "/system/api/leases/{leaseId}/spec",
                  "{\"spec\":" + SPEC + "}",
                  LEASE,
                  200,
                  ROLL,
                  (base, p) ->
                      assertNotNull(leases(base, p).replaceSpec(lease(p), spec()).specHash()),
                  "PUT /system/api/leases/{leaseId}/spec answering 200 with the rolled lease")
              .withNullable("$.detail"),
          row(
                  "endLease",
                  A_RUNNING_DESK_LEASE,
                  "DELETE",
                  "/system/api/leases/{leaseId}",
                  null,
                  List.of("$.id", "$.state"),
                  200,
                  STOP,
                  (base, p) -> assertTrue(leases(base, p).end(lease(p)).isPresent()),
                  "DELETE /system/api/leases/{leaseId} answering 200 with the lease ENDING"),
          row(
                  "endLease",
                  A_QUEUED_DESK_LEASE,
                  "DELETE",
                  "/system/api/leases/{leaseId}",
                  null,
                  List.of("$.id", "$.state"),
                  200,
                  REMOVE,
                  (base, p) ->
                      assertEquals(
                          SystemLeases.LeaseState.ENDED,
                          leases(base, p).end(lease(p)).orElseThrow().state()),
                  "DELETE /system/api/leases/{leaseId} answering 200 with a queued lease ENDED"),
          row(
              "purgeWorkVolume",
              AN_UNUSED_DESK_WORK_VOLUME,
              "DELETE",
              "/system/api/work-volumes/{workKey}",
              null,
              List.of(),
              202,
              REMOVE,
              (base, p) -> assertTrue(leases(base, p).purgeWorkVolume(p.get("workKey"))),
              "DELETE /system/api/work-volumes/{workKey} answering 202"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listTargets",
              A_DECLARED_TARGET,
              "GET",
              "/system/api/targets",
              Map.of(),
              null,
              List.of("$[*].id", "$[*].name", "$[*].hostname", "$[*].engineId"),
              200,
              START,
              (base, p) -> {
                List<SystemLeases.Target> targets = leases(base, p).targets();
                assertFalse(targets.isEmpty());
                assertNotNull(targets.get(0).name());
              },
              "GET /system/api/targets answering 200 with id, name, hostname and engineId")
          .withNullable("$[*].hostname", "$[*].engineId"));
}
