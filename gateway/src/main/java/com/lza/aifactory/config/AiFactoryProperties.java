package com.lza.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed view of {@code ai-factory.yml}. The file is imported via
 * {@code spring.config.import} in application.yml, then bound here with an empty
 * prefix so the top-level keys (git/agents/security/...) map directly. The same
 * file is also read by the bash pipeline (scripts/config/load-config.sh), so the
 * structure is kept identical on both sides.
 */
@ConfigurationProperties(prefix = "")
public class AiFactoryProperties {
    private int version = 1;
    private Workspace workspace = new Workspace();
    private Git git = new Git();
    private Agents agents = new Agents();
    private Security security = new Security();
    private Secrets secrets = new Secrets();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Workspace getWorkspace() { return workspace; }
    public void setWorkspace(Workspace workspace) { this.workspace = workspace; }
    public Git getGit() { return git; }
    public void setGit(Git git) { this.git = git; }
    public Agents getAgents() { return agents; }
    public void setAgents(Agents agents) { this.agents = agents; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public Secrets getSecrets() { return secrets; }
    public void setSecrets(Secrets secrets) { this.secrets = secrets; }

    public static class Workspace {
        private String workDir = "./.work";
        private String mode = "local";
        public String getWorkDir() { return workDir; }
        public void setWorkDir(String workDir) { this.workDir = workDir; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    public static class Git {
        private String provider = "github";
        private String repo;
        private String targetBranch = "main";
        private String branchPrefix = "ai";
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getRepo() { return repo; }
        public void setRepo(String repo) { this.repo = repo; }
        public String getTargetBranch() { return targetBranch; }
        public void setTargetBranch(String targetBranch) { this.targetBranch = targetBranch; }
        public String getBranchPrefix() { return branchPrefix; }
        public void setBranchPrefix(String branchPrefix) { this.branchPrefix = branchPrefix; }
    }

    public static class Agents {
        private int maxAgents = 3;
        private String planner = "codex";
        private String developer = "claude";
        private String reviewer = "codex";
        public int getMaxAgents() { return maxAgents; }
        public void setMaxAgents(int maxAgents) { this.maxAgents = maxAgents; }
        public String getPlanner() { return planner; }
        public void setPlanner(String planner) { this.planner = planner; }
        public String getDeveloper() { return developer; }
        public void setDeveloper(String developer) { this.developer = developer; }
        public String getReviewer() { return reviewer; }
        public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    }

    public static class Security {
        private List<String> allowRepositories = new ArrayList<>();
        private List<String> protectedBranches = new ArrayList<>(List.of("main", "master", "release/*"));
        private boolean requireHumanMerge = true;
        private boolean draftPullRequests = true;
        private String pullRequestLabel = "ai-generated";
        // Pre-flight gate: show a plain-language plan and wait for the user to
        // confirm before the AI starts building.
        private boolean confirmBeforeBuild = true;
        private int confirmationTimeoutMinutes = 30;
        // Local-folder import (mode=import + sourcePath) is DISABLED unless this is
        // set to a base directory; sourcePath must resolve inside it. Empty = off,
        // so an API caller can't copy arbitrary host directories into a result.
        private String importRootDir = "";
        public List<String> getAllowRepositories() { return allowRepositories; }
        public void setAllowRepositories(List<String> allowRepositories) { this.allowRepositories = allowRepositories; }
        public List<String> getProtectedBranches() { return protectedBranches; }
        public void setProtectedBranches(List<String> protectedBranches) { this.protectedBranches = protectedBranches; }
        public boolean isRequireHumanMerge() { return requireHumanMerge; }
        public void setRequireHumanMerge(boolean requireHumanMerge) { this.requireHumanMerge = requireHumanMerge; }
        public boolean isDraftPullRequests() { return draftPullRequests; }
        public void setDraftPullRequests(boolean draftPullRequests) { this.draftPullRequests = draftPullRequests; }
        public String getPullRequestLabel() { return pullRequestLabel; }
        public void setPullRequestLabel(String pullRequestLabel) { this.pullRequestLabel = pullRequestLabel; }
        public boolean isConfirmBeforeBuild() { return confirmBeforeBuild; }
        public void setConfirmBeforeBuild(boolean confirmBeforeBuild) { this.confirmBeforeBuild = confirmBeforeBuild; }
        public int getConfirmationTimeoutMinutes() { return confirmationTimeoutMinutes; }
        public void setConfirmationTimeoutMinutes(int m) { this.confirmationTimeoutMinutes = m; }
        public String getImportRootDir() { return importRootDir; }
        public void setImportRootDir(String importRootDir) { this.importRootDir = importRootDir; }
    }

    public static class Secrets {
        private String provider = "env";
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }
}
