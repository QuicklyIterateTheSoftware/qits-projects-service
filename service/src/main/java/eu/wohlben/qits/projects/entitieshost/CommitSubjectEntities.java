package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.entity.Archetype;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The qualified entity id, read back out of a commit subject.</b> {@code
 * fix(qits-1337): the button is the wrong colour} names entity 1337 of the project slugged {@code
 * qits}, and this class is the <b>one place in the estate that knows that grammar</b> — the reading
 * half of what {@code projects/api/QualifiedEntityIds} writes.
 *
 * <h2>NO SUBJECT IS A NORMAL ANSWER, AND IT IS NEVER A COMPLAINT</h2>
 *
 * <p><b>Every commit already in this platform's history predates this convention, and always will.
 * Most commits written after it will not carry an id either.</b> So a subject with no id, a subject
 * with a malformed id, and an id naming no entity are all <b>{@link Optional#empty()}</b> — and
 * <b>nothing is logged. No warning, no info, no debug-level complaint, no counter, no metric,
 * nothing that any reader could ever interpret as a degraded state.</b> This class holds no logger
 * field at all, which is the cheapest way to make that unbreakable, and every empty return below is
 * deliberately left unadorned.
 *
 * <p>Get this wrong once and every consumer built on top inherits a false alarm that can never be
 * cleared, because the condition it fires on is the ordinary case. A caller that <em>needs</em> an
 * id — the wrapper release request of ticket {@code da925ad1}, which refuses a branch whose subject
 * does not name a VERIFIED entity — says so itself, in its own words, at its own call site. It is
 * that caller's refusal, never this class's observation.
 *
 * <h2>It resolves across projects, and that is the decision</h2>
 *
 * <p>{@link #resolve} takes <b>no "current project" argument</b>. The form is project-qualified,
 * which is the entire reason it is qualified: {@code other-7} names project {@code other}'s entity
 * 7 unambiguously and globally, and a resolver that silently refused it — or worse, read it as the
 * caller's own entity 7 — would be answering a question nobody asked. What a consumer that cares
 * about the project does is compare: {@link NamedEntity} carries {@code projectId} and {@code
 * projectSlug} precisely so the refusal can be made <b>on the consumer's terms, in the consumer's
 * words</b>, rather than disguised as "no id found".
 *
 * <h2>Why it lives here</h2>
 *
 * <p>Resolution needs <b>both databases</b>: the project slug is in {@code domain}'s {@code project}
 * table and the {@code (project_id, number)} pair is in {@code entities}' {@code entity} table, two
 * separate physical databases in two modules that do not depend on each other. {@code service} is
 * the only module that can hold the pair, and {@code projects/entitieshost/} is where this repository
 * already declares a service-layer bridge into {@code entities} — see {@link
 * TicketUnattendedGateTickets}. The grammar could have lived in {@code entities} and the lookup here;
 * it deliberately does not, because two halves of one grammar in two modules is two places to keep
 * a format in step, and this format is written by hand by people and by agents.
 *
 * <h2>The grammar</h2>
 *
 * <p>The id sits in the conventional-commit <b>scope</b>: {@code term(<project-slug>-<n>): message}.
 *
 * <ul>
 *   <li><b>Only the first line is considered.</b> Everything from the first {@code \n} onward is the
 *       body and is ignored — including a body that itself contains something id-shaped, which is
 *       the ordinary case of a commit message quoting one.
 *   <li>The term before {@code (} is free-form and may contain a slash, or be absent entirely:
 *       {@code feat}, {@code fix}, {@code chore}, {@code entities/control}, or {@code (qits-1337):}.
 *   <li>An optional breaking-change {@code !} may sit between {@code )} and {@code :}.
 *   <li>Inside the parens, {@code <slug>-<digits>}, split on the <b>LAST</b> hyphen-then-digits — so
 *       a project slug that itself ends in digits ({@code other-2}) still reads correctly.
 *   <li><b>The scope may hold several comma-separated ids</b> — {@code
 *       chore(qits-1, qits-2): x} — one project's or several's, in any order, duplicates allowed.
 *       {@link #references} is the reading of all of them; {@link #reference} stays the first.
 *       <b>This reader is deliberately more lenient than the githost receive guard's writing
 *       grammar</b>: it strips each comma-separated part on both sides before matching, so {@code
 *       chore(qits-1 , qits-2): x} — padding around the comma — still reads both ids, which the
 *       guard's own grammar never writes but must remain a strict subset of what this class
 *       accepts.
 *   <li>A {@code :} must follow the scope. The message after it is not this class's business.
 * </ul>
 */
@ApplicationScoped
public class CommitSubjectEntities {

  /**
   * The conventional-commit head: an optional free-form term, a parenthesised scope, an optional
   * breaking-change bang, and the colon. The term excludes parentheses, whitespace and a colon —
   * so the scope is the conventional-commit scope at the head of the line and never a parenthesised
   * aside later in a message that already had its colon ({@code fix: tidy (qits-7): no}).
   */
  private static final Pattern HEAD = Pattern.compile("^([^()\\s:]*)\\(([^()]+)\\)(!?):");

  /**
   * The scope split into slug and number. The first group is <b>greedy</b>, which is what makes the
   * split happen at the LAST hyphen-then-digits: {@code other-2-7} is project {@code other-2},
   * entity 7. The digit run is bounded at 18 so the parse cannot overflow a {@code long} — a
   * nineteen-digit number is not an entity id anybody allocated, and it reads as no id at all.
   */
  private static final Pattern SCOPE =
      Pattern.compile("^([A-Za-z0-9][A-Za-z0-9-]*)-([0-9]{1,18})$");

  /** The two-database lookup, shared with every door that takes a qualified id (qits-551). */
  @Inject EntityIdResolver resolver;

  /**
   * <b>The grammar, pure and side-effect free.</b> No database, no injection, no logging — the
   * shape of the subject and nothing else, which is what makes it unit-testable on its own and what
   * makes it the single statement of the format.
   *
   * @param subject a commit message; only its first line is read
   * @return the <b>first</b> id the subject names, or empty. <b>Empty is normal</b> — see the
   *     class javadoc. A scope naming several ids (see {@link #references}) answers its first.
   */
  public static Optional<QualifiedId> reference(String subject) {
    List<QualifiedId> all = references(subject);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  /**
   * <b>Every id the subject's scope names</b>, deduplicated and in first-seen order. The scope is
   * split on {@code ,}, each part is {@link String#strip() stripped} on both sides and read as a
   * {@link #parse(String) parse}; <b>if any part is empty or does not match, the whole scope names
   * nothing and this answers {@link List#of() List.of()}</b> — a malformed id beside a well-formed
   * one is not read as "one id was found", because a receive guard checking the same subject would
   * refuse the commit outright rather than pick the id it could parse.
   *
   * <p>Pure like {@link #reference}, which is simply this method's first element.
   *
   * @param subject a commit message; only its first line is read
   * @return every distinct id the scope names, first-seen order; empty when the subject names no
   *     scope, an empty scope, or a scope any part of which does not match the grammar
   */
  public static List<QualifiedId> references(String subject) {
    if (subject == null || subject.isBlank()) {
      return List.of();
    }
    int newline = subject.indexOf('\n');
    String firstLine = (newline < 0 ? subject : subject.substring(0, newline)).trim();

    Matcher head = HEAD.matcher(firstLine);
    if (!head.find()) {
      return List.of();
    }
    LinkedHashSet<QualifiedId> ids = new LinkedHashSet<>();
    // limit -1: a trailing comma must leave a trailing EMPTY part rather than being silently
    // dropped, which is what Java's split(regex) does with the default limit of 0 — and a
    // trailing empty part is exactly what must refuse "chore(qits-1,): x" below.
    for (String part : head.group(2).split(",", -1)) {
      Optional<QualifiedId> id = parse(part);
      if (id.isEmpty()) {
        return List.of();
      }
      ids.add(id.get());
    }
    return List.copyOf(ids);
  }

  /**
   * <b>The scope half of the grammar on its own</b>: {@code qits-1337} as it appears in a commit
   * subject's parentheses, in a path segment or in a tool argument, split on the LAST
   * hyphen-then-digits. Pure like {@link #reference}; empty for anything that is not that shape.
   * <b>A UUID whose last group happens to be all digits does match</b> ({@code
   * …-4e1f-123456789012} reads as entity 123456789012 of a project slugged by the rest), which is
   * why {@link EntityIdResolver} tries a string as an entity id before it tries it as this.
   *
   * @param qualifiedId {@code <project-slug>-<number>}, surrounding whitespace ignored
   * @return the slug and the number it names, or empty
   */
  public static Optional<QualifiedId> parse(String qualifiedId) {
    if (qualifiedId == null) {
      return Optional.empty();
    }
    Matcher scope = SCOPE.matcher(qualifiedId.trim());
    if (!scope.matches()) {
      return Optional.empty();
    }
    return Optional.of(new QualifiedId(scope.group(1), Long.parseLong(scope.group(2))));
  }

  /**
   * <b>Grammar and lookup, in one call.</b> The subject is read, the project slug resolved and the
   * {@code (project_id, number)} pair hit directly against {@code uq_entity_project_number}.
   *
   * <p><b>Three different misses are one answer</b>, and every one of them is ordinary: no id in
   * the subject, an id that will not parse, and a well-formed id naming a project or an entity that
   * does not exist. Nothing is logged for any of them.
   *
   * <p>The lookup is {@link EntityIdResolver#lookup}'s — two reads in two transactions, callable
   * from a plain worker thread — since qits-551 made the REST and MCP comment doors its second and
   * third readers.
   *
   * @return the entity the subject names, with its archetype and its status, from one lookup
   */
  public Optional<NamedEntity> resolve(String subject) {
    return reference(subject).flatMap(resolver::lookup);
  }

  /**
   * What a commit subject said, before anything was looked up: a project slug and a number.
   *
   * @param projectSlug the qualifier, as written — not yet known to name a project
   * @param number the per-project entity number, as written — not yet known to name an entity
   */
  public record QualifiedId(String projectSlug, long number) {

    /**
     * The id as it is written, {@code <project-slug>-<number>}. It is the identical string {@code
     * QualifiedEntityIds.render} produces on the way out; {@code CommitSubjectEntitiesTest} asserts
     * the round trip rather than leaving the two to agree by eye.
     */
    public String rendered() {
      return projectSlug + "-" + number;
    }
  }

  /**
   * The entity a commit subject names, <b>with everything a consumer needs from ONE lookup</b>.
   *
   * <p>The shape is its first consumer's: ticket {@code da925ad1}, a wrapper release request that
   * refuses a branch whose subject does not name a VERIFIED entity. That decision needs the
   * archetype (is this a ticket at all) and the status (is it VERIFIED) beside the id, and a shape
   * that answered the id alone would make it a second read on every branch of every release.
   *
   * @param id the entity's uuid — the id every dossier page, audit row, branch name and URL on this
   *     platform already names
   * @param qualifiedId the form the subject used, {@code <project-slug>-<number>}
   * @param projectId the owning project. <b>Carried so a consumer can compare</b> — see the class
   *     javadoc on why {@code resolve} itself never refuses a cross-project reference
   * @param projectSlug the same project, as a person writes it
   * @param number the bare per-project number
   * @param archetype EPIC, TICKET, FEATURE or TASK. The number names a node, so all four are
   *     reachable through one subject
   * @param status the status word as stored — every archetype holds one since qits-763, a FEATURE's
   *     and a TASK's being where that piece stands rather than its epic's phase. A consumer gating on
   *     a status must read the archetype first
   * @param title the label, for the message a consumer writes about it
   */
  public record NamedEntity(
      String id,
      String qualifiedId,
      String projectId,
      String projectSlug,
      long number,
      Archetype archetype,
      String status,
      String title) {}
}
