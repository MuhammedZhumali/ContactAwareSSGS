# Final research input inventory

Inventory date: 2026-10-25. Owner: `final_matrix_inputs`. This is a read-only inventory and a proposed registration, not a completed experiment or a new preregistration. No JVM, build, simulation, source change, network acquisition, or policy tuning was performed. Paths below are repository-relative.

## Checked frozen models

For all nine models below, the existing bytes match `target/research-validation/models/manifest.json`; the original source file also exists and matches that manifest's `sourceSha256`. The model document may have different serialization from its source while preserving JSON values. Do not silently recreate it through a floating-point serializer.

| Model | Exact reusable model path | SHA-256 |
| --- | --- | --- |
| X5 | `target/research-validation/models/X5.json` | `a380a8fdea19603cdc98bc743bab97dd45dd83011d09218286721363c65a3cd9` |
| Y | `target/research-validation/models/Y.json` | `cd9c409ba816f382070a64fff8ce153e204f57cbc57544bb734f8ccd8a7331d8` |
| Previous high-load witness, no attack | `target/research-validation/models/demand-none.json` | `0dae2b6dd98fc66ae9e4b04bfeb0fb499bb674190947dfef944f1a4c98b16b64` |
| Previous high-load witness, detected disclosure | `target/research-validation/models/demand-late-detected.json` | `9d4411d840ba42e593182b0b4265e9b1dcba44ff12d3c6199226e30d9383ee21` |
| ISS46 | `target/research-validation/models/ISS46.json` | `eacfa95e4b90c9416c38f8b16006481743e56ff8bcdfcab404b6bd50bab44a08` |
| ISS44 | `target/research-validation/models/ISS44.json` | `8339b877af5f68ee942975eefae0c0570fdf772453966edbe19b683cdd1a0009` |
| ISS45 | `target/research-validation/models/ISS45.json` | `07ca15976df30cf50a7d3080d2a855b9eca2e56602da3b2bfb59a500f42768a5` |
| NOAA-19, 38 contacts | `target/research-validation/models/33591-base.json` | `caa436df61c5e8f12f5ad1a6005396ec27473b643cffa60143d0298ce27b301e` |
| HST, 48 contacts | `target/research-validation/models/20580-base.json` | `a3d21ff70e5123e9bcf72c9ac28c4a8e2ccf01a8249b55136bfdb63245f91ff3` |

X5/Y originate in `target/security-evaluation-se22/models/{X,Y}-none.model.json`. The two demand models originate in `target/security-evaluation-remaining-demand/models/{none,late-detected}.json`. Existing model `input`/`provenance` strings describe historical construction; preserve them and add an explicit new derivation manifest when changing offers, constraints, or costs.

## Source schedules and provenance

| Schedule | Exact path | Selected/candidate windows | Fixed UTC day | SHA-256 |
| --- | --- | --- | --- | --- |
| ISS46 | `data/runs/iss-20261010/schedule/schedule.json` | 46/146 | 2026-10-10 to 2026-10-11 | `fe8a56aebdae09d31dcb3097f1c03ed960a21860edb727643db4310788c64862` |
| ISS44 | `target/security-evaluation-new-days/day1/schedule/schedule.json` | 44/144 | 2026-10-11 to 2026-10-12 | `caacbbc2211c1faed382f7a73c7fec0607f77ea01796f7c58d260846ebe81c06` |
| ISS45 | `target/security-evaluation-new-days/day2/schedule/schedule.json` | 45/141 | 2026-10-12 to 2026-10-13 | `c05d41702c4e3977e73bc99504fd788b6d85ddba8ff58324eb8c8562325c4cc1` |
| NOAA38 | `target/research-validation/geometry-v2/33591-base/schedule/schedule.json` | 38/140 | 2026-10-25 to 2026-10-26 | `b0ba05ae5532b4ee71df4c5f7ea92c57f020d0de5d256ee758206b512c356c8d` |
| HST48 | `target/research-validation/geometry-v2/20580-base/schedule/schedule.json` | 48/74 | 2026-10-25 to 2026-10-26 | `a4a9b767ec79b7afa05c96539e40f7213e8ffc24a578644fc205548657a92d1c` |

Candidate counts/day intervals come from saved provenance, not newly propagated geometry. The new mission selection was registered before acquisition, independently of planner outcomes: `docs/research-audit/workload-sources.md`, `target/research-validation/orbit-sources/protocol.json` and `target/research-validation/geometry-v2/{protocol,summary}.json`.

