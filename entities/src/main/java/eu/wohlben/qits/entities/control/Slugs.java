package eu.wohlben.qits.entities.control;

import java.util.Collection;
import java.util.Locale;

/**
 * Git-safe slugs for epics, features and tasks — the path segments of the branch names the platform
 * mints: {@code epic/<epic>}, {@code feature/<epic>/<feature>}, {@code
 * task/<epic>/<feature>/<task>}.
 */
public final class Slugs {

  /**
   * <b>The one cap, and the only place it is written.</b> Both uses below read this constant —
   * {@link #slugify}'s truncation and {@link #unique}'s re-truncation when it appends {@code -2},
   * {@code -3}, … — so the two can never disagree about what fits.
   *
   * <p><b>Why 55, and why nothing nearer the obvious bounds.</b> Nothing in git or in this database
   * enforces a length at all: every slug column is {@code varchar(255)}, and qits-githost validates
   * refnames through JGit, which has no length cap. What actually binds is in a <em>different</em>
   * repository — {@code WorkspaceService.toWorkspaceSlug} in qits-workspaces-service, which
   * sanitizes a branch name and then hard-cuts it at <b>64 characters</b>, because the result
   * becomes a filesystem path segment. The longest branch prefix this service mints is
   * {@code "refining/"}, nine characters, so {@code 55 + 9 = 64} is exactly the largest slug that
   * cannot overflow that cut.
   *
   * <p><b>Going past 55 means fixing the 64-cut first, and that cut is sharp.</b>
   * {@code toWorkspaceSlug} truncates with <em>no de-collision</em>: two branches whose first 64
   * sanitized characters agree collapse onto one workspace id, and the second dispatch then fails
   * as "Workspace already exists" — a name clash presenting as a duplicate, a long way from its
   * cause. Raise this constant only together with a de-colliding fix over there.
   *
   * <p><b>There is no backfill and there never will be.</b> A slug is minted once at create and
   * never re-derived ({@code @Column(updatable = false)}; see {@link #slugify}), deliberately, so
   * that renaming a project, epic, feature, ticket or task cannot orphan a branch already cut from
   * the old slug. Every name already truncated at the old 40-character cap therefore stays
   * truncated for ever; raising this constant widens new slugs only, and no migration exists or is
   * wanted. (Out of scope and noted only so a reader is not surprised: the SPA's mirror of this
   * rule in {@code workspaces-page.ts} diverges from the server on dash handling.)
   */
  private static final int MAX_LENGTH = 55;

  private Slugs() {}

  /**
   * Derives a git-safe slug from a title: lowercase, every run of non-alphanumerics becomes a dash,
   * leading/trailing dashes stripped, capped at {@value #MAX_LENGTH} characters.
   *
   * <p>A deliberate copy of {@code ProjectService.slugify}'s <em>derivation</em> in the
   * {@code domain} module — epics must not depend on {@code domain}, so the lowercase/dash/strip
   * rule is duplicated rather than shared, and a change to the shape of the derivation belongs in
   * both. <b>The caps are NOT the same number and must not be kept in step</b>:
   * {@link #MAX_LENGTH} is {@value #MAX_LENGTH} because a branch name has to survive
   * qits-workspaces' 64-character path cut, while {@code ProjectService.MAX_SLUG_LENGTH} is 31 for
   * a reason entirely its own — a wrapper repository is named {@code <slug>-<slug>} and has to fit
   * one 64-character path segment, so {@code 31 + 31 + 1 = 63}. Each derivation is argued where its
   * constant is declared; neither number may be copied across.
   *
   * <p><b>A slug is minted once and never re-derived.</b> Every column holding one is
   * {@code updatable = false} and no update path touches it, so a retitle leaves the slug — and the
   * branches already cut from it — alone. See {@link #MAX_LENGTH} for what follows from that: names
   * truncated under the old 40-character cap stay truncated, and there is no backfill.
   *
   * <p><b>Total by construction</b> — a title with nothing alphanumeric in it ({@code "***"}, a
   * pure-unicode title) slugifies to the empty string, so it falls back to {@code fallbackPrefix}
   * plus the id's first 8 characters, which are UUID hex and therefore always valid. A SQL
   * backfill once mirrored this same fallback for rows that predated the slug column, in the old
   * H2 lineage's {@code V2__slugs.sql} — but that lineage was deleted rather than continued when
   * this module moved onto PostgreSQL (see {@code V1__init.sql}'s header), and every database
   * reaching the current V1 is empty, so there is no backfill to mirror any more: this method is
   * the only minter of a slug, in SQL or otherwise.
   */
  public static String slugify(String title, String entityId, String fallbackPrefix) {
    String slug =
        (title == null ? "" : title)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
    if (slug.length() > MAX_LENGTH) {
      // The cut can land on a dash, which a trailing dash is not allowed to be.
      slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
    }
    if (slug.isEmpty()) {
      return fallbackPrefix
          + entityId.substring(0, Math.min(8, entityId.length())).toLowerCase(Locale.ROOT);
    }
    return slug;
  }

  /**
   * Returns {@code base} when no sibling holds it, else the first free {@code -2}, {@code -3}, …,
   * trimming {@code base} so the whole stays within {@link #MAX_LENGTH} ({@value #MAX_LENGTH})
   * characters — the same constant {@link #slugify} truncates against, read here rather than
   * repeated.
   *
   * <p>These slugs are branch path segments, so two siblings sharing one would name the same
   * branch. {@code Project.slug} is unique too, with the whole service as its scope; the rule there
   * is the same suffixing for a derived slug and a 409 for a supplied one that collides.
   *
   * <p>The check is a read before a write with no lock, so two concurrent creates of the same title
   * in the same scope can both pass it and the second then fails the unique constraint as a 500.
   * That is accepted: planning writes are hand-driven, and a retry succeeds.
   */
  public static String unique(String base, Collection<String> taken) {
    if (!taken.contains(base)) {
      return base;
    }
    for (int n = 2; ; n++) {
      String suffix = "-" + n;
      String head =
          base.length() + suffix.length() <= MAX_LENGTH
              ? base
              : base.substring(0, MAX_LENGTH - suffix.length()).replaceAll("-+$", "");
      String candidate = head + suffix;
      if (!taken.contains(candidate)) {
        return candidate;
      }
    }
  }
}
