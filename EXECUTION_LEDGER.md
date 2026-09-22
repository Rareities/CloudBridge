# CloudBridge + rclone execution ledger

This ledger records implementation evidence for `CloudBridge-rclone-Luna-Master-Handoff.md`.
It is maintained separately from the source checkout while the projectless task is being
bootstrapped, and is intended to move into the reviewed repository documentation once a
real Git checkout is available.

## Standing instructions

- The complete handoff at `work/CloudBridge-rclone-Luna-Master-Handoff.md` is authoritative.
- Work one bounded package at a time; the current package is WP00.
- Preserve Bisync, Proton Drive, scheduling, Obsidian and useful CloudBridge functionality.
- Fix defects at their owning layer and test rclone independently before app integration.
- Missing device or live Proton access is `NOT RUN`, never a pass.
- Use disposable test data only. `Proton:RoundSync-Test` is not permission to delete an existing directory.
- The final app integration must use Rareities/rclone at an immutable, reproducible revision;
  a moving branch or silent fallback to `thies2005/rclone` is not acceptable.

## 2026-09-23 — WP00 baseline refresh

### Source identity

| Component | Remote | Branch | Current head evidence | Local source |
|---|---|---|---|---|
| CloudBridge | `https://github.com/Rareities/CloudBridge.git` | `master` | `c492876258ca841232229249519abe92ff77c3a4` from GitHub branch API | `work/CloudBridge` extracted from the master archive |
| rclone | `https://github.com/Rareities/rclone.git` | `master` | `1583cce1e28340e5d064ed955179f5f2b31e7757` from GitHub branch API | `work/rclone` extracted from the master archive |

Archive SHA-256 values:

- CloudBridge master archive: `C849E8C5307E58727AA4CB497B8DD4383191FC9994BE4E6FC5A04ED7D96B2324`
- rclone master archive: `9CE48D71455F76CECEC498DE0102E11358FDEA5CCEAEA842D08C6F5555B17208`
- handoff: `7179ECAFD7D142D478DD46A0495AC1D4D2C57F431E70703F13B7D77359D791B3`

GitHub refresh on 2026-09-22/23 returned no open or closed PR entries and no workflow runs
for either Rareities repository. This is current connector evidence, not proof that CI is
configured or that the source builds.

The bundled Git executable has no `git-remote-https` helper. Direct archive download required
approved network access. The archives did not contain upstream `.git` history, so each source
tree was initialized locally from the verified archive and given an evidence-only baseline
commit; those local commits are not substitutes for the remote commit SHAs above:

- CloudBridge local import: `800bbc19cf529beffb84ea8336dd27fc68116e13`
- rclone local import: `b9ba7bb3bdd75ee01643c6d1dae3024fcd6cba61`

No user changes were discarded. Do not push these archive-import commits as if they were
upstream history.

### Current app integration

`work/CloudBridge/gradle.properties` currently contains:

- `de.schuelken.cloudbridge.rCloneRepoUrl=https://github.com/thies2005/rclone.git`
- `de.schuelken.cloudbridge.rCloneRef=94f543c9d4df4f7a29c87c9b76abf7bb341b5d33`
- app Go requirement `1.25.0`
- NDK `29.0.14206865`, compiler/toolchain API `33`, min/compile/target SDK `23/36/36`

`work/rclone/go.mod` requires Go `1.26.0`. Updating the app pin to Rareities/rclone therefore
requires a deliberate toolchain/build compatibility change and independent engine validation;
it must not be hidden behind a moving ref or a fallback URL.

### Environment and test gates

| Gate | Result | Evidence / consequence |
|---|---|---|
| Read repository instructions | PASS | CloudBridge and rclone `AGENTS.md` read in full before source changes |
| Refresh repositories, PRs and CI | PASS | GitHub API evidence recorded above |
| Source archive acquisition | PASS with limitation | Immutable branch SHA and archive hash recorded; no local Git history |
| Standalone rclone build/tests | NOT RUN | Go executable is not installed or on PATH |
| Android unit/lint/debug build | NOT RUN | Current Java is OpenJDK 8; project requires JDK 17; no local.properties/NDK was found |
| Release/R8/signing/ APK inspection | NOT RUN | No compatible Android build toolchain or signing evidence |
| Samsung Galaxy S26 / One UI acceptance | NOT RUN | No acceptance device access in this environment |
| Live Proton Drive disposable-area tests | NOT RUN | No Proton credentials or approved disposable remote area |

### WP00 acceptance status

Baseline identity, current app pin, repository instructions, CI/PR refresh and gap list are
recorded in the local evidence commits above. WP00 build/test acceptance remains incomplete
because the required toolchains and full upstream Git metadata are unavailable. No feature
implementation is claimed. The next bounded work requires provisioning Go 1.26, JDK 17,
Android NDK 29 and a full Git checkout, or an explicitly documented equivalent; independent
rclone tests may proceed before app integration.

## Next package

WP01: independently update and validate the standalone Rareities/rclone engine. Before changing
the CloudBridge pin, compare the fork with the selected upstream snapshot, preserve required
Internxt/Proton behavior, record patch provenance, run engine tests, and resolve the Go 1.26
toolchain requirement.
