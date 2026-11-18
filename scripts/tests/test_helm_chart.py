"""Structure checks for the Helm chart (GAT-011).

Helm is not run here: this machine may not have it, and the checks must not depend on a cluster. The tests
parse Chart.yaml and values.yaml, strip Go-template actions from each template to get plain YAML, parse that, and
check the keys the deployment relies on (health probes, data volume claim, admin Secret reference, ConfigMap
mount). `helm lint` and `helm template` remain the full check; see the chart README.
"""
import re
import unittest
from pathlib import Path

import yaml

CHART = Path(__file__).resolve().parents[2] / "packaging" / "helm" / "oip-gateway"
ACTION = re.compile(r"\{\{-?.*?-?\}\}", re.S)
BLOCK_OPEN = re.compile(r"\{\{-?\s*(if|with|range|define)\b")
BLOCK_END = re.compile(r"\{\{-?\s*end\b")


def plain_yaml(text: str):
    """Drop lines that hold only a template action, and turn inline actions into a scalar."""
    out = []
    for line in text.splitlines():
        if ACTION.sub("", line).strip() == "" and "{{" in line:
            continue
        out.append(ACTION.sub("x", line))
    return yaml.safe_load("\n".join(out))


def template(name: str):
    return plain_yaml((CHART / "templates" / name).read_text())


class ChartFiles(unittest.TestCase):
    def test_chart_metadata(self):
        chart = yaml.safe_load((CHART / "Chart.yaml").read_text())
        for key in ("apiVersion", "name", "version", "appVersion"):
            self.assertIn(key, chart)
        self.assertEqual(chart["apiVersion"], "v2")
        self.assertEqual(chart["name"], "oip-gateway")

    def test_values_carry_every_documented_knob(self):
        values = yaml.safe_load((CHART / "values.yaml").read_text())
        for key in ("image", "profile", "admin", "gatewayYaml", "service", "httpPort", "persistence", "resources", "probes", "extraEnv"):
            self.assertIn(key, values)
        self.assertIn("repository", values["image"])
        self.assertEqual(values["service"]["type"], "ClusterIP")
        self.assertIn("existingSecret", values["admin"])
        self.assertEqual(values["admin"]["existingSecret"], "", "no password and no Secret name is committed")
        self.assertTrue(values["persistence"]["enabled"])
        self.assertEqual(values["probes"]["path"], "/api/v1/health")
        text = (CHART / "values.yaml").read_text().lower()
        self.assertNotIn("password:", text.replace("secretkey: password", ""))

    def test_template_actions_are_balanced(self):
        for path in (CHART / "templates").iterdir():
            text = path.read_text()
            opens = len(BLOCK_OPEN.findall(text))
            ends = len(BLOCK_END.findall(text))
            self.assertEqual(opens, ends, f"{path.name}: {opens} blocks opened, {ends} closed")

    def test_templates_parse_as_yaml(self):
        for name in ("configmap.yaml", "pvc.yaml", "service.yaml", "deployment.yaml"):
            self.assertIsInstance(template(name), dict, name)


class DeploymentTemplate(unittest.TestCase):
    dep = template("deployment.yaml")
    pod = dep["spec"]["template"]["spec"]
    gateway = next(c for c in pod["containers"] if c["name"] == "gateway")

    def test_one_writer_owns_the_data_directory(self):
        self.assertEqual(self.dep["kind"], "Deployment")
        self.assertEqual(self.dep["spec"]["replicas"], 1)
        self.assertEqual(self.dep["spec"]["strategy"]["type"], "Recreate")

    def test_probes_use_the_health_route(self):
        for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
            self.assertIn(probe, self.gateway)
            self.assertIn("httpGet", self.gateway[probe])
        text = (CHART / "templates" / "deployment.yaml").read_text()
        self.assertEqual(text.count(".Values.probes.path"), 3)

    def test_data_directory_is_a_claim_mounted_at_data(self):
        self.assertIn({"name": "data", "mountPath": "/data"}, self.gateway["volumeMounts"])
        volume = next(v for v in self.pod["volumes"] if v["name"] == "data")
        self.assertIn("persistentVolumeClaim", volume)
        self.assertIn("claimName", volume["persistentVolumeClaim"])
        self.assertIn("emptyDir", volume, "persistence.enabled=false falls back to an emptyDir")
        env = {e["name"]: e for e in self.gateway["env"]}
        self.assertEqual(env["ERIC_DATA_DIR"]["value"], "/data")

    def test_admin_password_comes_from_a_secret_reference(self):
        env = {e["name"]: e for e in self.gateway["env"]}
        ref = env["ERIC_ADMIN_PASSWORD"]["valueFrom"]["secretKeyRef"]
        self.assertIn("name", ref)
        self.assertIn("key", ref)
        self.assertNotIn("value", env["ERIC_ADMIN_PASSWORD"])

    def test_gateway_yaml_is_mounted_from_a_configmap_and_copied_in(self):
        init = self.pod["initContainers"][0]
        self.assertIn("/data/gateway.yaml", init["command"])
        volume = next(v for v in self.pod["volumes"] if v["name"] == "config")
        self.assertIn("configMap", volume)
        cm = template("configmap.yaml")
        self.assertEqual(cm["kind"], "ConfigMap")
        self.assertIn("gateway.yaml", cm["data"])

    def test_runs_as_the_image_user_without_extra_privilege(self):
        values = yaml.safe_load((CHART / "values.yaml").read_text())
        self.assertEqual(values["podSecurityContext"]["runAsUser"], 10001)
        self.assertTrue(values["podSecurityContext"]["runAsNonRoot"])
        self.assertFalse(values["containerSecurityContext"]["allowPrivilegeEscalation"])


class ServiceAndClaim(unittest.TestCase):
    def test_service_type_and_ports_come_from_values(self):
        svc = template("service.yaml")
        self.assertEqual(svc["kind"], "Service")
        text = (CHART / "templates" / "service.yaml").read_text()
        self.assertIn(".Values.service.type", text)
        self.assertIn(".Values.service.port", text)
        self.assertEqual(svc["spec"]["ports"][0]["name"], "http")

    def test_claim_requests_storage_and_access_modes(self):
        pvc = template("pvc.yaml")
        self.assertEqual(pvc["kind"], "PersistentVolumeClaim")
        self.assertIn("accessModes", pvc["spec"])
        self.assertIn("storage", pvc["spec"]["resources"]["requests"])


if __name__ == "__main__":
    unittest.main()
