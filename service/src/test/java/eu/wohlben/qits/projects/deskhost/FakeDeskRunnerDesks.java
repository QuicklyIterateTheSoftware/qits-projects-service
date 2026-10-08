package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.control.DeskRunnerDesks;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TEST-SCOPE {@link DeskRunnerDesks} whose desks a test places by hand (qits-767), so the delete
 * refusal can be driven before the {@code front_desk} table exists. Holds nothing until told, which
 * is the shipped answer, so every other suite reads the default. {@code @Alternative @Priority}, so
 * it keeps winning once the front-desk task ships a real implementation beside the default.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class FakeDeskRunnerDesks implements DeskRunnerDesks {

  private final Map<UUID, List<DeskRunnerDto.DeskRunnerDesk>> held = new ConcurrentHashMap<>();

  @Override
  public List<DeskRunnerDto.DeskRunnerDesk> desksOn(UUID runnerId) {
    return held.getOrDefault(runnerId, List.of());
  }

  /** Place {@code desks} on {@code runnerId}. */
  public void hold(UUID runnerId, List<DeskRunnerDto.DeskRunnerDesk> desks) {
    held.put(runnerId, List.copyOf(desks));
  }

  /** Forget every placed desk; the bean is shared across a {@code @QuarkusTest} class. */
  public void reset() {
    held.clear();
  }
}
