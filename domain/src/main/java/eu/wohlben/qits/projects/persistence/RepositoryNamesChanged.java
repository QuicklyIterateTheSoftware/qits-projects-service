package eu.wohlben.qits.projects.persistence;

/**
 * <b>A repository's name set changed in the current transaction</b> (qits-1215) — a CDI event, never
 * a bus one. Fired by {@link RepositoryNameRepository} from every write that actually adds or drops
 * a {@code repository_name} row, together with the {@code names_revision} bump.
 *
 * <p>It lives beside its one publisher rather than in {@code control/}: {@code control} reaches into
 * {@code persistence} everywhere and nothing here reaches back, and an event record is not a reason
 * to start.
 *
 * <p>{@code control/GitHostNameSync} observes it {@code during = AFTER_SUCCESS}, so a rolled-back
 * write pushes nothing, and a transaction that changed the names several times (a rename forgets
 * and then inserts) is still one name set by the time anything reads it.
 */
public record RepositoryNamesChanged(String repositoryId) {}
