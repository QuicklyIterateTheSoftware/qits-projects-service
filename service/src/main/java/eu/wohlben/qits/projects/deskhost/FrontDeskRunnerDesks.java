package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.control.DeskRunnerDesks;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The desks placed on a runner (qits-767), read from {@code front_desk}: a runner's listing shows
 * them, and its delete is refused 409 {@code RUNNER_OWNS_DESKS} while there are any. Runs inside
 * the caller's transaction, as the port requires. Wins over the domain's {@code @DefaultBean}
 * "owns none".
 */
@ApplicationScoped
public class FrontDeskRunnerDesks implements DeskRunnerDesks {

  @Inject FrontDesks frontDesks;

  @Inject ProjectRepository projects;

  @Override
  public List<DeskRunnerDto.DeskRunnerDesk> desksOn(UUID runnerId) {
    List<DeskRunnerDto.DeskRunnerDesk> desks = new ArrayList<>();
    Instant now = Instant.now();
    for (FrontDesk row : frontDesks.desksOn(runnerId)) {
      Project project = projects.findById(row.projectId);
      String state =
          project == null ? null : frontDesks.state(project, row, now).runtimeStatus().name();
      desks.add(
          new DeskRunnerDto.DeskRunnerDesk(
              row.projectId, project == null ? null : project.slug, state));
    }
    return List.copyOf(desks);
  }
}
