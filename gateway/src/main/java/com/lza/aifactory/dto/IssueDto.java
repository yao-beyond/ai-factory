package com.lza.aifactory.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

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
    private Integer maxAgents = 3;

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
    public Integer getMaxAgents() { return maxAgents; }
    public void setMaxAgents(Integer maxAgents) { this.maxAgents = maxAgents; }
}
