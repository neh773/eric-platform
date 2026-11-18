# oip-gateway Helm chart

Deploys one gateway on Kubernetes. Doc source: FEATURES GAT-011.

- The data directory (`/data`: configuration, history database, secrets key, logs) is a PersistentVolumeClaim, so it survives pod restarts and image upgrades. The Deployment uses the `Recreate` strategy and one replica because a single process owns the directory. Hot standby is the gateway's own redundancy setting, not a second replica of this chart.
- Liveness, readiness and startup probes call `/api/v1/health`.
- The administrator password comes from an existing Secret (`admin.existingSecret`, key `admin.secretKey`). The chart never creates or prints it.
- `gatewayYaml` is mounted from a ConfigMap and copied to `/data/gateway.yaml` on every start.

## Install

Build the repository `Dockerfile`, push the image, then:

```sh
kubectl create secret generic oip-admin --from-literal=password="$(openssl rand -base64 24)"
helm install gw ./packaging/helm/oip-gateway \
  --set image.repository=registry.example.net/oip-gateway --set image.tag=0.1.0 \
  --set admin.existingSecret=oip-admin
kubectl port-forward svc/gw-oip-gateway 8088:8088
```

A values file works the same way:

```yaml
image: {repository: registry.example.net/oip-gateway, tag: "0.1.0"}
admin: {existingSecret: oip-admin}
service: {type: LoadBalancer, port: 80}
persistence: {size: 50Gi, storageClass: fast-ssd}
resources:
  requests: {cpu: "1", memory: 2Gi}
  limits: {memory: 4Gi}
gatewayYaml: |
  logging:
    maxFileKb: 10240
    maxFiles: 8
```

## Values

| Key | Default | Meaning |
|---|---|---|
| `image.repository`, `image.tag`, `image.pullPolicy` | `oip-gateway`, appVersion, `IfNotPresent` | the gateway image |
| `profile` | `standard` | `edge`, `standard` or `hub` |
| `admin.existingSecret`, `admin.secretKey` | empty, `password` | Secret that seeds user `admin` on first start |
| `gatewayYaml` | empty | contents of `gateway.yaml`, from a ConfigMap |
| `service.type`, `service.port`, `service.nodePort` | `ClusterIP`, `8088`, none | how the gateway is exposed |
| `httpPort` | `8088` | port inside the pod |
| `gatewayNetwork.enabled`, `gatewayNetwork.port` | `false`, `8060` | also expose the gateway-network listener |
| `persistence.*` | enabled, 10Gi, `ReadWriteOnce` | the data directory claim; `existingClaim` reuses one |
| `resources` | 500m CPU, 1Gi request, 2Gi limit | pod resources |
| `extraEnv` | `[]` | extra environment, such as `OIP_HTTP_HTTPS` (see docs/ops/https.md) |

TLS is best terminated at an Ingress or the Service load balancer; see docs/ops/reverse-proxy.md for the headers
the gateway needs and `http.trustedProxies` in `gateway.yaml`.

## Checks

`helm lint packaging/helm/oip-gateway` and `helm template gw packaging/helm/oip-gateway` render the chart.
`python3 -m unittest scripts/tests/test_helm_chart.py` runs without Helm: it checks the files parse and carry the
required keys (probes, claim, Secret reference, ConfigMap mount).
