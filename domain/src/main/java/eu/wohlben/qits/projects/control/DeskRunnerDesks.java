package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import java.util.List;
import java.util.UUID;

/**
 * Which project front desks are placed on a runner (qits-767): what a runner's listing shows as its
 * {@code desks}, and what refuses its delete with 409 {@code RUNNER_OWNS_DESKS} while it is not
 * empty — a desk is sticky to its runner, so the runner cannot go before its desks do.
 *
 * <p>A port, because the {@code front_desk} table it reads arrives with the front-desk task; until
 * then {@link NoDeskRunnerDesks} answers "owns none". {@link #desksOn} is called inside the delete's
 * own transaction, so an implementation reads the rows it judges in that transaction.
 */
public interface DeskRunnerDesks {

  /** The desks on {@code runnerId}, or an empty list. Never null. */
  List<DeskRunnerDto.DeskRunnerDesk> desksOn(UUID runnerId);
}
