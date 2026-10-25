package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>One line of a desk trigger window's message</b> (epic qits-1604, V57): an item one source said
 * the desk should act on — {@link #itemRef} as the desk is shown it, {@link #label} in brackets
 * after it, {@link #cause} who or what caused it.
 *
 * <p>Insert-only. {@code (window_id, source, item_ref, label)} is unique and the insert is {@code on
 * conflict do nothing}, so the same item reached twice in one window, or one frame delivered twice,
 * is one line.
 *
 * <p>{@link Uncaused}: {@link DeskTriggerWindow}'s reasoning.
 */
@Entity
@Table(name = "desk_trigger_event")
@Uncaused
public class DeskTriggerEvent extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "window_id", nullable = false)
  public UUID windowId;

  /** The {@code DeskTriggerSource#id()} that admitted it. */
  @Column(nullable = false)
  public String source;

  @Column(name = "item_ref", nullable = false)
  public String itemRef;

  @Column(name = "item_id")
  public String itemId;

  @Column public String label;

  @Column public String cause;

  /** The bus event that carried it. */
  @Column(name = "event_id", nullable = false)
  public UUID eventId;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  @Column(name = "admitted_at", nullable = false)
  public Instant admittedAt;
}
