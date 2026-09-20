package eu.wohlben.qits.entities.entity;

/**
 * Who a {@link DossierPage} belongs to: one epic, or one ticket, and never both or neither.
 *
 * <p><b>Why a value rather than two nullable strings.</b> Every read and every write of a dossier
 * page is scoped by its owner — the listing, the slug lookup, the append position, the audit
 * subtree key and the gap-closing update all take one. Threading {@code (String epicId, String
 * ticketId)} through those signatures would put the "exactly one" rule at eight call sites and let
 * one of them get it wrong silently; here it cannot be constructed wrong at all, and the database's
 * own {@code ck_dossier_page_owner} (V8) says the same thing one layer down.
 *
 * <p><b>The two owners are not interchangeable</b>, and two rules read off the {@link #kind}
 * rather than off a null check, which is the other reason this is a type:
 *
 * <ul>
 *   <li>the {@code REFINING} freeze applies to an <b>epic</b> owner only — a plan freezes, a ticket
 *       does not, so a ticket's pages are writable at every status;
 *   <li>an inlined figure ({@code dossier_asset}) is <b>epic-only</b>, so the reference sync runs
 *       for an epic owner and is skipped for a ticket one.
 * </ul>
 */
public record DossierOwner(Kind kind, String id) {

  /** Which of the two roots owns the page. */
  public enum Kind {
    EPIC,
    TICKET
  }

  public DossierOwner {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("A dossier owner needs an id.");
    }
  }

  public static DossierOwner epic(String epicId) {
    return new DossierOwner(Kind.EPIC, epicId);
  }

  public static DossierOwner ticket(String ticketId) {
    return new DossierOwner(Kind.TICKET, ticketId);
  }

  /** The owner a stored page names. Exactly one of its two columns is set, so this is total. */
  public static DossierOwner of(DossierPage page) {
    return page.epicId != null ? epic(page.epicId) : ticket(page.ticketId);
  }

  public boolean isEpic() {
    return kind == Kind.EPIC;
  }

  /** The id to write into {@code dossier_page.epic_id}, or null when a ticket owns the page. */
  public String epicId() {
    return isEpic() ? id : null;
  }

  /** The id to write into {@code dossier_page.ticket_id}, or null when an epic owns the page. */
  public String ticketId() {
    return isEpic() ? null : id;
  }

  /** Stamp both columns at once, so a create cannot set one and forget to clear the other. */
  public void writeOnto(DossierPage page) {
    page.epicId = epicId();
    page.ticketId = ticketId();
  }
}
