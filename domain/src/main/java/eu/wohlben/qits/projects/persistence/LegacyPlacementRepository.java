package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.LegacyPlacement;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** The desks a retired front-desk runner held (epic qits-1481, V62), keyed by project id. */
@ApplicationScoped
public class LegacyPlacementRepository
    implements PanacheRepositoryBase<LegacyPlacement, String> {}
