# 20. Issue 15 review findings left open (Nit)

Status: ready-for-agent
Type: task

Found by the local review of the issue 15 branch. None blocks a gate.

1. (Nit) Root `package.json` `overrides` pins `minimatch` 11.0.8 for `@typescript-eslint/typescript-estree`, outside its declared range `^10.2.2`. Remove the override when typescript-eslint itself asks for minimatch 11.0.1 or later. Until then, Renovate does not update the pin.
2. (Nit) `scripts/update-gradle-verification.sh` only adds entries, so each AGP bump leaves stale components and OSV findings in `verification-metadata.xml`. Consider making the script empty the `<components>` block first (ADR-0038 Gaps), in its own PR.
