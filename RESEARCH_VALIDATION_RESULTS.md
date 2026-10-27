# R1 research validation results

Generated from the saved audited R1 outputs. Historical results are preserved and are not relabeled as new experiments. This stage implements and evaluates changes; it does not establish mission readiness or universal Multi superiority.

## 1. Starting state and issue dispositions

Base commit `3621333d8093b4cb0c232c7db9164c66e273053c`; the initial dirty tree is frozen in [environment.json](target/research-validation/environment.json). The latest supplied PDF differs from the available TeX source; the new manuscript is an explicitly identified R1 revision. The historical synthesis recount remains **63 FINISHED, 20 SEARCH_INCOMPLETE, 1 controller-limited; 21/42 complete pairs**. Three extra historical technical attempts are separate.

| Issue | Status | Evidence / remaining condition |
| --- | --- | --- |
| Search / scale | PARTIALLY_CLOSED | Journal folding verified against full DFS; fixed-node plans unchanged; no measured speedup. Practical mode and cancellation validated; exact and large continuous execution limits remain. |
| Horizon benefit | PARTIALLY_CLOSED | Matched both-rule X/Y/ISS evidence separates exact and practical. Practical budget can lose the historical X5 benefit; no universal advantage. |
| Disclosure / recovery | PARTIALLY_CLOSED | Registered undetected/delayed/contact/gap cases and four independent milestones; protected management and independent replacement assumptions remain. |
| Channel / forecast errors | PARTIALLY_CLOSED | Actual interruptions, delayed AOS/early LOS/missed contact and partial accounting tested. Explicit reconciliation after uncertain SWITCH/OPEN/REUSE is still absent. |
| Different LEO workloads | PARTIALLY_CLOSED | New public NOAA-19/HST GP snapshots, common geometry and full sequences attempted. Incomplete full runs are not replaced by fragment results. |
| Costs / load | PARTIALLY_CLOSED | Compact registered sensitivity and host primitive measurements; synthetic online costs are not calibrated spacecraft handshakes. |
| Compute deadlines | PARTIALLY_CLOSED | Critical-path delay/fallback mode and deadlines measured separately; distributed deployment/telemetry/command cost not validated. |
| Reproducibility | VERIFIED_CLOSED for saved R1 evidence | Source/build/model/JAR/journal/table hashes, protocols, data snapshots and restore instructions provided. This is a dirty-source snapshot, not a claim of a clean committed release. |
| Related work / article | PARTIALLY_CLOSED | Detke full paper/slides and standards checked; R1 manuscript/figures compiled separately. FHAP primary full text and detailed LK-AKA access remain open. |
| Historical 3361 s stall | NOT_REPRODUCED | No cause assigned to GC, solver or Windows software. Bounded cancellation and external watchdog are tested separately. |

Detailed original claims, implementation paths and closure criteria: [issue matrix](docs/research-audit/issue-matrix.md). NOT_REPRODUCED does not mean fixed. Broad issues are not closed by adding a discussion caveat.

## 2. Actual implementation changes

- `PlanningJournalSummary`, `ExactHorizonPlanner`, `PlanningOptimisticBound`, `PlanningStateKey`: share a derived immutable per-snapshot journal fold. Bounds, candidate space, five priorities and complete plan comparator remain unchanged.
- `BudgetedHorizonPolicy`: separate local-budget incumbent/fallback mode; INCOMPLETE stays in the ledger. Practical-v2 feasible suffix reuse requires full observation equality after one completed frame and current candidate membership. It grants no optimality certificate.
- `PlanningLatencyController`: advance actual model events while a decision is unavailable, revalidate intent, wait after deadline miss. Existing offline exact controller remains available.
- `ChannelInterruption`, `SimulationScenario`, `SimulationEngine`, `PlanningObservation`, `ExecutionSnapshot`, `SimulationEvent`: actual availability events separate from predicted contacts, committed work interruption and checkpoint restoration.
- `LifecycleTransitions.recordAbortedTransmission`: a committed aborted frame consumes its nonce while adding no delivered frame or successful-contact history. This is required when the same key can resume after an outage; it does not establish a historical exploit for irreversibly retired keys.
- `ResearchValidationMain`, `HostCryptoMicrobenchmark`, Python registration/audit/plot/report scripts; R1 recovery replay accounts for aborted nonces and observed channel availability. Historical replay defaults remain unchanged.

