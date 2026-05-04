from os import getenv
from random import choice, randint
from uuid import uuid4

from locust import HttpUser, between, task


class CircleGuardUser(HttpUser):
    wait_time = between(1, 4)

    def on_start(self):
        self.anonymous_id = str(uuid4())
        self.jwt_token = None
        self.form_base_url = getenv("FORM_BASE_URL", self.host)
        self.gateway_base_url = getenv("GATEWAY_BASE_URL", self.host)
        self.promotion_base_url = getenv("PROMOTION_BASE_URL", self.host)
        self.dashboard_base_url = getenv("DASHBOARD_BASE_URL", self.host)

    @staticmethod
    def mark_server_errors_only(response):
        if response.status_code < 500:
            response.success()

    @task(4)
    def submit_health_survey(self):
        symptoms = choice([
            [],
            ["fever"],
            ["cough"],
            ["fever", "cough", "fatigue"],
        ])
        with self.client.post(
            f"{self.form_base_url}/api/v1/surveys",
            json={
                "anonymousId": self.anonymous_id,
                "hasFever": "fever" in symptoms,
                "hasCough": "cough" in symptoms,
                "otherSymptoms": ",".join(s for s in symptoms if s not in ["fever", "cough"]),
                "responses": {
                    "temperature": round(36.0 + randint(0, 25) / 10, 1),
                    "recentContact": bool(symptoms),
                },
            },
            name="form: submit health survey",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)

    @task(3)
    def list_active_questionnaire(self):
        with self.client.get(
            f"{self.form_base_url}/api/v1/questionnaires/active",
            name="form: active questionnaire",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)

    @task(3)
    def list_buildings(self):
        with self.client.get(
            f"{self.promotion_base_url}/api/v1/buildings",
            name="promotion: list buildings",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)

    @task(2)
    def fetch_admin_settings(self):
        with self.client.get(
            f"{self.promotion_base_url}/api/v1/admin/settings",
            name="promotion: admin settings",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)

    @task(2)
    def fetch_dashboard_summary(self):
        with self.client.get(
            f"{self.dashboard_base_url}/api/v1/analytics/summary",
            name="dashboard: campus summary",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)

    @task(1)
    def fetch_health_stats(self):
        with self.client.get(
            f"{self.promotion_base_url}/api/v1/health-status/stats",
            name="promotion: health stats",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)

    @task(1)
    def validate_gate_qr(self):
        with self.client.post(
            f"{self.gateway_base_url}/api/v1/gate/validate",
            json={"token": self.jwt_token or "invalid-load-test-token"},
            name="gateway: validate invalid QR token",
            catch_response=True,
        ) as response:
            self.mark_server_errors_only(response)
