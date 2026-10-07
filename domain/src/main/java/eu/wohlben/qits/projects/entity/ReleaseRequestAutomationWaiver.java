package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One <b>person's waiver</b> of the automations gate for one fold of a release request (epic
 * qits-978) — the escape for the day qits-maintenance itself is broken and the fix for it is the very
 * request its automations are holding.
 *
 * <p><b>A waiver is about a sha, not about a request</b>, on exactly {@link ReleaseRequestApproval}'s
 * terms: the row carries the {@link ReleaseRequest#mergedSha} it was made against, so any re-fold
 * leaves it behind for free and the gate holds again for content nobody waived. The gate reads
 * whether any row names the request's <em>current</em> {@code mergedSha}.
 *
 * <p><b>Insert-only.</b> Nothing updates or deletes a row: it is the record that a named person let a
 * named fold through without its regenerations, and that record is worth more than the request row
 * it is about, which is why nothing foreign-keys it there either.
 *
 * <p>A {@link CausedRow}: every insert happens on the request thread of the person waiving.
 */
@Entity
@Table(name = "release_request_automation_waiver")
@EntityListeners(CausationStamp.class)
public class ReleaseRequestAutomationWaiver extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** The request waived for. A plain column: the parent is loaded by id, never joined. */
  @Column(name = "request_id", nullable = false)
  public String requestId;

  /** The fold waived — the request's {@link ReleaseRequest#mergedSha} at the moment. Never null. */
  @Column(name = "merged_sha", nullable = false)
  public String mergedSha;

  /** Who waived — the verified person, the point of the whole row. */
  @Column(nullable = false)
  public String actor;

  /** Why they did. Required: a waiver nobody can explain later is the one worth refusing. */
  @Column(nullable = false)
  public String reason;

  @Column(name = "waived_at", nullable = false)
  public Instant waivedAt;

  /** The platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
