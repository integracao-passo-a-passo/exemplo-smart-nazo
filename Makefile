KAMEL?=kamel
NAMESPACE=smart-nazo
CONFIG_MAP_NAME=smart-nazo-config

INTERNAL_REGISTRY?=registry-host
INTERNAL_MAVEN_REPO?=http://maven-repo
INTERNAL_REGISTRY_PROJECT?=opiske
INTERNAL_REGISTRY_INSECURE?=true
CAMEL_K_VERSION=1.5.0-SNAPSHOT
KAMEL?=$(HOME)/go/src/camel-k/kamel

INTERNAL_REGISTRY_SECRET_NAME?=internal-registry-secret

OPERATOR_IMAGE_NAME?=$(INTERNAL_REGISTRY)/$(INTERNAL_REGISTRY_PROJECT)/camel-k:$(CAMEL_K_VERSION)
EXTERNAL_BASE_IMAGE=adoptopenjdk/openjdk11:slim
BASE_IMAGE?=$(INTERNAL_REGISTRY)/adoptopenjdk/openjdk11:slim
ADDITIONAL_OPTS?=
BUILD_STRATEGY?=routine

refresh-openjdk11:
	docker rmi -f $(EXTERNAL_BASE_IMAGE) $(BASE_IMAGE)
	docker pull $(EXTERNAL_BASE_IMAGE)
	docker tag $(EXTERNAL_BASE_IMAGE) $(BASE_IMAGE)
	docker push $(BASE_IMAGE)

create-project-k8s:
	kubectl create namespace $(NAMESPACE) || true
	kubectl config set-context --current --namespace=$(NAMESPACE)

create-config-map-k8s:
	kubectl -n $(NAMESPACE) create configmap $(CONFIG_MAP_NAME) --from-file=config/application.properties || true

delete-config-map-k8s:
	kubectl -n $(NAMESPACE) delete configmap $(CONFIG_MAP_NAME)

create-project-oc:
	oc new-project $(NAMESPACE) || true

create-config-map-oc:
	oc create configmap $(CONFIG_MAP_NAME) --from-file=config/application.properties || true

setup-advanced: create-project-k8s create-config-map-k8s
	$(KAMEL) install -n $(NAMESPACE) --force --olm=false --maven-repository $(INTERNAL_MAVEN_REPO) --registry $(INTERNAL_REGISTRY) --organization $(INTERNAL_REGISTRY_PROJECT) --base-image $(BASE_IMAGE) --registry-insecure true --build-strategy $(BUILD_STRATEGY) --build-publish-strategy Spectrum $(ADDITIONAL_OPTS)

setup-simple:
	echo "Use: https://camel.apache.org/camel-k/latest/installation/installation.html"

run:
	k create -f kamelets/source/no2-preciate-filter-binding.yaml
	k create -f kamelets/source/pm10-predicate-filter-binding.yaml
	k create -f kamelets/source/catch-all-kafka-source-binding.yaml
	kamel run --dev --build-property quarkus.arc.unremovable-types=com.fasterxml.jackson.databind.ObjectMapper --configmap smart-nazo-config SmartNazoBot.java

clean:
	$(KAMEL) reset --namespace $(NAMESPACE)
	$(KAMEL) uninstall --all --global --olm=false  || true
	kubectl delete clusterrole camel-k:edit  || true
	kubectl delete operator camel-k.$(NAMESPACE)  || true
	kubectl delete namespace $(NAMESPACE) || true