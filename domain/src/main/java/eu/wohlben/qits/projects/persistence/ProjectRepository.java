package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ProjectRepository implements PanacheRepositoryBase<Project, String> {

  public Optional<Project> findByName(String name) {
    return find("name", name).firstResultOptional();
  }

  /** The project holding {@code slug}, if any — slugs are unique (V6). */
  public Optional<Project> findBySlug(String slug) {
    return find("slug", slug).firstResultOptional();
  }

  /**
   * The projects the platform has never been told about: {@code announced_at is null} (V15).
   *
   * <p>Rows created since the create path started publishing {@code ProjectCreated} stamp the column
   * on the insert, so this is the projects that predate the event and nothing else — a list that is
   * short on the day the column lands and empty the boot after. {@code ProjectAnnounceBackfill} is
   * its one reader. Ordered by id so a boot and an assertion see the same sequence twice running.
   */
  public List<Project> listUnannounced() {
    return find("announcedAt is null order by id").list();
  }
}
