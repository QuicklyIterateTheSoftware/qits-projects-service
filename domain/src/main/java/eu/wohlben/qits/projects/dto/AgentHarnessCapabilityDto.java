package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * What one harness can be configured with, as the editor's dropdowns read it.
 *
 * <p>One entry per harness, not per (harness, image version) pair: the store keeps a row for every
 * pair it has been told about, and this is the <em>newest</em> of them, saying which image version it
 * came from. Reports that disagree across image versions are not merged — a union of two builds'
 * model lists is a set no binary actually offers — so the ones that lost are named in {@link
 * #otherImageVersions} and nothing of theirs is folded in.
 *
 * <p><b>The three booleans each mean something an empty list does not.</b> {@code modelsEnumerated}
 * false says the harness has no listing command and this is a shipped alias set (Claude Code), so
 * the editor leads with the free-text escape rather than treating the dropdown as exhaustive.
 * {@code effortSupported} false says the harness has no effort concept at all (Kimi), so the editor
 * shows no effort control — not a disabled one carrying the other harness's values. {@code
 * probeFailed} true says these lists are a fallback rather than a reading of the binary.
 *
 * <p><b>{@code shipped} true is the fresh-estate answer.</b> Nothing has reported yet, this is the
 * library's shipped fallback, and every other field describes that fallback rather than any binary —
 * {@code imageVersion} is empty, {@code reportedAt} is null. The editor works before a single
 * container has started, which is the point of having a fallback at all.
 *
 * <p>{@code authenticated} rides here because auth is a property of the harness and the credential
 * volume rather than of a surface, and belongs in the same answer as the rest of "what can this
 * harness do right now".
 *
 * @param harness {@code CLAUDE} or {@code KIMI}
 * @param imageVersion the image build this report came from; empty on a shipped fallback
 * @param harnessVersion what the binary answers for {@code --version}; empty when unread
 * @param models the model ids or aliases, in the order they were enumerated
 * @param modelsEnumerated false when {@code models} is a shipped alias set
 * @param effortSupported false when the harness has no effort flag at all
 * @param effortLevels the effort levels; empty when {@code effortSupported} is false
 * @param authenticated whether anybody is signed in on the reporting container's credential volume
 * @param authDetail what the harness said about that; never a credential
 * @param probeFailed whether the report fell back rather than reading the binary
 * @param probeDetail why it fell back; empty when it did not
 * @param reportedBy free text naming the reporting container; display only
 * @param reportedAt when the report arrived, ISO-8601; null on a shipped fallback
 * @param shipped true when nothing has reported and this is the library's fallback
 * @param otherImageVersions the image versions that also reported this harness and did not win,
 *     newest first — named rather than merged
 */
public record AgentHarnessCapabilityDto(
    String harness,
    String imageVersion,
    String harnessVersion,
    List<String> models,
    boolean modelsEnumerated,
    boolean effortSupported,
    List<String> effortLevels,
    boolean authenticated,
    String authDetail,
    boolean probeFailed,
    String probeDetail,
    String reportedBy,
    String reportedAt,
    boolean shipped,
    List<AgentCapabilityImageVersionDto> otherImageVersions) {}
