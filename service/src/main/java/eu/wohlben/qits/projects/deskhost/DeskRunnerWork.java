package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import java.util.List;

/**
 * The front desk's half of a runner's socket (qits-767): what {@link DeskRunnerRegistry} asks once a
 * runner may be given work, and where the desk frames it does not handle itself go. {@link
 * FrontDeskWork} implements it: the estate, {@code take}, {@code remove}, the inventory.
 *
 * <p><b>The quarantine gate is the registry's, not this port's.</b> {@link #reserve} is asked only
 * for a session greeted at the pin, not draining, of a runner in service: a quarantined runner, one
 * still being upgraded or one not yet greeted is answered {@code nothing} without asking.
 */
public interface DeskRunnerWork {

  /**
   * The answer to a {@code reserve} from a runner that may take work now: a {@code take} for one
   * desk, or {@code nothing}. Never null.
   */
  RunnerMessage reserve(DeskRunnerRegistry.Session session);

  /**
   * How many desks the runner of {@code session} could be given now: the {@code backlog} it is told,
   * without which its slot ledger never sends a {@code reserve}.
   */
  long backlog(DeskRunnerRegistry.Session session);

  /**
   * The desks of {@code held} (the project ids a {@code hello} carries as {@code heldDesks}) that
   * this host keeps on the runner, answered in its {@code ack} as {@code adoptedDesks}; null is
   * "nothing adopted" when it could not be decided.
   */
  List<String> adopted(DeskRunnerRegistry.Session session, List<String> held);

  /**
   * A session was greeted at the pin (after its {@code ack}, and its {@code quarantined} when it is
   * out): where the front desk sends its {@code estate}.
   */
  void greeted(DeskRunnerRegistry.Session session);

  /**
   * A desk frame the registry does not handle itself — {@code inventory}, {@code launchFailed},
   * {@code removed}.
   */
  void onFrame(DeskRunnerRegistry.Session session, DeskRunnerMessage frame);
}
