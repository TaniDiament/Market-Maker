# Market-Maker Cluster Deployment Guide

This document serves as the comprehensive manual for deploying and testing the **Market-Maker** distributed system on a 14-node air-gapped K3s cluster.

## Overview of the Environment
* **Infrastructure**: 3 Control Plane nodes (cp1-cp3), 11 Worker nodes (n1-n12).
* **Networking**: Isolated via Mango Router (192.168.8.1 subnet).
* **Deployment**: Managed via K3s (Alpine Linux) using imported image bundles.

---

## Step 1: Physical Setup & Connectivity
1.  **Network Connection**: Connect the nodes to the Mango Router switch. Connect your laptop to a LAN port or the router's Wi-Fi.
2.  **Laptop Configuration**: Ensure your laptop is on the same subnet (`192.168.8.x`).
3.  **IP Map**:
    * **Control Planes**: `192.168.8.11` (cp1), `192.168.8.12` (cp2), `192.168.8.13` (cp3)
    * **Workers**: `192.168.8.101` – `192.168.8.112` (Note: `.107` is skipped)

---

## Step 2: One-Time Security & Automation Setup
Perform these steps from PowerShell on your laptop to enable silent script execution.

### A. SSH Key-Based Authentication
1.  **Generate Keys**: `ssh-keygen -t rsa -b 4096` (Press Enter for all defaults).
2.  **Distribute Keys**: Run `.\scripts\setup-keys.ps1` to authorize your laptop on all 14 nodes.

### B. Passwordless Administrative Access
1.  **Configure `doas`**: Run `.\scripts\setup-doas.ps1`.
    * This adds `permit nopass sack` to `/etc/doas.d/doas.conf` on all nodes, allowing scripts to execute `k3s` and `rc-service` commands without prompts.

---

## Step 3: Cluster Lifecycle Management
The K3s services must be running for the container runtime socket (`containerd.sock`) to be active.

* **To Start the Cluster**: Run `.\scripts\start-cluster.ps1`.
    * *Note: Wait ~20 seconds after completion for services to fully initialize.*
* **To Stop the Cluster**: Run `.\scripts\stop-cluster.ps1`.
* **Hard Reset**: If networking is stuck, run `doas /usr/local/bin/k3s-killall.sh` on the affected nodes.

---

## Step 4: Air-Gapped Image Deployment
Since the nodes cannot reach Docker Hub, images must be bundled on a machine with internet and transferred manually.

### A. Create the Image Bundle (Laptop with Internet)
```powershell
docker pull eclipse-temurin:21-jre-alpine
docker pull postgres:16-alpine
docker pull zookeeper:3.9
docker pull rancher/mirrored-pause:3.6
docker pull rancher/local-path-provisioner:v0.0.35
docker pull rancher/mirrored-library-busybox:1.37.0
docker pull ghcr.io/headlamp-k8s/headlamp:v0.39.0
docker save -o dist/images.tar `
  market-maker:1.0.0 `
  eclipse-temurin:21-jre-alpine `
  postgres:16-alpine `
  zookeeper:3.9 `
  rancher/mirrored-pause:3.6 `
  rancher/local-path-provisioner:v0.0.35 `
  rancher/mirrored-library-busybox:1.37.0 `
  ghcr.io/headlamp-k8s/headlamp:v0.39.0
```

> **Note:** `local-path-provisioner` and `mirrored-library-busybox` are required for the postgres PVC to bind on an air-gapped cluster — without them, every JPA service in the stack will CrashLoopBackOff because postgres never reaches Ready. The exact tags above match this cluster's installed K3s; if K3s is upgraded, re-run `kubectl get deploy -n kube-system local-path-provisioner -o jsonpath='{...}'` to confirm the tags before bundling.
### B. Distribute and Import (Connected to Cluster)
Run 
```powershell
.\scripts\distribute-images.ps1. 
```
This script:

1) scps the 500MB+ images.tar to every node.

2) Imports images into the k8s.io namespace using doas k3s ctr -n k8s.io images import.

## Step 5: Application Deployment
1) Transfer Manifests to cp1:
Run 
```powershell
ssh sack@192.168.8.11 "mkdir -p /home/sack/marketmaker"
scp -r ./k8s sack@192.168.8.11:/home/sack/marketmaker/k8s
```
2) Apply via Kustomize:
```powershell
ssh sack@192.168.8.11 "doas env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -k /home/sack/marketmaker/k8s/"```

## Step 6: Verification & Quorum Troubleshooting
Monitor status from cp1:
```powershell
doas env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get pods -n market-maker -o wide
```

## Critical ZooKeeper Fixes in zookeeper.yaml:

### Parallel Startup: 
Set `podManagementPolicy: Parallel` in the `StatefulSet` to avoid the "OrderedReady" deadlock.

### DNS Resolution: 
Set `publishNotReadyAddresses: true` in the `zk-hs` service so peers can resolve each other's IPs before they are healthy.

### Anti-Affinity: 
Ensure ZK pods are pinned to `cp1`, `cp2`, and `cp3` to separate the coordination layer from worker traffic.

