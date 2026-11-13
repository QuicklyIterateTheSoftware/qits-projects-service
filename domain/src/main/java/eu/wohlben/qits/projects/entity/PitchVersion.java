package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One version of a {@link Pitch}'s HTML (qits-2155, V77): <b>append-only</b>. Version 1 is written
 * with the pitch and each later one is {@code n+1}; none is ever rewritten or deleted, so a reader
 * may pick any of them to compare with the original.
 *
 * <p><b>It is never served as a page.</b> The document is agent-authored and is not sanitised here;
 * it travels only as a JSON field, and its consumer renders it in a sandboxed, script-free frame —
 * the rule {@link RefinementDesign} states for its own bytes.
 *
 * <p>A {@link CausedRow}, like its pitch: a version is appended on the request thread of the
 * agent's call.
 */
@Entity
@Table(name = "pitch_version")
@IdClass(PitchVersion.Key.class)
@EntityListeners(CausationStamp.class)
public class PitchVersion extends PanacheEntityBase implements CausedRow {

  @Id
  @Column(name = "pitch_id", nullable = false)
  public UUID pitchId;

  /** From 1. */
  @Id
  @Column(name = "number", nullable = false)
  public int number;

  /** The whole document, styles inline. */
  @Column(name = "html", nullable = false, columnDefinition = "text")
  public String html;

  /** UTF-8 length of {@link #html}: what the size limit counts and a listing shows. */
  @Column(name = "html_bytes", nullable = false)
  public int htmlBytes;

  /** What changed, as the agent says it. */
  @Column(name = "note", columnDefinition = "text")
  public String note;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

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

  /** The composite key. */
  public static class Key implements Serializable {
    public UUID pitchId;
    public int number;

    public Key() {}

    public Key(UUID pitchId, int number) {
      this.pitchId = pitchId;
      this.number = number;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key && number == key.number && Objects.equals(pitchId, key.pitchId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(pitchId, number);
    }
  }
}
