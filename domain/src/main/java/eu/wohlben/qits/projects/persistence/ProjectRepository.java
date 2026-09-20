package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
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
   * <b>The projects named by {@code ids}, in ONE query.</b> The bulk read behind the qualified
   * entity id {@code <project-slug>-<number>}: a listing of entities spans as many projects as it
   * likes, and resolving the slug row by row would be an N+1 on exactly the pages that are longest.
   * {@code WorkEntityRepository.listByIds} is the same shape one module over, for the same reason.
   *
   * <p>An empty input answers an empty list <b>without asking the database</b>: {@code in ()} is a
   * syntax error in postgres, so the guard is correctness rather than an optimisation. An id naming
   * no project is simply absent from the answer — never an exception, because a decoration must not
   * be able to fail the read it decorates.
   */
  public List<Project> list(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return find("id in ?1", ids).list();
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
