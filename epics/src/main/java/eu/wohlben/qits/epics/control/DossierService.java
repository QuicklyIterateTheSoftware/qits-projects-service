package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.dto.DossierPageDto;
import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.DossierOwner;
import eu.wohlben.qits.epics.entity.DossierPage;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import eu.wohlben.qits.epics.error.StaleWriteException;
import eu.wohlben.qits.epics.persistence.DossierPageRepository;
import eu.wohlben.qits.epics.persistence.EpicRepository;
import eu.wohlben.qits.epics.persistence.TicketRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only class that writes a dossier page; both doors — the SPA's REST controllers and the
 * agent-facing MCP tools — go through it, so neither can grow a second version of these rules.
 *
 * <p><b>A page has ONE owner, and it is an epic or a ticket.</b> Every method here takes a {@link
 * DossierOwner} rather than an epic id (V8): an epic's dossier is the plan's breakdown, a ticket's
 * is what the refine phase wrote when the ticket's own body could not hold it. Two of this class's
 * rules then read off the owner's kind rather than applying to everything:
 *
 * <ul>
 *   <li><b>{@link EpicLifecycle#requireRefining} runs for an EPIC owner only.</b> A plan freezes and
 *       its dossier freezes with it; a ticket freezes nothing — {@code TicketLifecycle} has no
 *       {@code requireOpen} and must not grow one — so a ticket-owned page is writable while the
 *       ticket is {@code REPORTED}, {@code IMPLEMENTED} and {@code DONE} alike. The phase that
 *       mostly writes them (refine) is deliberately not the only one allowed to: the implement phase
 *       correcting a page it found wrong is the ordinary case, not a violation.
 *   <li><b>{@link DossierAssetService#syncReferences} runs for an EPIC owner only.</b> Assets are
 *       copies of the refining route's sketches and designs, a route a ticket does not have, so
 *       {@code dossier_asset} stays epic-only. <b>That is why a ticket page cannot inline a
 *       sketch</b>: markdown naming an asset id on a ticket-owned page copies nothing and writes no
 *       {@code dossier_page_asset} row, rather than being handed a null epic id to count against.
 * </ul>
 *
 * <p><b>Slug.</b> Minted from the title at create, unique <b>per owner</b> with a numeric suffix on
 * collision, and never changed afterwards — the rule {@link Epic#slug} follows and for the same
 * reason: it is in URLs people have already sent each other. A rename changes the title only. The
 * same slug under an epic and under a ticket is two different addresses that never meet.
 *
 * <p><b>Position.</b> Dense and zero-based, per owner. A create appends; {@link #move} renumbers the
 * affected span and nothing outside it.
 *
 * <p><b>Version.</b> Incremented on every write. A write carrying a stale one throws {@link
 * StaleWriteException} with the current page attached — the caller needs to see what it would have
 * overwritten, and merging two authors' prose is not a decision this layer may make.
 *
 * <p><b>Audit.</b> One {@code AuditEntry} per create, update, move and delete, snapshotting the way
 * the feature and task services do, and carrying <b>the owner's</b> id as the subtree key. For a
 * ticket-owned page that is the ticket's id, which needs no schema change: {@code auditentry.epic_id}
 * is the subtree key rather than literally an epic (V4's stated reading — a {@code TICKET} row is
 * its own root), so "the whole history of this ticket" keeps including what its pages did.
 */
@ApplicationScoped
public class DossierService {

  @Inject DossierPageRepository pages;

  @Inject EpicRepository epics;

  @Inject TicketRepository tickets;

  @Inject AuditService auditService;

  @Inject ReadPatience patience;

  @Inject WritePatience writes;

  /** The copied figures a page's body references. Epic-owned pages only; see the class javadoc. */
  @Inject DossierAssetService assets;

  // --- reads ----------------------------------------------------------------

  /**
   * The owner's pages in position order, bodies included, held through a postgres cutover. The tab
   * renders one immediately and a dossier is a handful of pages, so a second round trip per page
   * would buy nothing.
   */
  public List<DossierPage> listByOwner(DossierOwner owner) {
    return patience.hold("dossier list", () -> pages.listByOwner(owner));
  }

  public DossierPage get(String pageId) {
    return pages
        .findByIdOptional(pageId)
        .orElseThrow(() -> new NotFoundException("Dossier page not found: " + pageId));
  }

  /** One page by the slug a URL names, or nothing. */
  public DossierPage getBySlug(DossierOwner owner, String slug) {
    return findBySlug(owner, slug)
        .orElseThrow(() -> new NotFoundException("Dossier page not found: " + slug));
  }

  /**
   * The same lookup for a door that has something else to try — the ticket routes address a page by
   * slug or by id, and an absent slug there is not yet a 404.
   */
  public Optional<DossierPage> findBySlug(DossierOwner owner, String slug) {
    return pages.findBySlug(owner, slug);
  }

  // --- writes ---------------------------------------------------------------

  /** A new page, appended to the end of the owner's dossier. */
  public DossierPage create(DossierOwner owner, String title, String body, String changedBy) {
    return writes.hold(
        "dossier page create",
        () -> {
          Validations.requireText(title, "title");
          requireWritable(owner);
          DossierPage page = new DossierPage();
          page.id = UUID.randomUUID().toString();
          owner.writeOnto(page);
          page.title = title;
          page.slug =
              Slugs.unique(
                  Slugs.slugify(title, page.id, "page-"),
                  pages.listByOwner(owner).stream().map(row -> row.slug).toList());
          page.body = body == null ? "" : body;
          page.position = pages.maxPosition(owner) + 1;
          page.version = 0L;
          page.createdAt = Instant.now();
          page.updatedAt = page.createdAt;
          pages.persist(page);
          syncAssets(owner, page.id, page.body);
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              page.id,
              owner.id(),
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
          DossierOwner owner = DossierOwner.of(page);
          requireWritable(owner);
          requireCurrent(page, version);
          if (title != null) {
            Validations.requireText(title, "title");
            page.title = title;
          }
          if (body != null) {
            page.body = body;
            syncAssets(owner, page.id, body);
          }
          page.version = page.version + 1;
          page.updatedAt = Instant.now();
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              page.id,
              owner.id(),
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
          DossierOwner owner = DossierOwner.of(page);
          requireWritable(owner);
          if (position < 0) {
            throw new BadRequestException("A position cannot be negative: " + position);
          }
          List<DossierPage> siblings = pages.listByOwner(owner);
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
              owner.id(),
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
          DossierOwner owner = DossierOwner.of(page);
          requireWritable(owner);
          // The same path a save takes, with nothing referenced any more: an asset whose last
          // reference goes with this page is deleted in this transaction. Epic owners only, for the
          // reason the class javadoc gives.
          syncAssets(owner, page.id, "");
          int gone = page.position;
          pages.delete(page);
          pages.closeGapAfter(owner, gone);
          auditService.record(
              AuditEntityType.DOSSIER_PAGE,
              pageId,
              owner.id(),
              AuditOperation.DELETE,
              changedBy,
              page);
        });
  }

  // --- plumbing -------------------------------------------------------------

  /**
   * Resolve the owner — 404 if it does not exist, whichever kind it is — and apply the freeze that
   * belongs to an epic and to nothing else.
   */
  private void requireWritable(DossierOwner owner) {
    if (owner == null) {
      throw new NotFoundException("Dossier owner not found: null");
    }
    if (owner.isEpic()) {
      // The freeze, and only here. A ticket owner is resolved and then left alone.
      EpicLifecycle.requireRefining(
          epics
              .findByIdOptional(owner.id())
              .orElseThrow(() -> new NotFoundException("Epic not found: " + owner.id())));
      return;
    }
    requireOwner(owner);
  }

  /**
   * Assert the owning epic or ticket exists, 404ing the same way for both: the id in a path or a
   * tool argument is a boundary, so an owner that is not there is absent rather than an error about
   * a null. Public because the read doors check it before listing an empty dossier.
   */
  public void requireOwner(DossierOwner owner) {
    if (owner == null) {
      throw new NotFoundException("Dossier owner not found: null");
    }
    if (owner.isEpic()) {
      epics
          .findByIdOptional(owner.id())
          .orElseThrow(() -> new NotFoundException("Epic not found: " + owner.id()));
      return;
    }
    tickets
        .findByIdOptional(owner.id())
        .orElseThrow(() -> new NotFoundException("Ticket not found: " + owner.id()));
  }

  /**
   * Count the figures an epic-owned page references, and do nothing at all for a ticket-owned one:
   * {@code dossier_asset} is epic-only by decision (V8), so there is no epic id to count against and
   * inventing one would be the cross-owner reference the copy exists to make impossible.
   */
  private void syncAssets(DossierOwner owner, String pageId, String body) {
    if (owner.isEpic()) {
      assets.syncReferences(pageId, owner.id(), body);
    }
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
        page.ticketId,
        page.slug,
        page.title,
        page.position,
        page.body,
        page.version,
        page.createdAt,
        page.updatedAt);
  }
}
