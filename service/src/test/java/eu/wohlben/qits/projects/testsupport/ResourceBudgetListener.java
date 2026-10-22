package eu.wohlben.qits.projects.testsupport;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * <b>Hard numbers from inside the CI fork</b> (epic qits-1038): CI dies with {@code
 * OutOfMemoryError: unable to create native thread} near the end of the surefire run, on both
 * runners, while {@code main} passes and the failure never reproduces locally. The fork's thread
 * and process budget is invisible from the surefire report itself, so this listener logs it as the
 * suite runs: once at the start (the limits the fork actually has), and once per test class finished
 * (what the fork is spending against them).
 *
 * <p>Auto-registered through {@code
 * META-INF/services/org.junit.platform.launcher.TestExecutionListener}, so no test wiring needs to
 * change. Every read here is best-effort: {@code /proc} and {@code /sys/fs/cgroup} are Linux-only,
 * may not exist (a dev machine, a cgroup v1 host, a sandboxed read), and may race against processes
 * disappearing mid-scan. Nothing in this class may throw out of a listener callback — doing so would
 * fail or skew the very suite it is trying to observe — so every read is wrapped and falls back to
 * {@code "n/a"} or is simply skipped.
 */
public class ResourceBudgetListener implements TestExecutionListener {

  private static final String PREFIX = "[qits-budget]";

