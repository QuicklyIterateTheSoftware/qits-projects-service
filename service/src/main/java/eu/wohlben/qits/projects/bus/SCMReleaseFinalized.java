package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>The default branch has this release.</b> A released tag reached {@code main}, and {@code main}
 * now names the released content.
 *
 * <p><b>The moment nothing on this platform announced.</b> Main moves exactly two ways here: a push,
 * which qits-githost announces from post-receive as {@code SCMPublishCommit}, and a release, which
 * until this event announced nothing at all. A release is a tag ({@link SCMRelease}), the artifacts
 * follow it (qits-ci's {@code SoftwareRelease}), the deployment follows those — and only then does
 * {@code ReleaseFinalization} merge the released sha into the default branch, through qits-githost's
 * REST merge door, which is documented to publish nothing. So the one statement a consumer needed
 * in order to know that a repository's files had changed was the one nobody made.
 *
 * <p><b>What that cost, concretely.</b> qits-platform-maintenance re-reads a repository's manifests
 * when it hears main move; it never heard, so a repository's pins were only ever as fresh as the
 * last nightly scan. A dependency bump would land, release, and still read as pending against the
 * version it had just been raised to — the inventory reporting a repository as behind on the very
 * dependency it had itself brought up to date. Measured live on 2026-09-09.
 *
 * <p><b>Why not one of the three events that already exist.</b> Each is true at a different instant
 * and none of them is this one:
 *
 * <ul>
 *   <li>{@link SCMRelease} is the TAG, and it is published <em>before</em> the release pipeline
 *       runs. Main has not moved and will not for minutes. A consumer scanning main on it reads the
 *       pre-release head and records it as current, which is worse than not scanning;
 *   <li>qits-ci's {@code SoftwareRelease} is the ARTIFACT, one per published package. It is later
 *       than the tag and still earlier than this — the deployment has not happened, so neither has
 *       the merge — and it is <b>absent entirely for a repository that publishes nothing</b>. Every
 *       {@code *-frontend} on this platform ships inside a service's gitlink and declares no
 *       artifact, so a consumer keyed on it is blind to exactly the repositories whose manifests
 *       move most often;
 *   <li>{@link ReleaseRequestChanged} is announced on a FOLD, when a request's sha changes. It says
 *       what is queued, never what landed.
 * </ul>
 *
 * <p>That the other three are archetype-shaped is right rather than a defect — a gitlink consumer
 * genuinely only needs the tag, and an npm consumer genuinely needs the registry publish. This event
 * is the one fact that is the same for every archetype, because every repository has a default
 * branch and every release ends by moving it.
 *
 * <p><b>{@code mergedSha} is what the target ref names now</b>, which is not always the released sha:
 * a fold onto a branch that moved underneath is a merge commit, and {@code UNCHANGED} — the target
 * already contained the release — answers the sha that was already there. It is the value a consumer
 * compares its own recorded head against, so it is the merge's answer and never the tag's.
 *
 * <p><b>Published after the merge is accepted and never conditionally on anything after it</b>, the
 * {@link SCMRelease} rule: the ref has moved the instant qits-githost answers, so an announcement
 * gated on the bookkeeping that follows would be silent about a merge that really happened.
 *
 * <p><b>Nothing here re-states the release.</b> A consumer that wants the version has {@link
 * SCMRelease}; {@code version} rides along because a log line naming the tag is worth more than one
 * naming a sha, not because this event is a second release announcement. Consuming both is expected:
 * they are two different facts about one release.
 *
 * <p>{@code eventId} and {@code occurredAt} are {@link QitsEvent} components and stay out of the
 * payload, so the wire is exactly {@code mergedSha}, {@code projectId}, {@code repository}, {@code
 * repositoryName}, {@code target}, {@code version}. It lives in {@code service/…/bus/} rather than a
 * published vocabulary module — the {@link RepositoryRenamed} ruling — and is registered in {@link
 * EventWireReflection}, without which every announcement dies inside the publish on the native
 * binary while the JVM suite stays green.
 *
 * @param projectId the project the repository belongs to, as qits-projects names it
 * @param repository the repository whose default branch moved, by string id
 * @param repositoryName the same repository by its registered name — the stable coordinate, and the
 *     one a consumer keyed by name can address. Nullable: a repository with no alias costs the event
 *     a field, never the merge
 * @param target the ref that moved, fully qualified ({@code refs/heads/main}). Spelled rather than
 *     assumed, because a repository's default branch is a per-repository fact and a consumer
 *     comparing against its own idea of "main" must be able to see which ref this was
 * @param version the release stamp whose tag was merged — the tag's name
 * @param mergedSha what {@code target} names now
 * @param occurredAt when the git host accepted the merge
 */
public record SCMReleaseFinalized(
    UUID eventId,
    String projectId,
    String repository,
    String repositoryName,
    String target,
    String version,
    String mergedSha,
    Instant occurredAt)
    implements QitsEvent {

  public SCMReleaseFinalized {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SCMReleaseFinalized(
      String projectId,
      String repository,
      String repositoryName,
      String target,
      String version,
      String mergedSha,
      Instant occurredAt) {
    this(null, projectId, repository, repositoryName, target, version, mergedSha, occurredAt);
  }
}
