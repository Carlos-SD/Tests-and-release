#!/usr/bin/env bash
set -euo pipefail

services=(
  "circleguard-auth-service:auth-service"
  "circleguard-identity-service:identity-service"
  "circleguard-promotion-service:promotion-service"
  "circleguard-notification-service:notification-service"
  "circleguard-form-service:form-service"
  "circleguard-file-service:file-service"
  "circleguard-gateway-service:gateway-service"
  "circleguard-dashboard-service:dashboard-service"
)

eval "$(minikube docker-env)"

./gradlew clean bootJar -x test

for entry in "${services[@]}"; do
  service="${entry%%:*}"
  image="${entry##*:}"
  docker build -t "circleguard/${image}:latest" "services/${service}/"
done

echo "Built CircleGuard images inside the Minikube Docker daemon."
