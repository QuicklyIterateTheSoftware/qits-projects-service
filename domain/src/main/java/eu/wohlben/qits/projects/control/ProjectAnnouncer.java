package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the rest of the platform that a project came into existence or left it.
 *
 * <p>A port, for the reason every other reach out of {@code domain} is one: the announcement leaves
 * over {@code qits-eventstream}'s bus, and {@code domain} does not know the event bus exists. The
 * one implementation is {@code service/…/bus/ProjectLifecycleAnnouncer}.
 *
 * <p><b>What it is for.</b> A project's {@link
 * eu.wohlben.qits.projects.entity.Project#slug slug} is the first label of every host the platform
 * serves that project under — {@code *.<slug>.<domain>} — and the platform edge terminates TLS for
 * all of them. Nothing outside this context knows a project exists, so without these two events the
 * edge has no way to learn which SANs a certificate has to carry, or when one stops being owed. The
 * slug is the load-bearing field in both: it is what the names are derived from, and it is {@code
 * @Column(updatable = false)}, so a project announced once never needs re-announcing under a second
 * spelling.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here: with no implementation a
 * project is still created and still deleted and simply announces nothing, which is what {@code
 * domain}'s own suite runs as. Injected as an {@code Instance<T>} for that reason.
 *
 * <p><b>Nothing here may throw</b> and nothing here may be called inside a transaction the caller
 * needs. The bus's own {@code publish} is fire-and-forget and never throws — see {@code
 * QitsEventBus} — but the port states the rule anyway, because an announcement that could fail a
 * creation would make the platform's bookkeeping a way for the operation to fail, and a caller who
 * gets a 500 from a create that in fact succeeded is worse off than an edge that learns about the
 * project a boot later (which is what {@code ProjectAnnounceBackfill} is for).
 */
public interface ProjectAnnouncer {

  /**
   * A project exists. Announced after the creating transaction has committed, so a consumer that
   * reads the project back finds it.
   *
   * @param projectId the row's id — the platform's opaque handle on the project
   * @param slug the git-safe, immutable identity every host serving this project is named after;
   *     the field the edge derives {@code *.<slug>.<domain>} from
   * @param name the free-form display name, which may change and which nothing may be derived from.
   *     It travels as {@code projectName} on the wire and never as {@code name} — see {@code
   *     bus/ProjectCreated}, where the reason is a collision with {@code QitsEvent.name()} that
   *     drops the field silently.
   * @param supportsEnvironments whether the project's services are deployed once per environment —
   *     see {@link eu.wohlben.qits.projects.entity.Project#supportsEnvironments}. {@code true} is
   *     what every project meant before the flag existed, so a consumer reading a frame without the
   *     field reads {@code true}.
   * @param occurredAt when the creation committed — the event's {@code occurredAt}, not the moment
   *     the announcement is made
   */
  void onProjectCreated(
      String projectId, String slug, String name, boolean supportsEnvironments, Instant occurredAt);

  /**
   * A project's editable facts changed — today, exactly {@link
   * eu.wohlben.qits.projects.entity.Project#supportsEnvironments}.
   *
   * <p><b>Why a third verb rather than a second create.</b> {@code ProjectCreated} states what was
   * true when the project came into existence, and its load-bearing field — the slug — is {@code
   * updatable = false}, so it never needs restating. The environment flag is the opposite: it is
   * declared in the wrapper's {@code .config/qits/project.yml} and a commit to that file can change
   * it at any time, years after the create. Re-publishing a creation to say so would tell every
   * consumer a project it already knows about has just been created.
   *
   * <p>Announced by {@code WrapperReconcileService}, after the transaction that moved the stored
   * value and <b>only when it actually moved</b> — a reconcile runs on a timer, and a frame per
   * pass saying nothing changed is noise a consumer has to filter.
   *
   * @param projectId the row's id, unchanged and the key a consumer holds its own row by
   * @param slug the project's immutable identity, restated so a consumer that keyed on it can find
   *     its row without a second lookup
   * @param name the free-form display name; see {@link #onProjectCreated} for why it travels as
   *     {@code projectName}
   * @param supportsEnvironments the value as it now stands
   * @param occurredAt when the change committed
   */
  void onProjectChanged(
      String projectId, String slug, String name, boolean supportsEnvironments, Instant occurredAt);

  /**
   * A project is gone, with every repository that was under it. Announced after the deleting
   * transaction has committed, so a consumer that reads the project back does not find it.
   *
   * @param projectId the row's id, which now names nothing
   * @param slug the slug the project held — carried because it is what a consumer keyed its own
   *     derivation on, and after the delete there is nothing left to look it up from
   * @param occurredAt when the deletion committed — the event's {@code occurredAt}, not the moment
   *     the announcement is made
   */
  void onProjectDeleted(String projectId, String slug, Instant occurredAt);
}
