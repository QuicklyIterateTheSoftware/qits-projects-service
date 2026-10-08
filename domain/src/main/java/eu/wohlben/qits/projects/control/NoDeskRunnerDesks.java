package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * The {@link DeskRunnerDesks} shipped until the front-desk task (qits-767) lands the {@code
 * front_desk} table: no runner owns a desk. {@code @DefaultBean}, so that implementation wins the
 * injection point simply by existing.
 */
@ApplicationScoped
@DefaultBean
public class NoDeskRunnerDesks implements DeskRunnerDesks {

  @Override
  public List<DeskRunnerDto.DeskRunnerDesk> desksOn(UUID runnerId) {
    return List.of();
  }
}
