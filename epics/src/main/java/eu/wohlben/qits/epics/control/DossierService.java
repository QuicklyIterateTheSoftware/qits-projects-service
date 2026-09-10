package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.dto.DossierPageDto;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.error.StaleWriteException;
import eu.wohlben.qits.epics.persistence.DossierPageRepository;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The only class that writes a dossier page; both doors — the SPA's REST controller and the
 * agent-facing MCP tools — go through it, so neither can grow a second version of these rules.
 *
 * <p><b>Slug.</b> Minted from the title at create, unique per epic with a numeric suffix on
 * collision, and never changed afterwards — the rule {@link Epic#slug} follows and for the same
 * reason: it is in URLs people have already sent each other. A rename changes the title only.
 *
 * <p><b>Position.</b> Dense and zero-based. A create appends; {@link #move} renumbers the affected
 * span and nothing outside it.
 *
 * <p><b>Version.</b> Incremented on every write. A write carrying a stale one throws {@link
 * StaleWriteException} with the current page attached — the caller needs to see what it would have
 * overwritten, and merging two authors' prose is not a decision this layer may make.
 *
 * <p><b>The guard is the same one features and tasks obey.</b> Every mutation calls {@link
 * EpicLifecycle#requireRefining}, so the three cannot drift apart and a non-{@code REFINING} epic
 * answers the identical message everywhere. Reads are not guarded: a frozen epic's dossier is what
 * implementation reads.
 *
 * <p><b>Audit.</b> One {@code AuditEntry} per create, update, move and delete, snapshotting the way
 * the feature and task services do, and carrying the OWNING EPIC's id as the subtree key.
 */
@ApplicationScoped
public class DossierService {

  @Inject DossierPageRepository pages;

  @Inject EpicRepository epics;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** Hook for the copied figures a page's body references. Absent until that feature lands. */
  @Inject DossierAssetService assets;

  // --- reads ----------------------------------------------------------------

  /**
   * The epic's pages in position order, bodies included, held through a postgres cutover. The tab
   * renders one immediately and a dossier is a handful of pages, so a second round trip per page
   * would buy nothing.
   */
  public List<DossierPage> listByEpic(String epicId) {
    return patience.hold("dossier list", () -> pages.listByEpic(epicId));
  }

  public DossierPage get(String pageId) {
    return pages
        .findByIdOptional(pageId)
        .orElseThrow(() -> new NotFoundException("Dossier page not found: " + pageId));
  }

  /** One page by the slug a URL names, or nothing. */
  public DossierPage getBySlug(String epicId, String slug) {
    return pages
        .findBySlug(epicId, slug)
        .orElseThrow(() -> new NotFoundException("Dossier page not found: " + slug));
  }

  // --- writes ---------------------------------------------------------------

  /** A new page, appended to the end of the epic's dossier. */
  public DossierPage create(String epicId, String title, String body, String changedBy) {
    return writes.hold(
        "dossier page create",
        () -> {
          Validations.requireText(title, "title");
          EpicLifecycle.requireRefining(requireEpic(epicId));
          DossierPage page = new DossierPage();
          page.id = UUID.randomUUID().toString();
          page.epicId = epicId;
          page.title = title;
          page.slug =
              Slugs.unique(
                  Slugs.slugify(title, page.id, "page-"),
                  pages.listByEpic(epicId).stream().map(row -> row.slug).toList());
          page.body = body == null ? "" : body;
          page.position = pages.maxPosition(epicId) + 1;
          page.version = 0L;
          page.createdAt = Instant.now();
          page.updatedAt = page.createdAt;
          pages.persist(page);
          assets.syncReferences(page.id, page.epicId, page.body);
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              page.id,
              epicId,
              AuditOperation.CREATE,
              changedBy,
              page);
          return page;
        });
  }

  /**
   * Retitle a page, rewrite its body, or both. {@code body} null leaves the markdown alone, which is
   * how a rename travels without carrying the whole page back up; {@code version} is what the caller
   * last read and a stale one is refused with the current page.
   *
   * <p>The slug is not re-derived. A page's address outlives its title.
   */
  public DossierPage update(
      String pageId, String title, String body, Long version, String changedBy) {
    return writes.hold(
        "dossier page update",
        () -> {
          DossierPage page = get(pageId);
          EpicLifecycle.requireRefining(requireEpic(page.epicId));
          requireCurrent(page, version);
          if (title != null) {
            Validations.requireText(title, "title");
            page.title = title;
          }
          if (body != null) {
            page.body = body;
            assets.syncReferences(page.id, page.epicId, body);
          }
          page.version = page.version + 1;
          page.updatedAt = Instant.now();
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              page.id,
              page.epicId,
              AuditOperation.UPDATE,
              changedBy,
              page);
          return page;
        });
  }

  /**
   * Put a page at {@code position}, renumbering the span between where it was and where it lands.
   * Positions stay dense and zero-based, so a position past the end is clamped rather than refused —
   * "put it last" is what a caller asking for that means.
   */
  public DossierPage move(String pageId, int position, String changedBy) {
    return writes.hold(
        "dossier page move",
        () -> {
          DossierPage page = get(pageId);
          EpicLifecycle.requireRefining(requireEpic(page.epicId));
          if (position < 0) {
            throw new BadRequestException("A position cannot be negative: " + position);
          }
          List<DossierPage> siblings = pages.listByEpic(page.epicId);
          int target = Math.min(position, siblings.size() - 1);
          if (target != page.position) {
            int from = page.position;
            for (DossierPage sibling : siblings) {
              if (sibling.id.equals(page.id)) {
                continue;
              }
              if (from < target && sibling.position > from && sibling.position <= target) {
                sibling.position = sibling.position - 1;
              } else if (from > target && sibling.position >= target && sibling.position < from) {
                sibling.position = sibling.position + 1;
              }
            }
            page.position = target;
            page.version = page.version + 1;
            page.updatedAt = Instant.now();
          }
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              page.id,
              page.epicId,
              AuditOperation.UPDATE,
              changedBy,
              page);
          return page;
        });
  }

  /** Remove a page and close the gap it leaves, so positions stay dense. */
  public void delete(String pageId, String changedBy) {
    writes.run(
        "dossier page delete",
        () -> {
          DossierPage page = get(pageId);
          String epicId = page.epicId;
          EpicLifecycle.requireRefining(requireEpic(epicId));
          // The same path a save takes, with nothing referenced any more: an asset whose last
          // reference goes with this page is deleted in this transaction.
          assets.syncReferences(page.id, epicId, "");
          int gone = page.position;
          pages.delete(page);
          // One statement, so the gap closes whatever the session happens to be holding.
          pages.update("position = position - 1 where epicId = ?1 and position > ?2", epicId, gone);
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              pageId,
              epicId,
              AuditOperation.DELETE,
              changedBy,
              page);
        });
  }

  // --- plumbing -------------------------------------------------------------

  private Epic requireEpic(String epicId) {
    if (epicId == null) {
      throw new NotFoundException("Epic not found: null");
    }
    return epics
        .findByIdOptional(epicId)
        .orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
  }

  /**
   * Refuse a write composed against an older read. The current page travels with the refusal, body
   * included, because the caller cannot fetch it again without racing the same writer twice.
   */
  private static void requireCurrent(DossierPage page, Long version) {
    if (version == null) {
      throw new BadRequestException("A write to a dossier page must carry its version.");
    }
    if (page.version != version) {
      throw new StaleWriteException(
          "This page was written since you read it (version "
              + page.version
              + ", you sent "
              + version
              + ").",
          view(page));
    }
  }

  /** The DTO shape both doors speak, built here so a refusal carries the same thing an answer does. */
  private static DossierPageDto view(DossierPage page) {
    return new DossierPageDto(
        page.id,
        page.epicId,
        page.slug,
        page.title,
        page.position,
        page.body,
        page.version,
        page.createdAt,
        page.updatedAt);
  }
}
