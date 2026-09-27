package eu.wohlben.qits.entities.campaign;

/**
 * <b>The one version order in this service</b> — the release floor a DEPLOYMENT_ACTIVE or
 * SCM_RELEASE criterion names ({@code minimumVersion}) is compared with this and nothing else.
 *
 * <p>The semantics are qits-deployments' {@code deployments/control/Versions.compare}, restated
 * rather than imported (this module depends on no other context):
 *
 * <ul>
 *   <li>split on {@code .};
 *   <li>a segment pair compares numerically when both parse as a {@code long}, else lexically
 *       ({@link String#compareTo});
 *   <li>when one version is a prefix of the other, the shorter is lower ({@code 2026.908 <
 *       2026.908.1});
 *   <li>null or blank is lowest.
 * </ul>
 *
 * <p>Plain string order is wrong for this estate's unpadded versions: {@code 2026.930.9} sorts
 * after {@code 2026.930.10000} lexically, and {@code 2026.1001.1} before {@code 2026.930.235959}.
 */
public final class ReleaseVersions {

  private ReleaseVersions() {}

  /** Negative, zero or positive as {@code a} is lower than, equal to or higher than {@code b}. */
  public static int compare(String a, String b) {
    boolean noA = a == null || a.isBlank();
    boolean noB = b == null || b.isBlank();
    if (noA || noB) {
      return noA == noB ? 0 : noA ? -1 : 1;
    }
    String[] left = a.trim().split("\\.", -1);
    String[] right = b.trim().split("\\.", -1);
    int shared = Math.min(left.length, right.length);
    for (int i = 0; i < shared; i++) {
      int order = compareSegment(left[i], right[i]);
      if (order != 0) {
        return order;
      }
    }
    return Integer.compare(left.length, right.length);
  }

  /**
   * Whether {@code version} is at or above {@code floor}. No floor ({@code null} or blank) is
   * always met; a blank {@code version} never meets a floor — a deployment that names no version
   * cannot be said to be at least anything.
   */
  public static boolean atLeast(String version, String floor) {
    if (floor == null || floor.isBlank()) {
      return true;
    }
    if (version == null || version.isBlank()) {
      return false;
    }
    return compare(version, floor) >= 0;
  }

  private static int compareSegment(String a, String b) {
    Long left = asLong(a);
    Long right = asLong(b);
    if (left != null && right != null) {
      return Long.compare(left, right);
    }
    return a.compareTo(b);
  }

  private static Long asLong(String segment) {
    try {
      return Long.parseLong(segment);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