[Execution, deployment and cost contract](docs/research-audit/execution-and-costs.md), [channel semantics](docs/research-audit/channel-model.md), [search correctness/profile](docs/research-audit/search-diagnosis.md).

## 3. Validation, tools and reproduction

Java 21.0.10, Maven 3.9.9 wrapper, Windows, Ryzen 7 5800HS, 16 logical CPUs and 16,541,487,104 bytes RAM. All experiment JVMs ran serially with `-Xmx1024m`. Host timings are measurements of this machine, not onboard benchmarks.

Fresh isolated candidate tests: **423 passed, 0 failures/errors** after repairing a new test fixture and restoring required local data. After practical-v2, **74 focused Java tests passed**, including 12 componentwise quality rows; these overlap the 423 and must not be added. **10 focused Java tests passed** after the export-only repair. **67 Python tests passed**, including 17 R1 journal corruption/regression checks. [Test logs and manifests](target/research-validation/) retain failed intermediate builds too.

The first baseline build had 22 errors: missing local data fixtures and blocked loopback import tests. Original-source fixture tests passed after data restoration; loopback tests passed in the subsequent permitted run. The first new quality-test attempt exhausted a 250000-node full-DFS root before the existing warmup. Its failure log is retained. The follow-up uses the two already defined warmed witness states, with unchanged budgets; it is not a successful full-root oracle. The 12 warmed comparisons match all five components, not a general approximation bound.

