GATEWAY_IMAGE ?= your-registry/ai-factory-gateway:0.1.0
AGENT_IMAGE ?= your-registry/ai-agent-orchestrator:0.1.0
NS ?= ai-factory

.PHONY: test test-scripts coverage build-gateway build-agent build apply-namespace apply-secrets apply-rbac \
        apply-configmaps apply-gateway apply-k8s smoke-local

# Run the gateway test suite (same command CI uses).
test:
	cd gateway && ./mvnw -B -ntp test

# Behavior tests for the pipeline shell scripts (containment/fallback paths).
test-scripts:
	bash scripts/tests/explainer-test.sh

# Run tests + produce the JaCoCo coverage report.
coverage:
	cd gateway && ./mvnw -B -ntp verify
	@echo "Coverage report: gateway/target/site/jacoco/index.html"

build-gateway:
	docker build -t $(GATEWAY_IMAGE) gateway

build-agent:
	docker build -t $(AGENT_IMAGE) -f Dockerfile.agent .

build: build-gateway build-agent

apply-namespace:
	kubectl apply -f k8s/00-namespace.yaml

apply-secrets:
	kubectl apply -f k8s/01-secrets.example.yaml

apply-rbac:
	kubectl apply -f k8s/05-agent-rbac.yaml

# Pack the pipeline scripts and Job template into ConfigMaps so the gateway
# pod can mount them. Re-run on every script change.
apply-configmaps:
	kubectl -n $(NS) create configmap ai-pipeline-scripts \
		--from-file=scripts/ \
		--dry-run=client -o yaml | kubectl apply -f -
	kubectl -n $(NS) create configmap ai-job-template \
		--from-file=04-orchestrator-job-template.yaml=k8s/04-orchestrator-job-template.yaml \
		--dry-run=client -o yaml | kubectl apply -f -

apply-gateway:
	kubectl apply -f k8s/03-gateway-deployment.yaml

apply-k8s: apply-namespace apply-secrets apply-rbac apply-configmaps apply-gateway

# Local smoke test: starts the gateway with an isolated work dir and the
# embedded run-task.sh as the pipeline. Requires REPO_URL exported.
smoke-local:
	@test -n "$$REPO_URL" || (echo "Set REPO_URL first" && exit 1)
	cd gateway && \
	  AI_FACTORY_WORK_DIR=$$(pwd)/../.work \
	  AI_FACTORY_PIPELINE_SCRIPT=$$(pwd)/../scripts/run-task.sh \
	  ./mvnw spring-boot:run
