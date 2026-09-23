package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A project's editable facts changed: this id, this slug, this display name, this environment
 * answer, as of this moment.
 *
 * <p><b>Why this exists beside {@link ProjectCreated}.</b> A creation states what was true when the
 * project came into existence, and the field it is really about — {@code slug}, which the platform
 * edge derives {@code *.<slug>.<domain>} from — is {@code @Column(updatable = false)}, so it never
 * needs restating. {@code supportsEnvironments} is the opposite kind of fact: it is declared in the
 * project's wrapper at {@code .config/qits/project.yml}, and that file can be committed at any time,
 * years after the create. {@code ProjectCreated} cannot say so — re-publishing it would tell every
 * consumer that a project it has known about for a year has just been created — so the change gets
 * a frame of its own.
 *
 * <p><b>The identity fields are restated, not omitted.</b> {@code projectId} is the key a consumer
 * holds its row by and {@code slug} is what it may have keyed its derivations on; carrying both
 * means a consumer can act on this frame without a lookup, and neither has changed nor can.
 *
 * <p><b>Published only when the stored value actually moves.</b> {@code WrapperReconcileService}
 * announces it after the transaction that moved it — a reconcile runs on a timer and on demand, so
 * an unconditional announcement would be a frame per pass saying nothing happened.
 *
 * <p>Every rule {@link ProjectCreated}'s javadoc states applies here unchanged and for the same
 * reasons: the display name is {@code projectName} and may never be called {@code name} (a
 * component of that name is silently dropped from every payload, because {@code CanonicalJson}'s
 * mix-in {@code @JsonIgnore}s {@code QitsEvent.name()} and Jackson matches a mix-in by accessor
 * name); {@code occurredAt} is when the change committed rather than when the publish was attempted;
 * {@code eventId} is a component and is kept out of the payload by the same mix-in; and it lives
 * here rather than in a published vocabulary module, so a consumer transcribes the field names by
 * hand into a local record and {@link ProjectLifecycleContractTest} is where they are pinned.
 *
 * <p><b>{@code supportsEnvironments} is a {@code Boolean} normalized to {@code TRUE}</b>, exactly as
 * on {@link ProjectCreated} — this frame is new, so no older one exists to read, but the two records
 * are transcribed together and a reader that handles one handles the other.
 *
 * <p>Registered for reflection in {@link EventWireReflection}, without which every publish of it
 * dies inside {@code CanonicalJson} on a native binary with the JVM suite green.
 */
public record ProjectChanged(
    UUID eventId,
    String projectId,
    String slug,
    String projectName,
    Boolean supportsEnvironments,
    Instant changedAt)
    implements QitsEvent {

  public ProjectChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    if (supportsEnvironments == null) {
      supportsEnvironments = Boolean.TRUE;
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public ProjectChanged(
      String projectId,
      String slug,
      String projectName,
      boolean supportsEnvironments,
      Instant changedAt) {
    this(null, projectId, slug, projectName, supportsEnvironments, changedAt);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
