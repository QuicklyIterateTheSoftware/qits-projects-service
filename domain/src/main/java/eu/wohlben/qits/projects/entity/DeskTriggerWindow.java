package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>One collection window of a project's desk triggers</b> (epic qits-1604, V57): what happened to
 * the project between {@link #openedAt} and {@link #closesAt}, sent to its delivery desk as one
 * prompt.
 *
 * <ul>
 *   <li><b>Opening</b> — the first trigger admitted for a project with no {@link
 *       DeskTriggerWindowState#OPEN OPEN} window opens one, closing a fixed window later; every later
 *       trigger joins it. {@link #closesAt} is never moved: a busy project is prompted on a clock,
 *       not when it goes quiet. At most one OPEN row per project (a partial unique index).
 *   <li><b>Closing</b> — the sweep renders {@link #message} from the window's {@link
 *       DeskTriggerEvent}s once {@link #closesAt} has passed, stamps {@link #closedAt} and makes it
 *       {@link DeskTriggerWindowState#OWED OWED}. The message is stored, so every retry sends the
 *       same text.
 *   <li><b>Delivering</b> — each attempt counts in {@link #attempts} and leaves {@link
 *       #lastStatus}/{@link #lastDetail}; an accepted one stamps {@link #acceptedAt} and {@link
 *       #commandId} (and {@link #queuePosition} when it was queued).
 * </ul>
 *
 * <p>{@link Uncaused}: written by a bus consumer and a sweep, where no causation scope stands —
 * {@code ReleaseRequestApprovalNotice}'s reasoning.
 */
@Entity
@Table(name = "desk_trigger_window")
@Uncaused
public class DeskTriggerWindow extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "project_id", nullable = false)
  public String projectId;

  @Column(name = "opened_at", nullable = false)
  public Instant openedAt;

  @Column(name = "closes_at", nullable = false)
  public Instant closesAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public DeskTriggerWindowState state;

  /** The exact text sent to the desk; null while the window is OPEN. */
  @Column public String message;

  @Column(name = "closed_at")
  public Instant closedAt;

  @Column(nullable = false)
  public int attempts;

  @Column(name = "last_attempt_at")
  public Instant lastAttemptAt;

  /** The HTTP status of the last attempt, as the front desk's prompt door would have answered it. */
  @Column(name = "last_status")
  public Integer lastStatus;

  /** The daemon's reason for the last refusal, or why it could not be reached. */
  @Column(name = "last_detail")
  public String lastDetail;

  @Column(name = "accepted_at")
  public Instant acceptedAt;

  /** The kept desk command the message was given to, from the daemon's answer. */
  @Column(name = "command_id")
  public String commandId;

  /** Its place in the desk's prompt queue, when it was queued. */
  @Column(name = "queue_position")
  public Integer queuePosition;
}
