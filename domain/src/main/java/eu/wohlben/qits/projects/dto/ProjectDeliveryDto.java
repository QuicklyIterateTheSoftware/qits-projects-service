package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * A project's front desk delivery loop on the wire (epic qits-1440), as its wrapper's {@code
 * .config/qits/project.yml} declares it under {@code front_desk.delivery}. Never null on a {@link
 * ProjectDto}: a project that declares nothing reads {@code enabled: false} with every value at its
 * default.
 *
 * @param enabled whether the desk runs the loop
 * @param maxRunning how many of the project's items the loop keeps running at once, 1..20
 * @param startRefined whether the desk may move a REFINED item to READY_FOR_DEV itself
 * @param approves the approval requirements the desk may satisfy, sorted; empty is none
 * @param interval how often the loop ticks, an ISO-8601 duration ({@code PT10M})
 */
public record ProjectDeliveryDto(
    boolean enabled, int maxRunning, boolean startRefined, List<String> approves, String interval) {}
