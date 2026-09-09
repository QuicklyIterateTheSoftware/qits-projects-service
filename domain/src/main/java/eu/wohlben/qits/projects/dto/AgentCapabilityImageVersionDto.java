package eu.wohlben.qits.projects.dto;

/**
 * An image version that reported a harness and was not the newest — named, so the editor can say
 * which build's answer it is <em>not</em> showing.
 *
 * <p>It exists because the alternative was a union. Where two builds of the workspace image report
 * different model sets for the same harness, merging them offers a catalogue no single binary has;
 * so the newest report wins whole, and the losers appear here as a fact rather than as content. An
 * operator who sees a model they expected missing can read this and know a second build exists.
 *
 * @param imageVersion the image build; empty when the reporter could not name its own
 * @param reportedAt when that report arrived, ISO-8601
 */
public record AgentCapabilityImageVersionDto(String imageVersion, String reportedAt) {}
