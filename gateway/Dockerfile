FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
# Base tooling + kubectl (k8s mode) + Node.js 20 (for the AI CLIs).
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash curl jq git ca-certificates gettext-base \
    && curl -fsSL -o /usr/local/bin/kubectl \
        https://dl.k8s.io/release/v1.30.4/bin/linux/amd64/kubectl \
    && chmod +x /usr/local/bin/kubectl \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*
# The pipeline shells out to `claude` and `codex`; bundle them so the default
# `docker compose up` can actually run builds. Auth is supplied at runtime via
# ANTHROPIC_API_KEY / OPENAI_API_KEY (or mounted ~/.claude, ~/.codex).
RUN npm install -g @anthropic-ai/claude-code @openai/codex \
    && npm cache clean --force \
    && claude --version && codex --version
WORKDIR /app
COPY --from=build /app/target/ai-factory-gateway-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
