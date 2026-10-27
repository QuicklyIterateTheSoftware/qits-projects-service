package eu.wohlben.qits.projects.targethost;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * qits-system's {@code TargetLeaseChanged} (epic qits-1481), transcribed rather than depended on —
 * the wire contract, not a type, crosses from another context. Spelled exactly as qits-system's
 * ({@code qits-system-service/targets/.../events/TargetLeaseChanged.java}) and qits-workspaces'
 * copy: its simple name is the event's name on the bus, and its components are the payload's keys.
 *
 * <p>Bound by {@link TargetLeaseEvents} through the bus's own mapper, so {@code
 * bus/EventWireReflection} registers it for native-image: without that line the JVM suite is green
 * and the binary cannot read one.
 *
 * <p>Published on every state change of a lease and on every change of a queued lease's {@code
 * detail}. {@code targetId} is null while QUEUED, {@code exitCode} is set for EXITED, {@code
 * detail} names why a lease waits or failed.
 */
public record TargetLeaseChanged(
    UUID eventId,
    String leaseId,
    String ownerApp,
    String ownerRef,
    String slotType,
    String targetId,
    String state,
    Integer exitCode,
    String detail,
    Instant occurredAt)
    implements QitsEvent {}
