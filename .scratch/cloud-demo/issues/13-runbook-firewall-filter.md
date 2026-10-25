# 13. Runbook section 7: firewall check lists rules of other networks

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Found during the first cloud setup on 2026-10-25, recorded under AGENTS.md rule 7.

`gcloud compute firewall-rules list --filter=network:signaldock-demo` in `docs/runbook-demo.md` section 7 matches by substring on the network URL. The URL holds the project name `signaldock-demo`, so the output also lists the four `default-allow-*` rules of the `default` network. A reader can think the demo VPC has six rules. Use a filter that matches the network name exactly (for example `--format='value(name,network.basename())'` and check the second column, or `--filter="network~/networks/signaldock-demo$"`), and run it to show it lists only the two rules.

The project also has the GCP `default` network with 22 and 3389 open to the internet. No VM uses it. Decide whether the runbook tells the owner to delete it.