  private final AtomicBoolean limitsPrinted = new AtomicBoolean(false);
  private final AtomicInteger classesSeen = new AtomicInteger(0);
  private final AtomicInteger maxJvmThreads = new AtomicInteger(0);

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    if (!limitsPrinted.compareAndSet(false, true)) {
      return;
    }
    try {
      String maxProcesses = readLimitLine("Max processes");
      String maxOpenFiles = readLimitLine("Max open files");
      String cgroupPidsMax = readCgroupValue("pids.max", "pids/pids.max");
      String cgroupMemMax = readCgroupValue("memory.max", "memory/memory.limit_in_bytes");
      String cgroupSwapMax = readCgroupValue("memory.swap.max", null);
      long runtimeMaxMemory = safeLong(() -> Runtime.getRuntime().maxMemory());
      String uid = safeString(ResourceBudgetListener::readOwnUid);
      List<String> inputArgs =
          safeList(() -> ManagementFactory.getRuntimeMXBean().getInputArguments());

      System.out.println(
          PREFIX
              + " limits maxProcesses=\""
              + maxProcesses
              + "\" maxOpenFiles=\""
              + maxOpenFiles
              + "\" cgroupPidsMax="
              + cgroupPidsMax
              + " cgroupMemMax="
              + cgroupMemMax
              + " cgroupSwapMax="
              + cgroupSwapMax
              + " runtimeMaxMemory="
              + runtimeMaxMemory
              + " uid="
              + uid
              + " jvmArgs="
              + inputArgs);
    } catch (Throwable t) {
      // Best-effort: never let diagnostics break the suite.
    }
  }

  @Override
  public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
    try {
      testIdentifier
          .getSource()
          .filter(source -> source instanceof ClassSource)
          .map(source -> (ClassSource) source)
          .ifPresent(
              classSource -> {
                String simpleName = simpleNameOf(classSource.getClassName());
                printBudgetLine(simpleName);
              });
    } catch (Throwable t) {
      // Best-effort: never let diagnostics break the suite.
    }
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    try {
      printBudgetLine("TOTAL");
    } catch (Throwable t) {
      // Best-effort: never let diagnostics break the suite.
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The per-class/per-plan budget line
  // ---------------------------------------------------------------------------------------------

  private void printBudgetLine(String label) {
    int activeCount = safeInt(Thread::activeCount);
    ThreadMXBean threadMXBean = safeBean(ManagementFactory::getThreadMXBean);
    int threadMXCount = threadMXBean == null ? -1 : safeInt(threadMXBean::getThreadCount);
    int peakThreads = threadMXBean == null ? -1 : safeInt(threadMXBean::getPeakThreadCount);
    long httpClientThreads = safeLong(ResourceBudgetListener::countHttpClientThreads);

    String cgroupPids = readCgroupValue("pids.current", "pids/pids.current");
    String cgroupPidsPeak = readCgroupValue("pids.peak", null);
    String memCurrent = readCgroupValue("memory.current", "memory/memory.usage_in_bytes");
    String memPeak = readCgroupValue("memory.peak", "memory/memory.max_usage_in_bytes");

    ProcScanResult procScan = safeProcScan();

    MemoryMXBean memoryMXBean = safeBean(ManagementFactory::getMemoryMXBean);
    long heapUsedMb =
        memoryMXBean == null
            ? -1
            : safeLong(() -> memoryMXBean.getHeapMemoryUsage().getUsed() / (1024 * 1024));

    System.out.println(
        PREFIX
            + " "
            + label
            + " jvmThreads="
            + activeCount
            + "/"
            + threadMXCount
            + " peakThreads="
            + peakThreads
            + " httpClientThreads="
            + httpClientThreads
            + " cgroupPids="
            + cgroupPids
            + " cgroupPidsPeak="
            + cgroupPidsPeak
            + " memCurrent="
            + memCurrent
            + " memPeak="
            + memPeak
            + " uidProcs="
            + procScan.uidProcs
            + " uidThreads="
            + procScan.uidThreads
            + " zombies="
            + procScan.zombies
            + " heapUsed="
            + heapUsedMb
            + "MB");

    int classCount = classesSeen.incrementAndGet();
    int observedJvmThreads = threadMXCount >= 0 ? threadMXCount : activeCount;
    int previousMax = maxJvmThreads.getAndUpdate(prev -> Math.max(prev, observedJvmThreads));
    boolean grewALot = observedJvmThreads - previousMax >= 20;
    boolean every25 = classCount % 25 == 0;
    if (grewALot || every25) {
      printTopThreadPrefixes(label);
    }
  }

  private void printTopThreadPrefixes(String label) {
    try {
      Map<String, Integer> counts = new TreeMap<>();
      for (Thread t : allLiveThreads()) {
        if (t == null) {
          continue;
        }
        String name = t.getName();
        if (name == null) {
          continue;
        }
        String prefix = stripTrailingCounter(name);
        counts.merge(prefix, 1, Integer::sum);
      }
      List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
      sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
      StringBuilder sb = new StringBuilder();
      sb.append(PREFIX).append(" ").append(label).append(" topThreadPrefixes=");
      int limit = Math.min(10, sorted.size());
      for (int i = 0; i < limit; i++) {
        Map.Entry<String, Integer> e = sorted.get(i);
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(e.getKey()).append("=").append(e.getValue());
      }
      System.out.println(sb);
    } catch (Throwable t) {
      // Best-effort: never let diagnostics break the suite.
    }
  }

  private static String stripTrailingCounter(String name) {
    // Strips a trailing numeric suffix, with or without a leading dash, e.g.
    // "ForkJoinPool.commonPool-worker-3" -> "ForkJoinPool.commonPool-worker",
    // "pool-2-thread-1" -> "pool-2-thread".
    return name.replaceAll("-?\\d+$", "");
  }

  private static List<Thread> allLiveThreads() {
    try {
      return new ArrayList<>(Thread.getAllStackTraces().keySet());
    } catch (Throwable t) {
      return List.of();
    }
  }

  private static long countHttpClientThreads() {
    long count = 0;
    for (Thread t : allLiveThreads()) {
      if (t != null && t.isAlive() && t.getName() != null && t.getName().startsWith("HttpClient-")) {
        count++;
      }
    }
    return count;
  }

  // ---------------------------------------------------------------------------------------------
  // /proc/self/limits, /proc/self/status
  // ---------------------------------------------------------------------------------------------

  private static String readLimitLine(String label) {
    try {
      Path limits = Path.of("/proc/self/limits");
      if (!Files.isReadable(limits)) {
        return "n/a";
      }
      for (String line : Files.readAllLines(limits, StandardCharsets.UTF_8)) {
        if (line.startsWith(label)) {
          return line.trim();
        }
      }
      return "n/a";
    } catch (Throwable t) {
      return "n/a";
    }
  }

  private static String readOwnUid() {
    try {
      String uid = readStatusField(Path.of("/proc/self/status"), "Uid:");
      return uid == null ? "n/a" : uid;
    } catch (Throwable t) {
      return "n/a";
    }
  }

  /** The real uid (second whitespace-delimited token) from a {@code Uid:} line, or {@code null}. */
  private static String readStatusField(Path statusFile, String fieldLabel) {
    try {
      if (!Files.isReadable(statusFile)) {
        return null;
      }
      for (String line : Files.readAllLines(statusFile, StandardCharsets.UTF_8)) {
        if (line.startsWith(fieldLabel)) {
          String[] parts = line.trim().split("\\s+");
          return parts.length > 1 ? parts[1] : null;
        }
      }
      return null;
    } catch (Throwable t) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // cgroup v2 with v1 fallback
  // ---------------------------------------------------------------------------------------------

  private static String readCgroupValue(String v2Filename, String v1RelativePath) {
    try {
      Path v2Path = Path.of("/sys/fs/cgroup/" + v2Filename);
      if (Files.isReadable(v2Path)) {
        String value = firstLine(v2Path);
        if (value != null) {
          return value;
        }
      }
      if (v1RelativePath != null) {
        Path v1Path = Path.of("/sys/fs/cgroup/" + v1RelativePath);
        if (Files.isReadable(v1Path)) {
          String value = firstLine(v1Path);
          if (value != null) {
            return value;
          }
        }
      }
      return "n/a";
    } catch (Throwable t) {
      return "n/a";
    }
  }

  private static String firstLine(Path path) {
    try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
      return lines.findFirst().map(String::trim).orElse(null);
    } catch (Throwable t) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // /proc/<pid> scan for this uid's processes, threads and zombies
  // ---------------------------------------------------------------------------------------------

  private static final class ProcScanResult {
    final String uidProcs;
    final String uidThreads;
    final String zombies;

    ProcScanResult(String uidProcs, String uidThreads, String zombies) {
      this.uidProcs = uidProcs;
      this.uidThreads = uidThreads;
      this.zombies = zombies;
    }
  }

  private static ProcScanResult safeProcScan() {
    try {
      return scanProc();
    } catch (Throwable t) {
      return new ProcScanResult("n/a", "n/a", "n/a");
    }
  }

  private static ProcScanResult scanProc() {
    String ownUid = readOwnUid();
    if (ownUid == null || "n/a".equals(ownUid)) {
      return new ProcScanResult("n/a", "n/a", "n/a");
    }
    Path proc = Path.of("/proc");
    if (!Files.isDirectory(proc)) {
      return new ProcScanResult("n/a", "n/a", "n/a");
    }
    int procs = 0;
    long threads = 0;
    int zombies = 0;
    try (var stream = Files.list(proc)) {
      for (Path pidDir : stream.toList()) {
        String fileName = pidDir.getFileName().toString();
        if (!fileName.chars().allMatch(Character::isDigit)) {
          continue;
        }
        try {
          Path status = pidDir.resolve("status");
          if (!Files.isReadable(status)) {
            continue;
          }
          String pidUid = null;
          String threadsStr = null;
          String stateStr = null;
          for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
            if (line.startsWith("Uid:")) {
              String[] parts = line.trim().split("\\s+");
              pidUid = parts.length > 1 ? parts[1] : null;
            } else if (line.startsWith("Threads:")) {
              String[] parts = line.trim().split("\\s+");
              threadsStr = parts.length > 1 ? parts[1] : null;
            } else if (line.startsWith("State:")) {
              stateStr = line;
            }
          }
          if (ownUid.equals(pidUid)) {
            procs++;
            if (threadsStr != null) {
              try {
                threads += Long.parseLong(threadsStr);
              } catch (NumberFormatException ignored) {
                // skip
              }
            }
            if (stateStr != null && stateStr.contains("Z")) {
              zombies++;
            }
          }
        } catch (Throwable perPid) {
          // A process can disappear mid-scan, or be unreadable; skip it.
        }
      }
    } catch (Throwable t) {
      return new ProcScanResult("n/a", "n/a", "n/a");
    }
    return new ProcScanResult(String.valueOf(procs), String.valueOf(threads), String.valueOf(zombies));
  }

  // ---------------------------------------------------------------------------------------------
  // Small best-effort helpers
  // ---------------------------------------------------------------------------------------------

  private static String simpleNameOf(String className) {
    int lastDot = className.lastIndexOf('.');
    return lastDot < 0 ? className : className.substring(lastDot + 1);
  }

  private interface LongSupplierThrowing {
    long get() throws Throwable;
  }

  private interface IntSupplierThrowing {
    int get() throws Throwable;
  }

  private interface ListSupplierThrowing {
    List<String> get() throws Throwable;
  }

  private interface StringSupplierThrowing {
    String get() throws Throwable;
  }

  private interface BeanSupplier<T> {
    T get() throws Throwable;
  }

  private static long safeLong(LongSupplierThrowing supplier) {
    try {
      return supplier.get();
    } catch (Throwable t) {
      return -1;
    }
  }

  private static int safeInt(IntSupplierThrowing supplier) {
    try {
      return supplier.get();
    } catch (Throwable t) {
      return -1;
    }
  }

  private static List<String> safeList(ListSupplierThrowing supplier) {
    try {
      return supplier.get();
    } catch (Throwable t) {
      return List.of("n/a");
    }
  }

  private static String safeString(StringSupplierThrowing supplier) {
    try {
      return supplier.get();
    } catch (Throwable t) {
      return "n/a";
    }
  }

  private static <T> T safeBean(BeanSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (Throwable t) {
      return null;
    }
  }
}