## Pod Reset:
If `mm` pods are in `CrashLoopBackOff` after ZK is ready, force a restart:
Run on cp1:
```powershell
doas env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl delete pods -l app=mm -n market-maker
```

## Step 7: Headlamp Cluster UI
A web UI for inspecting cluster state is deployed alongside the application via `k8s/headlamp.yaml` and exposed on **NodePort 30090** of every node.

1) Browse to `http://192.168.8.11:30090` (or any other node IP).
2) Generate a login token from cp1 and paste it into Headlamp's token field:
```powershell
ssh sack@192.168.8.11 "doas env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n market-maker create token headlamp"
```
The token is bound to a `cluster-admin` ClusterRoleBinding so Headlamp can see all namespaces (kube-system, market-maker, etc). Tokens are short-lived; re-run the command above to refresh.

## Step 8: Running the Integration Test
Execute `ClusterIntegrationWithSystemK8sTest.java`.

1) Network: Connect laptop to the Mango Router.

2) IntelliJ VM Options: You must point the test to the physical Master IPs. Add this to your Run Configuration:

Plaintext
`-Dzk.hosts=192.168.8.11:2181,192.168.8.12:2181,192.168.8.13:2181`

3) Run: The test will interface with the cluster, injecting orders and validating distributed state across the 14 nodes.

---

## Appendix A: Using Headlamp

Headlamp is a web-based Kubernetes dashboard. For this cluster it is exposed on **NodePort 30090** of every node and authenticates with a bearer token tied to the `headlamp` ServiceAccount in the `market-maker` namespace (granted `cluster-admin` via a ClusterRoleBinding).

### Logging In
1. Open `http://192.168.8.11:30090` (or any other node IP from the IP map) in a browser on a laptop attached to the Mango Router.
2. The login screen will ask for a **Cluster** and a **Token**. Leave the cluster picker on its default ("main"); it's the only entry the in-cluster build knows about.
3. Generate a token from cp1:
   ```powershell
   ssh sack@192.168.8.11 "doas env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n market-maker create token headlamp"
   ```
   Tokens default to ~1 hour. For longer sessions, append `--duration=24h`.
4. Paste the token, click **Authenticate**.

### Day-to-day Workflow
After authenticating, the most useful views for this stack:

- **Workloads → Pods** (filter namespace to `market-maker`): live status of `mm-0..mm-6`, `trading-state`, `exchange`, `exposure-reservation`, `external-publisher`, `postgres-0`, `zk-0..zk-2`. Click a pod to open its **Logs** tab — the same output `kubectl logs` would give, streamed live, with a **Previous** toggle for inspecting the last crashed container during a CrashLoopBackOff.
- **Workloads → StatefulSets**: the `mm` StatefulSet has a **Scale** button — useful for shrinking to 1 replica during a debug session and scaling back to 7. Pod identity (`mm-0` … `mm-N`) is preserved, so the symbol-shard assignment in ZK stays stable.
- **Storage → Persistent Volume Claims**: confirms `data-postgres-0` is `Bound`. If it's `Pending`, see *Step 5* — usually the local-path-provisioner image isn't on the node yet.
- **Cluster → Nodes**: 14 entries (cp1-cp3, n1-n12 minus n107). A node with `NotReady` here is your first signal something is wrong with k3s itself before you start digging into application logs.
- **Network → Services**: shows the NodePorts (30081-30087 for `mm-N-np`, 30180-30183 for the support services, 30090 for Headlamp itself) — handy when you forget which port maps to which pod from outside the cluster.
- **Config → ConfigMaps**: `symbols`, `hazelcast-members`. Editing in-place from the UI takes effect on the next pod restart, useful for adding a symbol without re-applying the kustomization.

### Exec Into a Pod
Headlamp's pod page has a **Terminal** tab that runs `kubectl exec -it … -- sh` inside the browser. For the JVM pods this lands you in the Spring app container; the helpful diagnostic commands:

```sh
# inside an mm pod
ls /config                        # confirms the symbols.txt mount
echo ruok | nc -w 2 zk-0.zk-hs 2181   # confirms ZK reachability from this pod
wget -qO- http://localhost:8080/actuator/health   # what the kubelet probe sees
```

### Token Expired
If the UI suddenly reports 401s on every panel, the token expired. Re-run the `kubectl create token` command and paste the new value via the **Settings → Cluster Settings → Token** field — no need to log out fully.

### Caveats Specific to This Cluster
- **Air-gapped pulls**: Headlamp's image (`ghcr.io/headlamp-k8s/headlamp:v0.39.0`) must be in `dist/images.tar` and distributed via `.\scripts\distribute-images.ps1`. If the pod is `ImagePullBackOff`, that's the cause — re-run the distribute step.
- **One Headlamp pod, one node**: there's only 1 replica. If the node hosting it goes down, NodePort 30090 on *other* nodes still routes correctly (k3s's klipper-lb forwards to wherever the pod actually is), but during the eviction/reschedule window the UI is briefly unavailable. Bumping `replicas: 2` in `headlamp.yaml` is fine if you want HA — Headlamp itself is stateless.
- **No persistent settings**: Headlamp stores user preferences in the browser, so cluster bookmarks / saved filters disappear if you switch laptops. Nothing to back up.