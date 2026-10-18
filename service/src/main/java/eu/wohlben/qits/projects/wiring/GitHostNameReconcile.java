package eu.wohlben.qits.projects.wiring;

import eu.wohlben.qits.projects.control.GitHostNameSync;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * <b>Re-pushes every repository's name set to the git host on an interval</b> (qits-1215): every
 * {@code qits.projects.githost-names.reconcile-every} (5m), first 30s after boot, NORMAL launch mode
 * only, it lists every repository id and hands each to {@link GitHostNameSync#push}, then logs one
 * INFO line of counts.
 *
 * <p>The after-commit push in {@link GitHostNameSync} is what moves the index at once; this is what
 * makes it <em>eventually right</em> whatever happened to that push — a git host that was down, a
 * process that died between commit and push, an index the host lost. It costs one {@code PUT} per
 * repository per pass, each answered {@code applied: false} when nothing moved, because the
 * revision makes a repeat harmless.
 *
 * <p><b>Its first run after deploy is the one-time backfill</b> of the git host's index: every row
 * reached V45 at {@code names_revision = 0}, which the host has never been sent, so that pass is
 * what fills it. There is no separate migration step and none is needed.
 *
 * <p>Nothing runs under test: the suite drives {@link #reconcile} directly, the same posture the
 * desk host's scheduled beans take.
 */
@ApplicationScoped
public class GitHostNameReconcile {

  private static final Logger LOG = Logger.getLogger(GitHostNameReconcile.class);

  /** One pass's counts: pushed is every repository the host answered for, failed is the rest. */
  public record Counts(int pushed, int applied, int notStored, int failed, int skipped) {}

  @Inject GitHostNameSync sync;
  @Inject RepositoryRepository repositories;

  @Scheduled(
      identity = "githost-name-reconcile",
      every = "${qits.projects.githost-names.reconcile-every:5m}",
      delayed = "30s",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      reconcile();
    } catch (RuntimeException e) {
      LOG.warnf("githost name sync failed: %s; the next interval retries", e.toString());
    }
  }

  /** One pass over every repository; answers and logs the counts. */
  public Counts reconcile() {
    List<String> ids =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    repositories
                        .getEntityManager()
                        .createQuery("select r.id from Repository r order by r.id", String.class)
                        .getResultList());
    int pushed = 0;
    int applied = 0;
    int notStored = 0;
    int failed = 0;
    int skipped = 0;
    for (String id : ids) {
      switch (sync.push(id)) {
        case APPLIED -> {
          pushed++;
          applied++;
        }
        case ALREADY_CURRENT -> pushed++;
        case NOT_STORED -> {
          pushed++;
          notStored++;
        }
        case FAILED -> failed++;
        case SKIPPED -> skipped++;
      }
    }
    LOG.infof(
        "githost name sync: %d pushed, %d applied, %d not stored, %d failed",
        pushed, applied, notStored, failed);
    return new Counts(pushed, applied, notStored, failed, skipped);
  }
}
