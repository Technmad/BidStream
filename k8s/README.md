# Kubernetes manifests (PDR §23.2)

Apply in this order:

```
kubectl apply -f k8s/configmap-and-hpa.yaml
kubectl apply -f k8s/secret.example.yaml   # replace with your real secret first
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/networkpolicy.yaml
```

- `deployment.yaml` - the app Deployment (3 replicas) and its ClusterIP Service. Liveness/readiness
  hit the app's own `/actuator/health/liveness` and `/actuator/health/readiness`, which are wired
  to real DB/Kafka/Redis health indicators (`application.yml`), not just JVM-up. The container
  image tag is a placeholder (`bidstream:0.1.0-SNAPSHOT`) that CI/CD must replace with an immutable
  tag or digest at deploy time; the pod/container run with a hardened `securityContext`
  (non-root, read-only root filesystem, all capabilities dropped) backed by an `emptyDir` at `/tmp`
  for the embedded Tomcat work dir.
- `configmap-and-hpa.yaml` - non-secret config (in-cluster DB/Kafka/Redis hostnames via Spring
  Boot's relaxed env-var binding), a CPU-based HPA (3-10 replicas), and a PodDisruptionBudget
  (`minAvailable: 2`) so a node drain can't take every Kafka-consumer-group member down at once.
- `secret.example.yaml` - documents the expected Secret keys. Never commit real values here;
  provision the actual Secret out-of-band.
- `networkpolicy.yaml` - restricts the bidstream pod to inbound traffic on its service port
  (8080) and outbound traffic to DNS plus Postgres/Redis/Kafka ports only. Those three dependencies
  run outside this cluster (see below), so egress is scoped by port via `ipBlock: 0.0.0.0/0` rather
  than by namespace/pod labels - tighten this if/when they move in-cluster.
- Postgres, Redis, and Kafka themselves aren't manifested here - this app is stateless and assumes
  managed or separately-operated instances of each (RDS/Cloud SQL, ElastiCache/MemoryStore, MSK/
  Confluent Cloud, or your own StatefulSets), matching how `docker/docker-compose.yml` stands in
  for them locally.

## Known limitation: JWT keys are not shared across replicas

`JwtKeyConfig` generates a fresh RSA key pair in-process on every startup (see
`src/main/java/com/bidstream/config/JwtKeyConfig.java`). With more than one replica, a token
issued by one pod will fail RS256 verification on another - this manifests as intermittent 401s
under the HPA's default `minReplicas: 3`. Before running this at more than a single replica for
real, load the signing key from a shared secret (or a KMS-backed provider) instead of generating
it at boot. Tracked here rather than silently shipped as if it were already solved.
