package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * A release as ONE pipeline: the phases it is made of, and the gates between them.
 *
 * <p><b>This block reports; it decides nothing.</b> Every gate in it was already decided by {@code
 * ReleaseGates} and the paths that answer it, and every phase in it is read off {@code
 * release_pipeline_run}, which is a mirror of qits-ci's own run rows. Nothing here is a new fact and
 * nothing downstream may treat it as one — in particular there is deliberately <b>no pipeline status
 * word</b>. What a reader wants to know is what the release is waiting on, and that is the first
 * unfinished phase together with the gate in front of it, both of which are already in the two lists.
 * A single summarising word would be a third answer free to disagree with them.
 *
 * <p><b>{@code phases} is in pipeline order and is as long as the pipeline has got.</b> QA, then
 * publish, then deploy — a phase that does not exist yet is simply not in the list, because the
 * honest statement about a release whose tag has not been cut is that its publish phase has not
 * begun rather than that it is pending. The list therefore grows as the release proceeds, and a
 * caller reads position rather than counting on a length.
 *
 * <p><b>{@code gates} carries only the gates the repository configures</b>, exactly as {@code
 * ReleaseRequestDto.gates} does and out of the same answers — this list is that one <em>placed</em>
 * between the phases it separates, never re-decided. A repository that configures no gate has an
 * empty list here and is not waiting on anything, which is not the same as a gate quietly in
 * progress.
 *
 * <p><b>The two lists are complements and neither is derivable from the other.</b> A phase says what
 * ran; a gate says what has to be true before the next phase may start. A red gate on a finished
 * phase is the ordinary state of a release that is stuck, and it is visible only because both are
 * reported.
 */
public record ReleasePipelineDto(
    List<ReleasePhaseDto> phases, List<ReleasePipelineGateDto> gates) {}
