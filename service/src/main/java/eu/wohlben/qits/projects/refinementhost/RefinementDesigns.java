package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.RefinementDesign;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.error.StaleWriteException;
import eu.wohlben.qits.projects.persistence.RefinementDesignRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The refinement's HTML designs — the Design tab's rows, held host-side so they outlive the
 * container, and the surface an agent writes on.
 *
 * <p><b>One kind of write, and nobody accepts it.</b> {@link #put} creates a design when no id is
 * given and rewrites one in place when an id is. There is no proposal, no ACTIVE row and no
 * decision: a design is a document a person and an agent both write, like the epic's description,
 * and the gate on the draft is the epic's own {@code REFINING → IMPLEMENTATION} transition.
 *
 * <p><b>{@code version} is what stands in for the decision.</b> Every write bumps it and a write
 * carrying a stale one is refused with the current row attached — never merged, because guessing
 * whose sentence wins is not this class's judgement to make.
 *
 * <p>The document is not read for meaning here: it is stored whole, measured in UTF-8 bytes, and
 * never rendered by this process. The size cap is the only judgement passed on it.
 */
@ApplicationScoped
public class RefinementDesigns {

  /**
   * The current row as it travels with a refused write — the whole document included, because the
   * caller needs to see what it would have overwritten and cannot fetch it without racing again.
   */
  public record DesignView(
      String id,
      String title,
      String sourceRoute,
      int htmlBytes,
      boolean truncated,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      String html) {

    static DesignView of(RefinementDesign row) {
      return new DesignView(
          row.id,
          row.title,
          row.sourceRoute,
          row.htmlBytes,
          row.truncated,
          row.version,
          row.createdBy,
          row.createdAt,
          row.updatedAt,
          row.html);
    }
  }

  @Inject RefinementDesignRepository store;

  @Inject RefinementChangePublisher changes;

  @ConfigProperty(name = "qits.projects.refinement-design-max-bytes", defaultValue = "4194304")
  long maxBytes;

  /** Oldest first — the order the tab renders. */
  public List<RefinementDesign> list(long refinementId) {
    return QuarkusTransaction.requiringNew().call(() -> store.listByRefinement(refinementId));
  }

  public RefinementDesign get(long refinementId, String designId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByRefinementAndId(refinementId, designId))
        .orElseThrow(() -> new NotFoundException("No such design"));
  }

  /**
   * Create a design, or rewrite one in place. {@code designId} absent means create; present means
   * update, and then {@code version} must be the one the caller last read — a stale value is
   * refused with the current row rather than merged.
   *
   * <p>{@code html} may be null on an update, which leaves the document alone; that is how a rename
   * travels without carrying megabytes back.
   */
  public RefinementDesign put(
      long refinementId,
      String designId,
      String title,
      String html,
      Long version,
      String sourceRoute,
      boolean truncated,
      String createdBy) {
    requireTitle(title);
    boolean creating = designId == null || designId.isBlank();
    if (creating) {
      return create(refinementId, title, html, sourceRoute, truncated, createdBy);
    }
    int bytes = html == null ? -1 : measure(html);
    RefinementDesign written =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  RefinementDesign row =
                      store
                          .findByRefinementAndId(refinementId, designId)
                          .orElseThrow(() -> new NotFoundException("No such design"));
                  requireCurrent(row, version);
                  row.title = title;
                  if (html != null) {
                    row.html = html;
                    row.htmlBytes = bytes;
                    row.truncated = truncated;
                  }
                  if (sourceRoute != null) {
                    row.sourceRoute = sourceRoute;
                  }
                  row.version = row.version + 1;
                  row.updatedAt = Instant.now();
                  return row;
                });
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
    return written;
  }

  /** Store a design nobody has stored before — a captured page, or an agent's first draft. */
  private RefinementDesign create(
      long refinementId,
      String title,
      String html,
      String sourceRoute,
      boolean truncated,
      String createdBy) {
    int bytes = measure(html);
    Instant now = Instant.now();
    RefinementDesign row = new RefinementDesign();
    row.id = UUID.randomUUID().toString();
    row.refinementId = refinementId;
    row.title = title;
    row.html = html;
    row.htmlBytes = bytes;
    row.truncated = truncated;
    row.sourceRoute = sourceRoute;
    row.version = 0L;
    row.createdBy = createdBy;
    row.createdAt = now;
    row.updatedAt = now;
    QuarkusTransaction.requiringNew().run(() -> store.persist(row));
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
    return row;
  }

  /**
   * Refuse a write that was composed against an older version. The current row travels with the
   * refusal so the caller can show what it would have overwritten.
   */
  private static void requireCurrent(RefinementDesign row, Long version) {
    if (version == null) {
      throw new DomainException(400, "A write to an existing design must carry its version.");
    }
    if (row.version != version) {
      throw new StaleWriteException(
          "This design was written since you read it (version "
              + row.version
              + ", you sent "
              + version
              + ").",
          DesignView.of(row));
    }
  }

  public void delete(long refinementId, String designId) {
    boolean removed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    store
                        .findByRefinementAndId(refinementId, designId)
                        .map(
                            row -> {
                              store.delete(row);
                              return true;
                            })
                        .orElse(false));
    if (!removed) {
      throw new NotFoundException("No such design");
    }
    changes.fire(refinementId, RefinementChangeHint.Topic.DESIGNS);
  }

  private static void requireTitle(String title) {
    if (title == null || title.isBlank()) {
      throw new DomainException(400, "A design needs a title.");
    }
  }

  /** UTF-8 length, which is what the cap counts and what the row records. */
  private int measure(String html) {
    if (html == null || html.isBlank()) {
      throw new DomainException(400, "A design needs its HTML.");
    }
    int bytes = html.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > maxBytes) {
      throw new DomainException(413, "The design is larger than " + maxBytes + " bytes.");
    }
    return bytes;
  }
}