The following source hashes were independently recomputed during this inventory:

- ISS GP: `data/runs/iss-20261010/orekit/raw/orbit/gp.json`, epoch `2026-10-10T18:56:31.282080Z`, SHA-256 `9d78055cff2573f4d18b1f367050542595cb8f714a4de57e118bc42bd9f56a73`.
- NOAA GP: `target/research-validation/orbit-sources/33591/gp.json`, epoch `2026-10-24T05:39:23.871744Z`, inclination 98.9398 degrees, SHA-256 `5ddc16975bcb9f79a572d0760c9258dd95ae07908541f62bf1cd9de6c09cb1dc`.
- HST GP: `target/research-validation/orbit-sources/20580/gp.json`, epoch `2026-10-24T07:20:21.531264Z`, inclination 28.4737 degrees, SHA-256 `0266ccdb04d26e0d02eac17ce471f3581cca2ca92e095b6e313a0ac41505ec8b`.
- Common 32-station coordinates: `data/runs/iss-20261010/orekit/raw/stations.json`, SHA-256 `921201a236337afa86d98da592e0a42e20eb0c043978e153531139a17aff44e9`.

The GP files have adjacent `.source.json` metadata with source URL, retrieval information, and hashes. The common geometric settings are elevation 10 degrees, switch guard 0 s, check interval 30 s, root tolerance 0.001 s and maximum epoch distance 72 hours. Keep the saved propagation days; advancing the calendar without replacing the GP is invalid. ISS and NOAA/HST days differ, so this is not a contemporaneous three-satellite operational comparison. Earth-orientation/leap-second inputs are in `data/sources/earth-20261011/` with adjacent source manifests. NOAA/HST also have saved elevation20, guard30 and cohort16 schedules in the same geometry directory; they are optional, not needed for the compact main matrix.

These are public-orbit-derived geometric contact opportunities. Station authorization, satellite compatibility, actual link availability, demand, cryptographic costs and lifecycle limits are synthetic assumptions. They are not authenticated mission communications or measured onboard performance. Additional ISS days are not independent satellites.

## Exact witness definitions and historical evidence

`src/main/java/org/contactaware/security/simulation/HorizonWitness.java` fixes epoch `2026-01-01T00:00:00Z`: C1=A `[0,20]`, C2=B `[30,50]`, C3=`[80,85]`; C3 returns to A for X5 and B for Y. All X5/Y starts are t=-2. CP=150 s, material age=400 s, reuse gap=200 s. Prior A pair: created -90, activated -80, last contact ends -60; B: -40/-30/-20. Payload=400 B/s, 100 B/frame, minimum=400 B/direction, offered=`Long.MAX_VALUE`/direction. Thus X5 is the original five-second return witness, not a fifth random draw.

`PolicyExperimentFixtures.witness(sequence, false)` uses that common prior state with no prescribed warmup. `true` prescribes PREPARE from t=-2 to 0 and charges it once. Do not conflate the two versions:

| Historical model/mode | Next five metrics (served, useful s, CPU s, management B, new directional keys) | Multi five metrics |
| --- | --- | --- |
| Prepared X, original test expectation | `(2,38,0.32,160,2)` | `(3,37,0.83,1240,2)` |
| Prepared Y, original test expectation | `(3,42,0.38,240,2)` | same |
| Autonomous X5, R1 exact CONTINUE_WAIT saved result | `(2,38,0.12,160,0)` | `(3,37,0.83,1240,2)` |
| Autonomous X5, R1 practical-v2, either rule | `(2,38,0.12,160,0)` | same |
| High-demand no attack, R1 practical-v2 CANONICAL | `(3,410,2.13,3240,6)` | `(3,410,2.78,4240,8)` |
| High-demand no attack, R1 practical-v2 CONTINUE_WAIT | `(3,410,2.13,3240,6)` | `(2,40,1.42,2160,4)` |

The original prepared expectations are executable assertions in `src/test/java/org/contactaware/security/simulation/ControlledWitnessIntegrationTest.java`; `docs/formal-cost-model-and-horizon/main.tex` explains their derivation and a tied installation/deferred-switch plan. They were read, not rerun in this inventory. Saved R1 evidence is `docs/research-audit/results/runs.csv`, `RESEARCH_VALIDATION_RESULTS.md`, and the corresponding `target/research-validation/{exact,practical-v2}/runs/` directories. X5 CANONICAL exact Multi is incomplete; the finished CONTINUE_WAIT result cannot fill that cell. Y is an essential null control: the return does not require A renewal.

