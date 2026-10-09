package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.dto.ReleasePhaseDto;
import eu.wohlben.qits.projects.dto.ReleasePipelineDto;
import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Which run is the QA phase when a request re-folds. The old fold's run is cancelled, and its
 * CANCELLED frame can be newer than the new run's first frame (release request dd113f4c,
 * 2026-10-09). The QA phase is the run at the current fold, whatever order the frames came in.
 */
class ReleasePipelineAssemblerTest {

  private static final String OLD_FOLD = "57746d58";
  private static final String NEW_FOLD = "de19c2b1";

  private final ReleasePipelineAssembler assembler = new ReleasePipelineAssembler();

  @Test
  void theOldFoldsLateCancelDoesNotTakeTheQaPhaseFromTheNewFoldsRun() {
    // newest transition first, as the repository answers: the old run's cancel came last
    List<ReleasePipelineRun> runs =
        List.of(
            run("old", OLD_FOLD, "CANCELLED", "2026-10-09T17:15:34.946Z"),
            run("new", NEW_FOLD, "RUNNING", "2026-10-09T17:15:34.500Z"));

    ReleasePhaseDto qa = qa(runs, NEW_FOLD);

    assertEquals("new", qa.runId());
    assertEquals("RUNNING", qa.state());
  }

  @Test
  void theOldFoldsCancelBeforeTheNewRunStartsAlsoLeavesTheNewRun() {
    List<ReleasePipelineRun> runs =
        List.of(
            run("new", NEW_FOLD, "QUEUED", "2026-10-09T17:15:35Z"),
            run("old", OLD_FOLD, "CANCELLED", "2026-10-09T17:15:34Z"));

    assertEquals("new", qa(runs, NEW_FOLD).runId());
  }

  @Test
  void beforeTheNewFoldHasARunTheQaPhaseIsAbsentNotTheOldRun() {
    List<ReleasePipelineRun> runs =
        List.of(run("old", OLD_FOLD, "CANCELLED", "2026-10-09T17:15:34Z"));

    ReleasePipelineDto block = assembler.assemble(runs, List.of(), Map.of(), null, NEW_FOLD, LIST);

    assertTrue(block.phases().isEmpty(), "the old fold's run is not this fold's QA phase");
  }

  @Test
  void aRowWithNoRecordedCommitIsStillDrawnButLosesToARunAtTheFold() {
    ReleasePipelineRun legacy = run("legacy", null, "CANCELLED", "2026-10-09T17:15:34.946Z");

    assertEquals("legacy", qa(List.of(legacy), NEW_FOLD).runId());
    assertEquals(
        "new",
        qa(List.of(legacy, run("new", NEW_FOLD, "RUNNING", "2026-10-09T17:15:34Z")), NEW_FOLD)
            .runId());
  }

  @Test
  void aRetryAtTheSameFoldIsStillNewestFirst() {
    List<ReleasePipelineRun> runs =
        List.of(
            run("retry", NEW_FOLD, "SUCCESS", "2026-10-09T18:00:00Z"),
            run("first", NEW_FOLD, "FAILED", "2026-10-09T17:30:00Z"));

    assertEquals("retry", qa(runs, NEW_FOLD).runId());
  }

  private static final ReleasePipelineAssembler.DeployReach LIST =
      ReleasePipelineAssembler.DeployReach.LIST_READ;

  private ReleasePhaseDto qa(List<ReleasePipelineRun> runs, String foldSha) {
    ReleasePipelineDto block = assembler.assemble(runs, List.of(), Map.of(), null, foldSha, LIST);
    return block.phases().stream()
        .filter(phase -> ReleasePipelineAssembler.PHASE_QA.equals(phase.phase()))
        .findFirst()
        .orElseThrow();
  }

  private static ReleasePipelineRun run(String id, String sha, String status, String at) {
    ReleasePipelineRun run = new ReleasePipelineRun();
    run.runId = id;
    run.releaseRequestId = "req";
    run.repoId = "repo";
    run.phase = ReleasePipelineRuns.PHASE_RELEASE_REQUEST;
    run.commitSha = sha;
    run.status = status;
    run.updatedAt = Instant.parse(at);
    return run;
  }
}
