package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.EntityMembership;
import eu.wohlben.qits.epics.entity.WorkEntity;

/**
 * <b>A descendant row and the parent its {@link EntityMembership} edge names.</b> The answer shape
 * of {@link FeatureService} and {@link TaskService} — the two archetypes that cannot be roots —
 * where {@link EpicService} and {@link TicketService} answer a bare {@link WorkEntity}.
 *
 * <h2>Why the parent travels BESIDE the row rather than on it</h2>
 *
 * <p>{@code feature.epic_id} and {@code task.feature_id} were columns and are an {@code
 * entity_membership} row now: <b>the membership edge is the relation</b>, and a merged row carries
 * no parent id of its own. So a caller that wants one has to have read the edge — and the caller
 * <em>has</em> read it, every time: a listing reads the whole level's edges in one query, a create
 * writes the edge itself, and a get and an update resolve it once at the top of the method. Handing
 * the answer back beside the row costs nothing and asks nothing again.
 *
 * <p><b>The alternative is an N+1 on exactly the reads this model makes one easy on.</b> A row that
 * fetched its own membership would be a query per row on a tree listing — the single performance
 * mistake the unified table invites, and the one {@code FeatureService.listByEpic} and {@code
 * TaskService.listByFeature} are written as two queries to avoid. This is the argument the deleted
 * {@code WorkEntityProjections} carried ("The parent is passed in, never read from the row"), and it
 * survives the projections it was written for: what changed is that the row handed out is now the
 * merged row itself rather than a shape of it, and the parent still has nowhere on that row to sit.
 *
 * <p><b>Null is a real answer and not an error.</b> {@link #parentId} is null for a row whose edge
 * is missing — which the services treat the way they always did, by resolving the owning epic from
 * it and answering the 404 that resolution produces.
 *
 * @param entity the descendant row itself, of {@link Archetype#FEATURE} or {@link Archetype#TASK}
 * @param parentId the {@code parent_id} of that row's {@code entity_membership} edge — the epic of a
 *     feature, the feature of a task — or null when it has no edge
 */
public record Nested(WorkEntity entity, String parentId) {}
