package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * The {@link DeskRunnerDesks} of this module on its own — its suite, which has no front desks wired:
 * no runner owns a desk. {@code @DefaultBean}, so the service's {@code deskhost/FrontDeskRunnerDesks},
 * which reads {@code front_desk} (qits-767), wins the injection point simply by existing.
 */
@ApplicationScoped
@DefaultBean
public class NoDeskRunnerDesks implements DeskRunnerDesks {

  @Override
  public List<DeskRunnerDto.DeskRunnerDesk> desksOn(UUID runnerId) {
    return List.of();
  }
}