The previous high-demand witness is the first three chronological contacts of ISS day2, CP5400, empty keys, with only C1 offered demand changed from 1000 to 39000 B in each direction. C1 is `orekit-25544-SATNOGS-3634-1`, `2026-10-12T00:08:37.296295166Z` to `00:14:59.990386963Z`. C2 is SATNOGS-2987, C3 returns to SATNOGS-3634. C2/C3 remain 1000 B/direction. Minimum remains 1000 B/direction, rate 100 B/s and frame 1000 B. The detected version discloses at `00:14:21.720977783Z` (90% through C1), notice at `00:14:23.720977783Z`. Source: `docs/security-evaluation-remaining-demand/{protocol.md,configuration-audit.json,comparison.csv}`. It is a selected historical mechanism witness, not evidence that all high-load mission schedules behave this way.

All four original SE2.5 Multi runs were `SEARCH_INCOMPLETE`; final objectives are unavailable. Their completed Next counterparts are retained in that comparison CSV. R1's completed practical negatives are separate later results, not retroactive replacements. The PC2 post-PREPARE fixture at `target/practical-controller-pc2/fixtures/post-prepare-known-retained-winner/` preserves the concrete same-root cheaper continuation; `docs/practical-controller/pc2/fixtures-note.md` explains why earlier-root scores and final snapshots cannot substitute for a reconstructed current root.

## Reusable constructors and parameter boundaries

- `PolicyExperimentFixtures.selectedSchedule(path, contactLimit, cryptoperiod, costScale, workload)` reads and verifies the schedule. `contactLimit=0` means all chronological contacts, preserving IDs and timestamps. It starts two seconds before first contact at scale=1, with configured contexts and empty keys. No state reset occurs between contacts. Schedule defaults are CP5400, material age=172800 s, reuse gap=86400 s.
- `ResearchValidationMain --mode configure --schedule ... --count 0 --cp 5400 --workload LIGHT --out ...` invokes that constructor with costScale=1. Already saved base model JSON can be copied directly. New derivations should recursively check that only declared leaves change, keeping nanosecond timestamps and exact integer tokens.
- `PolicyExperimentFixtures.traffic`: LIGHT offers/requires 1000 B/direction; MODERATE offers/requires 3000; SATURATED offers `max(1,floor(windowSeconds/10))*1000` per direction and requires 1000. All use 100 B/s, 1000 B frames. MODERATE changes the service threshold as well as offered demand; it must not be described as pure demand sensitivity.
- `payloadFeasibility` supplies a payload-only service ceiling. Required complete frames are rounded up per direction, then their durations added. This is an optimistic geometry bound, not achieved service or security feasibility.
- `SecurityCostProfile.syntheticWitness()`: PREPARE 2 s elapsed, 0.20 s CPU, 0 B; INSTALL 4 s, 0.30 s CPU, 800 B; SWITCH 1 s, 0.15 s CPU, 200 B; OPEN/REUSE 1 s, 0.06 s CPU, 80 B. STOP/EXPIRE are instantaneous. INSTALL/SWITCH/OPEN have serial ground CPU, uplink, spacecraft CPU, downlink and waiting phases; management radio assumption is 400 B/s. ROTATE/ESTABLISH compose already-required phases without double charging. Frozen evaluation models additionally contain NOTIFY_REVOCATION: 0.40 s elapsed, 0.04 s CPU, 96 B; preserve that augmented cost profile when reusing those models.
- `scaledCosts(k)` multiplies all security phase durations AND bytes by positive integer k and moves start earlier to fit PREPARE. This is not INSTALL-only sensitivity. Existing R1 `X-cost-INSTALL-0.5.json` and `X-cost-INSTALL-2.json` instead scale INSTALL phase durations only, keep bytes fixed, and explicitly change modeled radio rate. Use exactly one interpretation and label it.
- `ResearchValidationMain` runs fixed, percontact, practical or exact; it accepts `--portfolio SEED_AND_RETENTION` and `SEED_WITHOUT_SEARCH`. The latter is the no-DFS seed diagnostic. `SEED_ONLY` still runs DFS and is not that control. Use seed diagnostics at H1 and H2 to avoid introducing an unreported visibility difference.
- Existing practical limits in `scripts/run_pc2.py`: 5000 nodes/1000 ms per search, 2000 ms per decision, 350 ms seed and retained stages, 10000 rollout transitions, no cumulative controller limit, 600 s external watchdog, one `-Xmx1024m` JVM. These are a reusable starting protocol, not a guarantee that new workloads finish. Existing R1 20 s cumulative controller limit censored every NOAA/HST full execution; those cells are not service losses.

