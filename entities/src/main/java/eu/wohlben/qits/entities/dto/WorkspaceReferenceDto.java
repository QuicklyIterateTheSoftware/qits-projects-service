package eu.wohlben.qits.entities.dto;

/**
 * A workspace that was cut for this ticket or epic, as the browser reads it — <b>live or
 * resolved</b>, and saying which.
 *
 * <p><b>Derived at read time and stored nowhere.</b> The row it hangs off holds no pointer to a
 * workspace: the workspace carries the reference, and this is what the answer to "which ones name
 * this row?" looks like on the wire.
 *
 * <p><b>A resolved workspace stays on the list, and that is the point of {@link #status}.</b> It
 * used to drop off the moment it was integrated or abandoned, which took the link to where the work
 * actually happened — the transcripts, the diff, the session — with it. So the row keeps its place
 * and carries the word instead, and the reader draws it for what it is. Nothing here is a claim that
 * somebody is working: a list of one {@code REMOVED} agent means nobody is on it.
 *
 * <p><b>Why this record lives in the entities module.</b> It is data and nothing else — the module
 * still depends on no context that knows what a workspace is, and the lookup that fills it happens a
 * layer up, where crossing is allowed. Putting the shape beside the DTO that carries it keeps the
 * read model in one place.
 *
 * <p>Since qits-1152 each row is an <b>agent</b> of the work item: agents are keyed by work item and
 * live in a workspace once placed. The shape kept its name and members so the browser's reading of
 * it did not move; {@code agentId} is new.
 *
 * @param agentId the agent's id at qits-workspaces
 * @param workspaceRowId the row id of the workspace the agent is placed in, or {@code null} while it
 *     is QUEUED for a slot
 * @param repositoryId the wrapper repository. The pair is the address: that application routes a
 *     workspace as {@code repositories/{repositoryId}/workspaces/{workspaceRowId}}
 * @param workspaceId a label for a link — the work item's qualified id, or {@code null}
 * @param branch the agent's wrapper branch
 * @param status the agent's state — {@code ACTIVE}, {@code OBSOLETE} or {@code REMOVED}. A string and
 *     not an enum: it is qits-workspaces' vocabulary, and a word this platform has never heard of
 *     must reach the browser rather than fail the read that carries it
 */
public record WorkspaceReferenceDto(
    String agentId,
    Long workspaceRowId,
    String repositoryId,
    String workspaceId,
    String branch,
    String status) {}
