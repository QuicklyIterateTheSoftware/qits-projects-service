package eu.wohlben.qits.entities.campaign;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The {@code campaign_start} rows, plain CRUD; the caller owns the transaction. */
@ApplicationScoped
public class CampaignStartRecordRepository
    implements PanacheRepositoryBase<CampaignStartRecord, String> {

  /** The start of {@code campaignId}, or empty for a campaign never started. */
  public Optional<CampaignStartRecord> startOf(String campaignId) {
    return findByIdOptional(campaignId);
  }

  /** Whether {@code campaignId} has a start and it is active — the executor's own gate. */
  public boolean isActive(String campaignId) {
    return startOf(campaignId).map(start -> start.active).orElse(false);
  }

  /** The starts of every campaign in {@code campaignIds}, in one query. */
  public List<CampaignStartRecord> startsOfAll(Collection<String> campaignIds) {
    if (campaignIds == null || campaignIds.isEmpty()) {
      return List.of();
    }
    return find("campaignId in ?1", campaignIds).list();
  }

  /**
   * <b>The pause hook's one statement</b>: the campaign's start, if it has one, is no longer active.
   * An UPDATE rather than a read-modify-write, so it takes the row lock the executor's claim waits on
   * with {@code FOR SHARE}. A campaign never started has no row and nothing happens.
   */
  public int pause(String campaignId) {
    return update("active = false where campaignId = ?1 and active = true", campaignId);
  }
}
