package com.lza.aifactory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public class IssueDto {
    @NotBlank
    private String source;
    private String externalId;
    @NotBlank
    private String title;
    @NotBlank
    private String description;
    private String priority = "P2";
    private List<String> labels = List.of();
    private String repo;
    private String targetBranch = "main";
    // "existing" works on a git repo and opens a PR (needs a git account/token).
    // "new"      generates a brand-new project locally — no git needed.
    // "import"   improves an uploaded/local existing project locally — no git needed.
    private String mode = "existing";
    // For mode=import via a local folder: an absolute path to copy as the seed.
    private String sourcePath;
    @Min(1)
    @Max(10)
    private Integer maxAgents = 3;
    private String projectType = "recommend";
    // Discovery origin: the chosen starter-card id. The server re-derives the
    // authoritative capabilityBoundary from this id (never trusts a client-supplied
    // boundary), so a discovery-originated task carries hard constraints into the
    // pipeline (issue.json + env), not just prose in the description.
    @Size(max = 128)
    private String discoveryCardId;
    // Server-derived structured capability boundary (written to issue.json). Any
    // value sent by the client is ignored and overwritten from the card library.
    private Map<String, Object> capabilityBoundary;
    // Governance profile that governs this task's change-control (capability grants,
    // gates, independence). Resolved server-side against the profile library; an
    // unknown id is rejected. Default keeps existing behaviour (standard-app).
    @Size(max = 128)
    private String governanceProfileId = "standard-app";

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getTargetBranch() { return targetBranch; }
    public void setTargetBranch(String targetBranch) { this.targetBranch = targetBranch; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public Integer getMaxAgents() { return maxAgents; }
    public void setMaxAgents(Integer maxAgents) { this.maxAgents = maxAgents; }
    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
    public String getDiscoveryCardId() { return discoveryCardId; }
    public void setDiscoveryCardId(String discoveryCardId) { this.discoveryCardId = discoveryCardId; }
    public Map<String, Object> getCapabilityBoundary() { return capabilityBoundary; }
    public void setCapabilityBoundary(Map<String, Object> capabilityBoundary) { this.capabilityBoundary = capabilityBoundary; }
    public String getGovernanceProfileId() { return governanceProfileId; }
    public void setGovernanceProfileId(String governanceProfileId) { this.governanceProfileId = governanceProfileId; }
}