## Concrete compact matrix recommendation

Register all cases, model hashes, ordering, objective, rule, budgets and one frozen binary before the first run. Use CANONICAL as the primary rule by convention, not because a historical cell wins. Preserve the common five-objective comparator and all action/constraint/cost semantics. The matrix below is a finite proposal for the parent to accept or reduce before execution, never after seeing results.

1. **Primary mission block: 54 attempts.** ISS46, NOAA38 and HST48 x three offered loads x six configurations: FixedPeriod T=3600 s, PerContact JUST_IN_TIME, practical Next H1, practical Multi H2, SEED_WITHOUT_SEARCH H1 and H2. Use common CP5400, original complete schedule, CANONICAL and offline physical time. Low=1000, medium=3000 B/direction; high=`max(3,floor(windowDurationNs/10000000000))*1000` B/direction. All three retain minimum 1000, frame 1000 and rate 100 B/s. The max(3,...) maintains low<=medium<=high even for short windows. Only offer leaves change. Report payload-only ceiling and actual delivered frames separately from served count.
2. **Historical mechanisms: 18 attempts.** Unchanged X5, Y and demand-none x the same six configurations; FixedPeriod T=60 s for X/Y (existing conventional default), T=3600 s for demand-none. Preserve historical definitions exactly. This includes null and negative witnesses rather than selecting only wins.
3. **Limited factor checks: 18 attempts.** For both X5 and Y, vary INSTALL duration x0.5/x2 and CP90/240 one factor at a time; Next/Multi pairs give 16 attempts. Add H3 to both base witnesses (2). Keep the same rule and budget as primary Next/Multi. The duration-only installation sensitivity is the existing R1 definition (bytes fixed), not uniform resource scaling. This compact block makes no claim of an orbit-wide sensitivity sweep.
4. **Security/disruption subset: 16 attempts.** Existing models `X-undetected`, `X-detected2`, `X-gap`, `X-channel-temporary`, `X-channel-early-los`, `X-channel-late-aos`, `X-channel-missed`, and `demand-late-detected`, each Next/Multi. Their no-attack baselines are already in the mechanism block. Reuse `target/research-validation/models/<name>.json` and register each hash. These retain undetected disclosure, two-second notice, gap notice, interrupted radio, lost contact edges, a missed contact, and remaining post-notice demand. Existing X event coordinates are at 8/no notice; 8/10; 60/62; channel `[5,7]`, `[15,20]`, `[0,4]`, `[0,20]` respectively.
5. **Tie-rule sensitivity: 12 attempts.** CONTINUE_WAIT Next/Multi on X5, Y, demand-none and LOW ISS46/NOAA38/HST48; primary CANONICAL partners already exist. Do not extrapolate these rule checks to unrun medium/high cells.

Recommended total is **118 attempts** (54+18+18+16+12); 54 are complete mission/load comparisons, the remainder are targeted controls and small witnesses except six LOW mission rule checks. This is not a factorial grid over every factor. If tighter runtime is required, fix one primary rule and drop the complete 12-case alternate-rule block before registration, yielding 106 attempts; state that rule sensitivity was not rerun. Do not drop only slow or losing cells.

This recommendation is independent of the desired direction of the results: all three preregistered missions are retained; workloads follow a uniform arithmetic rule; X and Y move together for sensitivity; the prior losing demand witness remains. Existing favorable X-light/X-medium results are historical context, not a reason to replace X5. No new satellite, contact fragment or higher budget should be substituted after a failure.

Before packaging, copy the selected model bytes, schedule/GP/station/Earth inputs, adjacent provenance metadata, derivation audit, manifests and source/JAR identity into the new package because many `target/` and `data/` artifacts are local ignored files. Save every failed/incomplete attempt with stop reason and checkpoint. `FINISHED` means reaching scenario end, not serving every contact, and does not establish global optimality. Report search completion, controller completion, service, security outcomes and deadline behavior separately. This inventory performed current hash checks but no fresh Java verifier, test execution or propagation.