Atomic checkpoint tests exercise bounded transient/permanent rename failures, last-good preservation and restoration. A separate watchdog probe reaped a silent process in about 2.031 s under a 2 s limit. Every available saved checkpoint is compared with the exported state and journal; Java tests separately execute restoration. Rename timing/retries, search phase CPU/wall/GC and sampled heap are recorded, with unavailable values left absent/null.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -B -ntp test
python -X utf8 -m unittest discover -s scripts -p "test_*.py"
python -X utf8 scripts/analyze_research_validation.py
python -X utf8 scripts/plot_research_validation.py
```

For faithful saved-version execution use the frozen JAR and model from the series protocol, not an arbitrary current checkout. The scripts refuse existing attempts. See [reproduction instructions](docs/research-audit/reproduction.md) for source snapshots, data restore and a new output path. Maven tests need the declared local orbit/station/earth data; a clean checkout without restoration is insufficient.

## 4. Registered matrix and completion

| Series | Planned | Attempted | FINISHED | Search incomplete | Controller limit | Other failed | Not run |
| --- | --- | --- | --- | --- | --- | --- | --- |
| baseline | 12 | 12 | 11 | 1 | 0 | 0 | 0 |
| exact | 40 | 40 | 30 | 8 | 2 | 0 | 0 |
| practical-v2 | 44 | 44 | 23 | 0 | 21 | 0 | 0 |
| security-v2 | 24 | 24 | 24 | 0 | 0 | 0 | 0 |
| disruptions-v2 | 16 | 16 | 16 | 0 | 0 | 0 | 0 |
| sensitivity-v2 | 38 | 38 | 26 | 0 | 12 | 0 | 0 |
| controls-v2 | 10 | 10 | 10 | 0 | 0 | 0 | 0 |
| deadlines-v2 | 8 | 1 | 0 | 0 | 0 | 1 | 7 |
| deadlines-v3 | 8 | 8 | 6 | 2 | 0 | 0 | 0 |

Excluding the 12 pre-change baseline attempts: **181 attempted, 135 FINISHED**. Matched R1 horizon comparisons: **55/84 complete pairs**; unmatched H3 and Fixed/PerContact controls are not counted as pairs.

Six original practical-series registrations were superseded **before any attempts** by practical-v2 registrations with identical cases/budgets and a new binary. They remain saved; their planned cells are not counted twice. No limit was increased to obtain COMPLETE. One deterministic configuration is an experimental unit; repeated builds/probes are not independent service observations.

| Series | Multi wins (five-criterion order) | Equal | Multi loses | Incomplete pair |
| --- | --- | --- | --- | --- |
| deadlines-v3 | 0 | 2 | 0 | 2 |
| disruptions-v2 | 0 | 8 | 0 | 0 |
| exact | 1 | 9 | 0 | 10 |
| practical-v2 | 0 | 8 | 3 | 11 |
| security-v2 | 0 | 12 | 0 | 0 |
| sensitivity-v2 | 2 | 9 | 1 | 6 |

Exact: 2,000,000 nodes / 30 s per decision, 60 s controller, 90 s process. Practical: 5000 nodes / 1 s per decision, 20 s controller, 35 s process. Practical FINISHED describes a complete physical execution, not all searches being COMPLETE. Missing final quality is not zero. All cases, costs, reasons and per-search diagnostics: [runs.csv](docs/research-audit/results/runs.csv), [pairs.csv](docs/research-audit/results/pairs.csv), [matrix.json](docs/research-audit/results/matrix.json).

## 5. Horizon, rule and cost results

| Model | Rule | H | Execution | Served | Useful s | CPU s | Mgmt B | New keys |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| X3 | CANONICAL | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X3 | CANONICAL | 2 | SEARCH_INCOMPLETE | — | — | — | — | — |
| X3 | CONTINUE_WAIT | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X3 | CONTINUE_WAIT | 2 | SEARCH_INCOMPLETE | — | — | — | — | — |
| X5 | CANONICAL | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X5 | CANONICAL | 2 | SEARCH_INCOMPLETE | — | — | — | — | — |
| X5 | CONTINUE_WAIT | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X5 | CONTINUE_WAIT | 2 | FINISHED | 3 | 37 | 0.83 | 1240 | 2 |
| X9 | CANONICAL | 1 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| X9 | CANONICAL | 2 | SEARCH_INCOMPLETE | — | — | — | — | — |
| X9 | CONTINUE_WAIT | 1 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| X9 | CONTINUE_WAIT | 2 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| Y | CANONICAL | 1 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| Y | CANONICAL | 2 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| Y | CONTINUE_WAIT | 1 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| Y | CONTINUE_WAIT | 2 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |

The exact X5 CONTINUE_WAIT result retains the earlier conditional short-return mechanism when that execution finishes. CANONICAL incomplete cells cannot be inferred from it. The autonomous model charges only newly executed preparation; its Next CPU is 0.12 s, distinct from the old 0.32 s shared-preparation fixture.

| Model | Rule | H | Execution | Served | Useful s | CPU s | Mgmt B | New keys |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| X3 | CANONICAL | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X3 | CANONICAL | 2 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X3 | CONTINUE_WAIT | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X3 | CONTINUE_WAIT | 2 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X5 | CANONICAL | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X5 | CANONICAL | 2 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X5 | CONTINUE_WAIT | 1 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X5 | CONTINUE_WAIT | 2 | FINISHED | 2 | 38 | 0.12 | 160 | 0 |
| X9 | CANONICAL | 1 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| X9 | CANONICAL | 2 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| X9 | CONTINUE_WAIT | 1 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| X9 | CONTINUE_WAIT | 2 | FINISHED | 3 | 41 | 0.83 | 1240 | 2 |
| Y | CANONICAL | 1 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| Y | CANONICAL | 2 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| Y | CONTINUE_WAIT | 1 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| Y | CONTINUE_WAIT | 2 | FINISHED | 3 | 42 | 0.18 | 240 | 0 |
| demand-none | CANONICAL | 1 | FINISHED | 3 | 410 | 2.13 | 3240 | 6 |
| demand-none | CANONICAL | 2 | FINISHED | 3 | 410 | 2.78 | 4240 | 8 |
| demand-none | CONTINUE_WAIT | 1 | FINISHED | 3 | 410 | 2.13 | 3240 | 6 |
| demand-none | CONTINUE_WAIT | 2 | FINISHED | 2 | 40 | 1.42 | 2160 | 4 |
| demand-late-detected | CANONICAL | 1 | FINISHED | 3 | 400 | 2.88 | 4416 | 8 |
| demand-late-detected | CANONICAL | 2 | CONTROLLER_TIME_LIMIT | — | — | — | — | — |
| demand-late-detected | CONTINUE_WAIT | 1 | FINISHED | 3 | 390 | 2.88 | 4416 | 8 |
| demand-late-detected | CONTINUE_WAIT | 2 | FINISHED | 2 | 40 | 1.42 | 2160 | 4 |

Under the registered small practical budget, X5 Multi loses the earlier third-contact advantage and equals Next. In the high-demand no-attack fragment, CANONICAL can keep service but spend extra CPU/bytes/keys; CONTINUE_WAIT may lose service. These are observed budgeted-controller outcomes, not exact-optimum losses or a changed objective. A larger horizon can worsen the feasible plan found within a fixed search budget.

Two finite-offer CANONICAL cases retain a practical horizon benefit: X-light (400 B/direction/contact) and X-medium (1600 B/direction/contact) each gain one served contact with Multi. The respective useful-time gains are 2 and 4 s. Both cost an extra 0.71 s operation CPU, 1080 management bytes and two new keys. This is not evidence for CONTINUE_WAIT on these unrun factor cells or an optimum guarantee for the bounded solver.

Cost/load sensitivities vary one axis at a time: operation duration, cryptoperiod, material age, reuse gap, offer, minimum, frame size and H. Numeric ranges and snapshots are in protocols, with incomplete cells retained. Increasing frame bytes without changing frame duration changes modeled rate as well as quantization; this is not an isolated wire-framing efficiency claim. Initial key notAfter values remain fixed in cryptoperiod sensitivity.

The effective deadline is the minimum of stored notAfter, activation + cryptoperiod and creation + maximum age. Thus CP=90 s tightens the old A pair deadline to t=10 even though stored notAfter stays t=70. In its Next journal PREPARE completes at t=10, INSTALL at 14, SWITCH at 15 and OPEN at 16; both horizons then serve three contacts. Renewal needed within the current contact removes the need for extra visibility in this case. CP=240 cannot extend the stored old-key deadline and both practical policies serve two. These two settings do not establish robustness to arbitrary cryptoperiods.

FixedPeriod ranges are T=30/60/90/120 s on X5 and 1800/3600/5400 s on full ISS46. Each runs the same input and constraints as its comparison family; full ISS results are meaningful only if FINISHED.

| Model | Policy | T s | Status | Served | Useful s | CPU s | Mgmt B | New keys |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| X5 | fixed | 30 | FINISHED | 2 | 28 | 2.12 | 2960 | 8 |
| X5 | fixed | 60 | FINISHED | 2 | 26 | 1.92 | 2960 | 6 |
| X5 | fixed | 90 | FINISHED | 3 | 36 | 1.09 | 1320 | 4 |
| X5 | fixed | 120 | FINISHED | 2 | 38 | 0.62 | 960 | 2 |
| ISS46 | fixed | 1800 | FINISHED | 46 | 920 | 35.86 | 49680 | 124 |
| ISS46 | fixed | 3600 | FINISHED | 46 | 920 | 35.66 | 49680 | 122 |
| ISS46 | fixed | 5400 | FINISHED | 46 | 920 | 35.26 | 49680 | 118 |
| X5 | percontact | — | FINISHED | 2 | 28 | 1.92 | 2960 | 6 |
| Y | percontact | — | FINISHED | 2 | 28 | 1.92 | 2960 | 6 |
| ISS46 | percontact | — | FINISHED | 46 | 920 | 32.66 | 49680 | 92 |

Host primitive measurements and the fixed-node comparison are in [search-measurements.md](docs/research-audit/search-measurements.md). After the aggregate change, this single diagnostic was about 1.89%/2.47% slower for CANONICAL/CW, with the same 20000-node incumbent plan and score. It provides no measured speedup; the historical 3361 s stall remains unexplained.

FixedPeriod T=90 serves all three X5 contacts (36 s useful, 1.09 s CPU, 1320 management bytes, four new keys); the other tested X periods do not. All four completed ISS46 operational controls serve 46 contacts and provide 920 s useful time. The missing search-policy finals cannot be ranked against them as service losses.

| ISS46 rule | H | Contacts started at stop | Search s | Checkpoint s | Searches | Certified suffixes | Feasible suffixes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CANONICAL | 1 | 10 | 18.536123 | 1.096937 | 109 | 16 | 0 |
| CANONICAL | 2 | 12 | 19.467344 | 0.408366 | 37 | 0 | 0 |
| CONTINUE_WAIT | 1 | 26 | 19.194093 | 0.615906 | 79 | 0 | 0 |
| CONTINUE_WAIT | 2 | 12 | 19.541094 | 0.353212 | 36 | 0 | 0 |

Across the saved R1 runs, checkpoint diagnostics record 49 bounded retries, with no checkpoint process failure. This does not identify the Windows process responsible for a transient denial. The full ISS samples spend about 18.5–19.5 s of their 20 s budget inside searches, versus 0.35–1.10 s in checkpoint writes: repeated decision search, rather than checkpoint time alone, is the measured dominant cost in these cases.

## 6. Disclosure, recovery, actual channel and deadlines

| Case | Rule | H | Status | Confidential served | Confidential B | Historical exposure B | Post-disclosure B | Recovery |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| X-undetected | CANONICAL | 1 | FINISHED | 1 | 7600 | 2800 | 4800 | UNDETECTED |
| X-undetected | CANONICAL | 2 | FINISHED | 1 | 7600 | 2800 | 4800 | UNDETECTED |
| X-undetected | CONTINUE_WAIT | 1 | FINISHED | 1 | 7600 | 2800 | 4800 | UNDETECTED |
| X-undetected | CONTINUE_WAIT | 2 | FINISHED | 1 | 7600 | 2800 | 4800 | UNDETECTED |
| X-detected0 | CANONICAL | 1 | FINISHED | 3 | 10600 | 2800 | 0 | RECOVERED |
| X-detected0 | CANONICAL | 2 | FINISHED | 3 | 10600 | 2800 | 0 | RECOVERED |
| X-detected0 | CONTINUE_WAIT | 1 | FINISHED | 3 | 10600 | 2800 | 0 | RECOVERED |
| X-detected0 | CONTINUE_WAIT | 2 | FINISHED | 3 | 10600 | 2800 | 0 | RECOVERED |
| X-detected2 | CANONICAL | 1 | FINISHED | 2 | 9800 | 2800 | 800 | RECOVERED |
| X-detected2 | CANONICAL | 2 | FINISHED | 2 | 9800 | 2800 | 800 | RECOVERED |
| X-detected2 | CONTINUE_WAIT | 1 | FINISHED | 2 | 9800 | 2800 | 800 | RECOVERED |
| X-detected2 | CONTINUE_WAIT | 2 | FINISHED | 2 | 9800 | 2800 | 800 | RECOVERED |
| X-detected8 | CANONICAL | 1 | FINISHED | 1 | 7600 | 2800 | 3200 | CENSORED_NO_RECOVERY |
| X-detected8 | CANONICAL | 2 | FINISHED | 1 | 7600 | 2800 | 3200 | CENSORED_NO_RECOVERY |
| X-detected8 | CONTINUE_WAIT | 1 | FINISHED | 1 | 7600 | 2800 | 3200 | CENSORED_NO_RECOVERY |
| X-detected8 | CONTINUE_WAIT | 2 | FINISHED | 1 | 7600 | 2800 | 3200 | CENSORED_NO_RECOVERY |
| X-gap | CANONICAL | 1 | FINISHED | 1 | 7600 | 7600 | 0 | CENSORED_NO_RECOVERY |
| X-gap | CANONICAL | 2 | FINISHED | 1 | 7600 | 7600 | 0 | CENSORED_NO_RECOVERY |
| X-gap | CONTINUE_WAIT | 1 | FINISHED | 1 | 7600 | 7600 | 0 | CENSORED_NO_RECOVERY |
| X-gap | CONTINUE_WAIT | 2 | FINISHED | 1 | 7600 | 7600 | 0 | CENSORED_NO_RECOVERY |
| X-late | CANONICAL | 1 | FINISHED | 1 | 7600 | 6800 | 800 | CENSORED_NO_RECOVERY |
| X-late | CANONICAL | 2 | FINISHED | 1 | 7600 | 6800 | 800 | CENSORED_NO_RECOVERY |
| X-late | CONTINUE_WAIT | 1 | FINISHED | 1 | 7600 | 6800 | 800 | CENSORED_NO_RECOVERY |
| X-late | CONTINUE_WAIT | 2 | FINISHED | 1 | 7600 | 6800 | 800 | CENSORED_NO_RECOVERY |

The external attack remains fixed per pair; actual active keys determine the disclosed IDs. NO_TARGET is retained. Traffic-key disclosure assumes recorded ciphertext, protected management credentials and independent replacement material with no persistent attacker access. Root compromise, active forgery and real RF jamming are not evaluated.

| Model | Rule | H | Status | SC notice s | Activation s | SA open s | First frame s |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ISS5400-p30-detected | CANONICAL | 1 | FINISHED | 0.4 | 7.4 | 5661.427338 | 5671.427338 |
| ISS5400-p30-detected | CANONICAL | 2 | FINISHED | 0.4 | 1.4 | 5657.427338 | 5667.427338 |
| ISS5400-p30-detected | CONTINUE_WAIT | 1 | FINISHED | 0.4 | 7.4 | 5661.427338 | 5671.427338 |
| ISS5400-p30-detected | CONTINUE_WAIT | 2 | FINISHED | 0.4 | 7.4 | 5661.427338 | 5671.427338 |
| X-detected2 | CANONICAL | 1 | FINISHED | 0.4 | 7.4 | 8.4 | 8.65 |
| X-detected2 | CANONICAL | 2 | FINISHED | 0.4 | 7.4 | 8.4 | 8.65 |
| X-detected2 | CONTINUE_WAIT | 1 | FINISHED | 0.4 | 7.4 | 8.4 | 8.65 |
| X-detected2 | CONTINUE_WAIT | 2 | FINISHED | 0.4 | 7.4 | 8.4 | 8.65 |

These times originate at GS notice. Pair/SA readiness intervals retain later expiry and closure; first activation is not continuous readiness. Material, notification, contact and offered-data waiting flags can overlap and are not additive. The observed ISS gap between activation and first delivered frame is kept explicit.

| Interruption | Rule | H | Status | Served | Useful s | Aborted frames | Failures |
| --- | --- | --- | --- | --- | --- | --- | --- |
| X-channel-temporary | CANONICAL | 1 | FINISHED | 3 | 32 | 0 | {} |
| X-channel-temporary | CANONICAL | 2 | FINISHED | 3 | 32 | 0 | {} |
| X-channel-temporary | CONTINUE_WAIT | 1 | FINISHED | 3 | 32 | 0 | {} |
| X-channel-temporary | CONTINUE_WAIT | 2 | FINISHED | 3 | 32 | 0 | {} |
| X-channel-early-los | CANONICAL | 1 | FINISHED | 2 | 33 | 0 | {} |
| X-channel-early-los | CANONICAL | 2 | FINISHED | 2 | 33 | 0 | {} |
| X-channel-early-los | CONTINUE_WAIT | 1 | FINISHED | 2 | 33 | 0 | {} |
| X-channel-early-los | CONTINUE_WAIT | 2 | FINISHED | 2 | 33 | 0 | {} |
| X-channel-late-aos | CANONICAL | 1 | FINISHED | 2 | 34 | 0 | {} |
| X-channel-late-aos | CANONICAL | 2 | FINISHED | 2 | 34 | 0 | {} |
| X-channel-late-aos | CONTINUE_WAIT | 1 | FINISHED | 2 | 34 | 0 | {} |
| X-channel-late-aos | CONTINUE_WAIT | 2 | FINISHED | 2 | 34 | 0 | {} |
| X-channel-missed | CANONICAL | 1 | FINISHED | 1 | 19 | 0 | {} |
| X-channel-missed | CANONICAL | 2 | FINISHED | 1 | 19 | 0 | {} |
| X-channel-missed | CONTINUE_WAIT | 1 | FINISHED | 1 | 19 | 0 | {} |
| X-channel-missed | CONTINUE_WAIT | 2 | FINISHED | 1 | 19 | 0 | {} |

Controlled outages are actual-channel availability interventions, including a possible jamming-induced interruption interpretation, not an RF jammer model. Online work stops, partial cost and uncertainty persist, nonces are not reused, and future actual recovery is hidden from planning. Tests separately interrupt INSTALL, SWITCH, OPEN/REUSE and payload, including missing ACKs and checkpoint boundaries. The small outage matrix is not a stochastic reliability study.

| Case | H | Rule | Status | Deadline misses | Served | Useful s |
| --- | --- | --- | --- | --- | --- | --- |
| 001-X5-practical-H1-CANONICAL | 1 | CANONICAL | PROCESS_ERROR | — | — | — |
| 001-X5-practical-H1-CANONICAL | 1 | CANONICAL | FINISHED | 0 | 2 | 31.5 |
| 002-X5-practical-H1-CANONICAL | 1 | CANONICAL | FINISHED | 9 | 0 | 0 |
| 003-X5-practical-H2-CANONICAL | 2 | CANONICAL | SEARCH_INCOMPLETE | 0 | — | — |
| 004-X5-practical-H2-CANONICAL | 2 | CANONICAL | FINISHED | 9 | 0 | 0 |
| 005-X5-practical-H1-CONTINUE_WAIT | 1 | CONTINUE_WAIT | FINISHED | 0 | 2 | 31.5 |
| 006-X5-practical-H1-CONTINUE_WAIT | 1 | CONTINUE_WAIT | FINISHED | 9 | 0 | 0 |
| 007-X5-practical-H2-CONTINUE_WAIT | 2 | CONTINUE_WAIT | SEARCH_INCOMPLETE | 0 | — | — |
| 008-X5-practical-H2-CONTINUE_WAIT | 2 | CONTINUE_WAIT | FINISHED | 9 | 0 | 0 |

The 50/500 ms delays are synthetic, host-scale zero, with a 250 ms budget. A missed decision waits safely. Search termination, scenario termination, service and deadline compliance are four separate outcomes. The logical ground coordinator is not a completed ground/onboard command implementation. The initial deadlines-v2 attempt failed while serializing an internal CertifiedSearch capability; seven cells were not run under its stop rule. The export-only repair has a separate JAR, 10 passing focused tests and deadlines-v3 registration with unchanged models/limits. This failed technical attempt remains counted in executions but is not duplicated as a selected horizon pair.

At 50 ms, both completed Next runs retain two served contacts but useful time falls from 38 to 31.5 s; both Multi runs stop on the 20 s controller budget. Their raw status is SEARCH_INCOMPLETE and the search ledger explicitly identifies CONTROLLER_TIME_LIMIT. At 500 ms all four runs finish safely with nine deadline misses and no service. The latency adapter does not populate the legacy computation-summary object: its zero defaults are marked unavailable in postprocessing; authoritative elapsed/CPU comes from measurement and per-search ledgers. These are observable adapter/reporting limits, not zero computation.

## 7. Continuous workloads and evidence limits

| Workload | Contacts | Rule | H | Status | Served | Useful s | Searches | Nodes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ISS46 | 46 | CANONICAL | 1 | CONTROLLER_TIME_LIMIT | — | — | 109 | 148989 |
| ISS46 | 46 | CANONICAL | 2 | CONTROLLER_TIME_LIMIT | — | — | 37 | 177542 |
| ISS46 | 46 | CONTINUE_WAIT | 1 | CONTROLLER_TIME_LIMIT | — | — | 79 | 200795 |
| ISS46 | 46 | CONTINUE_WAIT | 2 | CONTROLLER_TIME_LIMIT | — | — | 36 | 175858 |
| ISS44 | 44 | CANONICAL | 1 | CONTROLLER_TIME_LIMIT | — | — | 71 | 178566 |
| ISS44 | 44 | CANONICAL | 2 | CONTROLLER_TIME_LIMIT | — | — | 36 | 167843 |
| ISS44 | 44 | CONTINUE_WAIT | 1 | CONTROLLER_TIME_LIMIT | — | — | 70 | 198551 |
| ISS44 | 44 | CONTINUE_WAIT | 2 | CONTROLLER_TIME_LIMIT | — | — | 34 | 163664 |
| ISS45 | 45 | CANONICAL | 1 | CONTROLLER_TIME_LIMIT | — | — | 123 | 182908 |
| ISS45 | 45 | CANONICAL | 2 | CONTROLLER_TIME_LIMIT | — | — | 34 | 165045 |
| ISS45 | 45 | CONTINUE_WAIT | 1 | CONTROLLER_TIME_LIMIT | — | — | 75 | 198086 |
| ISS45 | 45 | CONTINUE_WAIT | 2 | CONTROLLER_TIME_LIMIT | — | — | 30 | 137967 |
| 33591-base | 38 | CANONICAL | 1 | CONTROLLER_TIME_LIMIT | — | — | 119 | 172117 |
| 33591-base | 38 | CANONICAL | 2 | CONTROLLER_TIME_LIMIT | — | — | 41 | 201168 |
| 33591-base | 38 | CONTINUE_WAIT | 1 | CONTROLLER_TIME_LIMIT | — | — | 86 | 206643 |
| 33591-base | 38 | CONTINUE_WAIT | 2 | CONTROLLER_TIME_LIMIT | — | — | 40 | 191741 |
| 20580-base | 48 | CANONICAL | 1 | CONTROLLER_TIME_LIMIT | — | — | 229 | 133848 |
| 20580-base | 48 | CANONICAL | 2 | CONTROLLER_TIME_LIMIT | — | — | 43 | 207788 |
| 20580-base | 48 | CONTINUE_WAIT | 1 | CONTROLLER_TIME_LIMIT | — | — | 106 | 233810 |
| 20580-base | 48 | CONTINUE_WAIT | 2 | CONTROLLER_TIME_LIMIT | — | — | 38 | 184331 |

ISS days use the saved suitable-epoch ISS snapshot and retain state across all 46/44/45 contacts. NOAA-19 (98.94 deg inclination) and Hubble (28.47 deg) use freshly acquired GP epochs on October 24 for October 25–26 propagation, within the 72 h acquisition bound. Their base common schedules have 38 and 48 contacts. Other days of ISS are not independent missions. Public stations are a hypothetical authorized cohort, not permission for actual secure uplink. Elevation 20 deg, 16-station cohort and 30 s switching-guard variants are common inputs for both policies. [Source provenance](docs/research-audit/workload-sources.md).

Where a full continuous run remains incomplete, that workload is not validated by its prefix or a successful triple. No completed-fragment result is promoted to a full-mission result. Exact finite-model evidence, host measurements, synthetic interventions and public geometry are distinct evidence classes.

## 8. Article, figures and next substantive step

The separately identified [R1 manuscript](docs/paper-r1/main.tex) retains negative/incomplete results in the main text; [supplement](docs/paper-r1/supplement.tex) holds technical history. The compiled [main PDF](docs/paper-r1/main.pdf) has eight pages and the [supplement PDF](docs/paper-r1/supplement.pdf) has four. All pages and six figures were visually reviewed; no overfull boxes or unresolved references remained. Minor underfull-box/font-configuration warnings are retained in the logs. Compile diagnostics and source/PDF hashes are saved alongside results. Detke et al.'s full six-page conference paper and eight-page slides were inspected: route/key-scope optimization is close prior work, and temporal key scheduling is explicitly future work. IOTA already includes contact-dependent allowlists/revocation. R1 does not invent MPC, lifecycle states, or contact-aware security. [Primary-source comparison](docs/research-audit/related-work.md).

The [reproduction package](target/research-validation/r1-reproduction.zip) includes frozen sources, four identified JARs, required test/public-input fixtures, registered protocols, all new journals, tables, figures and test evidence. [package.json](target/research-validation/package.json) records its SHA-256 and ZIP integrity; [manifest.json](target/research-validation/manifest.json) maps source/build/result identities. All 4195 protected historical files passed the byte-preservation check.

Figures are generated from saved audited tables, with PDF/SVG/PNG exports and per-figure provenance: [horizon/cost](docs/paper-r1/figures/service_cost_horizon.pdf), [runtime/completion](docs/paper-r1/figures/runtime_completion.pdf), [sensitivity](docs/paper-r1/figures/sensitivity.pdf), [timeline](docs/paper-r1/figures/mechanism_timeline.pdf), [four recovery stages](docs/paper-r1/figures/recovery.pdf), [exposure with separate policy/hatch legends](docs/paper-r1/figures/detected_exposure.pdf).

The next substantive step is to improve or qualify the practical controller on the observed failed full sequences and the measured service losses, using the saved decision observations as small reproducible optimization targets. Any pruning/batching must preserve the full comparator and information boundary against the full-DFS oracle; any heuristic change needs a new identity/protocol and a quality comparison. Do not raise timeouts or change the objective until Multi appears favorable. Mission-calibrated link/management timing and distributed reconciliation remain separate work.
