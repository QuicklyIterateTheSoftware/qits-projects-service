package eu.wohlben.qits.projects.diag;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * TEMPORARY DIAGNOSTICS FOR qits-965 — delete once the gate's "unable to create native thread" is
 * understood and fixed. Nothing in the suite depends on it.
 *
 * <p>Registered through {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener},
 * so every surefire fork of the service module starts it. It runs a daemon thread that writes one
 * {@code [qits-965-diag]} line to stderr every 15 s: the cgroup's pids and memory (cgroup v2 at
 * {@code /sys/fs/cgroup}, which in the gate's container is the step's own cgroup), this JVM's thread
 * count with the most frequent thread-name prefixes, and every visible process's task count grouped
 * by {@code comm}. It never throws and never fails a test: every read is guarded and a failure
 * prints {@code ?}.
 */
public final class Qits965ThreadSampler implements LauncherSessionListener {

    private static final String TAG = "[qits-965-diag]";
    private static final long PERIOD_MS = 2_000;
    private static volatile Thread sampler;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            start();
        } catch (Throwable t) {
            safePrint(TAG + " could not start: " + t);
        }
    }

    private static synchronized void start() {
        if (sampler != null) {
            return;
        }
        long t0 = System.nanoTime();
        Runtime rt = Runtime.getRuntime();
        safePrint(TAG + " start pid=" + ProcessHandle.current().pid()
                + " availableProcessors=" + rt.availableProcessors()
                + " maxMemory=" + rt.maxMemory()
                + " cpu.max=" + read("/sys/fs/cgroup/cpu.max")
                + " cpuset.cpus.effective=" + read("/sys/fs/cgroup/cpuset.cpus.effective")
                + " pids.max=" + read("/sys/fs/cgroup/pids.max")
                + " memory.max=" + read("/sys/fs/cgroup/memory.max")
                + " cgroup=" + read("/proc/self/cgroup")
                + " jvmArgs=" + safeJvmArgs());
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    sample(t0);
                } catch (Throwable e) {
                    safePrint(TAG + " sample failed: " + e);
                }
                try {
                    Thread.sleep(PERIOD_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "qits-965-diag");
        t.setDaemon(true);
        t.start();
        sampler = t;
    }

    private static void sample(long t0) {
        long secs = (System.nanoTime() - t0) / 1_000_000_000L;
        StringBuilder line = new StringBuilder(TAG);
        line.append(" t=").append(secs)
                .append(" pids.current=").append(read("/sys/fs/cgroup/pids.current"))
                .append(" pids.max=").append(read("/sys/fs/cgroup/pids.max"))
                .append(" mem.current=").append(read("/sys/fs/cgroup/memory.current"))
                .append(" mem.max=").append(read("/sys/fs/cgroup/memory.max"));
        try {
            Map<String, Integer> prefixes = new HashMap<>();
            int total = 0;
            for (Thread th : Thread.getAllStackTraces().keySet()) {
                total++;
                prefixes.merge(prefix(th.getName()), 1, Integer::sum);
            }
            line.append(" jvmThreads=").append(total).append(" top=").append(top(prefixes, 5));
        } catch (Throwable e) {
            line.append(" jvmThreads=? top=?");
        }
        try {
            File[] dirs = new File("/proc").listFiles();
            int procs = 0;
            int zombies = 0;
            Map<String, Integer> tasksByComm = new HashMap<>();
            if (dirs != null) {
                for (File d : dirs) {
                    if (!isNumeric(d.getName())) {
                        continue;
                    }
                    procs++;
                    String stat = read(d.getPath() + "/stat");
                    int close = stat.lastIndexOf(')');
                    if (close > 0 && close + 2 < stat.length() && stat.charAt(close + 2) == 'Z') {
                        zombies++;
                    }
                    String[] tasks = new File(d, "task").list();
                    int n = tasks == null ? 0 : tasks.length;
                    String comm = read(d.getPath() + "/comm");
                    tasksByComm.merge(comm, n, Integer::sum);
                }
            }
            line.append(" procs=").append(procs).append(" zombies=").append(zombies).append(" tasks=").append(top(tasksByComm, 5));
        } catch (Throwable e) {
            line.append(" procs=?");
        }
        // Every 2 s near the pids limit, else every 30 s: the gate keeps only the end of a step's
        // log, so the samples that matter have to be the last ones written before a failure.
        long pids = -1;
        try {
            pids = Long.parseLong(read("/sys/fs/cgroup/pids.current"));
        } catch (Throwable ignored) {
            // absent outside a pids cgroup
        }
        if (pids >= 1500 || secs % 30 < 2) {
            safePrint(line.toString());
        }
    }

    /** Thread name with trailing digits, ids and separators stripped, so a pool collapses to one. */
    static String prefix(String name) {
        if (name == null) {
            return "null";
        }
        String p = name.replaceAll("[0-9a-fA-F]{8,}", "")
                .replaceAll("[\\s#:\\-_.()\\[\\]0-9]+$", "")
                .replaceAll("[0-9]+", "N");
        return p.isEmpty() ? "N" : p.replace(' ', '_');
    }

    private static String top(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path)).trim().replace('\n', '|').replace(' ', '_');
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String safeJvmArgs() {
        try {
            return String.join(",", ManagementFactory.getRuntimeMXBean().getInputArguments()).replace(' ', '_');
        } catch (Throwable e) {
            return "?";
        }
    }

    private static void safePrint(String s) {
        try {
            // Through the logging the Quarkus apps use: surefire routes a bare System.err write from
            // this daemon thread to the dumpstream file, which the gate's log never shows.
            org.jboss.logging.Logger.getLogger("qits-965-diag").warn(s);
        } catch (Throwable ignored) {
            // diagnostics must never fail a test
        }
    }
}
