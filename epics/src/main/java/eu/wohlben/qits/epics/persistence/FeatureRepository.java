package eu.wohlben.qits.epics.persistence;

import eu.wohlben.qits.epics.entity.Feature;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The legacy {@code feature} table's CRUD. <b>No service calls it any more</b>: {@code
 * FeatureService} reads and writes {@code entity} + {@code entity_membership}, and {@code
 * EpicService}'s subtree walks went with it. It is kept because the table is kept — the recovery
 * path and the verification door's comparison target — and because the suite wipes that table
 * between tests. It goes when the table does.
 */
@ApplicationScoped
public class FeatureRepository implements PanacheRepositoryBase<Feature, String> {

  /** Oldest first — the same order V2's slug backfill ranked duplicates by. */
  public List<Feature> listByEpic(String epicId) {
    return find("epicId", Sort.by("createdAt").and("id"), epicId).list();
  }

  /** Features whose {@code dependsOnFeatureId} points at {@code featureId}. */
  public List<Feature> listDependents(String featureId) {
    return find("dependsOnFeatureId", featureId).list();
  }
}
