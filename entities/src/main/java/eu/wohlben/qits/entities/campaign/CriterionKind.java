package eu.wohlben.qits.entities.campaign;

/**
 * <b>The four kinds of fact a campaign member can wait on</b> (V19), stored as its name behind
 * {@code ck_campaign_criterion_kind}. Each has exactly one {@link CriterionPredicate} shape.
 *
 * <ul>
 *   <li>{@link #ENTITY_STATUS} — another member of the same campaign reaches a status ({@code
 *       EntityTransitioned}).
 *   <li>{@link #DEPLOYMENT_ACTIVE} — an application goes live, optionally in one environment and at
 *       least at a version ({@code DeploymentActive}).
 *   <li>{@link #SCM_RELEASE} — a repository releases, optionally at least at a version ({@code
 *       SCMRelease}).
 *   <li>{@link #APPROVAL} — a person says yes. It never matches an event and is latched only by the
 *       approve door.
 * </ul>
 *
 * <p>A fifth kind is a signature, a decoder, a predicate arm, an SPA form and a test, in one change
 * — and a migration, since the check constraint names these four.
 */
public enum CriterionKind {
  ENTITY_STATUS,
  DEPLOYMENT_ACTIVE,
  SCM_RELEASE,
  APPROVAL
}
