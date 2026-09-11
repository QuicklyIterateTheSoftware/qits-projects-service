package eu.wohlben.qits.epics.dto;

/**
 * A live workspace that is working on this ticket or epic, as the browser reads it.
 *
 * <p><b>Derived at read time and stored nowhere.</b> The row it hangs off holds no pointer to a
 * workspace: the workspace carries the reference, and this is what the answer to "which ones name
 * this row?" looks like on the wire. A workspace that is integrated or discarded simply stops
 * appearing, with nothing here to clear.
 *
 * <p><b>Why this record lives in the epics module.</b> It is data and nothing else — the module
 * still depends on no context that knows what a workspace is, and the lookup that fills it happens a
 * layer up, where crossing is allowed. Putting the shape beside the DTO that carries it keeps the
 * read model in one place.
 *
 * @param workspaceRowId the workspace's row id at qits-workspaces
 * @param repositoryId the repository it belongs to. The pair is the whole address: that application
 *     routes a workspace as {@code repositories/{repositoryId}/workspaces/{workspaceRowId}}
 * @param workspaceId the workspace's label — what a link says when it names one
 * @param branch the branch it owns
 */
public record WorkspaceReferenceDto(
    long workspaceRowId, String repositoryId, String workspaceId, String branch) {}
