# SE2 preregistered robustness protocol

Status: fixed before the first SE2 policy run, 2026-10-22. This document and the complete machine manifest are hashed before execution. All blocks below are fixed together, not selected after seeing an earlier block. SE1, its protocol, raw outputs and figures remain unchanged.

## Questions and claims

1. An independent spare already installed on the spacecraft can enable Multi to recover during the current contact while Next waits for a later contact. Test the inventory and actual recovery mechanism, not just the latency difference.
2. Reusing traffic keys can reduce management work while increasing disclosed traffic. Compare exposure, confidential delivery, total delivery and service together; silence is not a security victory.
3. Differences can depend on the uplink/downlink frame phase at detection. Record frame direction, remaining nominal airtime, spare inventory and delay to the next target contact. Distinguish historical exposure from post-disclosure emissions and independent recovery.

These are conditional hypotheses under a deterministic synthetic security/traffic model, not claims of cryptographic strength or probabilities in deployment. In SE1 X at t=10, both Next and Multi disclosed 800 B after disclosure; the difference in total exposure came from prior traffic on the captured generation.

## Frozen design: 26 configurations, 488 runs

Each configuration has four no-attack controls, one per policy. For each attack point, each policy runs undetected and detected variants. There are 104 controls and 384 attack runs. Controls are reused only within exactly the same physical/model configuration and policy.

| Block | Configurations | Attack points per configuration | Runs |
|---|---:|---:|---:|
| attack-time | X, Y, ISS first 3 CP5400, ISS first 3 CP7200 | 6, 6, 7, 7 | 224 |
| load | ISS first 3, CP5400/7200, MODERATE/SATURATED | original only | 48 |
| contacts | X C3 length 2/12 s at t=80; X C3 start 60/100 s, length 5 s | original only | 48 |
| fragments | ISS zero-based indices 3..5 and 6..8, each CP5400/7200, LIGHT | original only | 48 |
| combinations | X C3 start 100 s and length 12 s; ISS indices 3..5 CP5400 MODERATE | original only | 24 |
| assumptions | each of the four primary configurations under alternative WAIT or doubled notice duration, separately | original only | 96 |

X/Y use autonomous preparation, cryptoperiod 150 s, FixedPeriod T=60 s and unchanged witness traffic: 100 B frames at 400 B/s, 400 B required per direction. All X variants keep the same initial material, creation times, activation times, frozen deadlines, counters and history; only C3 geometry changes. Y is A-B-B, X is A-B-A. C1=[0,20), C2=[30,50), original C3=[80,85), epoch 2026-01-01 UTC, simulation begins at t=-2.

ISS uses the existing verified schedule `data/runs/iss-20261010/schedule/schedule.json`; IDs and nanosecond geometry are unchanged. The first three windows are A-B-A; fragments 3..5 and 6..8 are additional predetermined sequences, not selected by outcomes. Each fragment starts two seconds before its first AOS with empty keys and contexts for its selected stations. This is an explicit independent cold-start experiment, not a reset in a running mission. T=1800 s, maximum material age=2 days, maximum reuse gap=1 day.

Existing ISS workloads are used unchanged: LIGHT offers/requires one 1000 B frame per direction; MODERATE three; SATURATED offers max(1,floor(window seconds/10)) frames per direction and requires one. Rate=100 B/s, frame duration=10 s. The payload-only service ceiling is recorded. These workloads are not transplanted into X/Y's short windows, where even LIGHT would consume 20 s before security overhead. Moderate's service threshold differs from saturated's; report that difference when comparing service.

## Attack generation before policy execution

Target is A for X/Y and the first window's station for each ISS configuration. Original time is t=10 for X/Y or first AOS+15 s for ISS. Full primary grid: original, 10/30/50/70/90% of that first target window, and midpoint of the positive gap to the next physical window of any station. A gap is not the interval to the next target-station window if other contacts intervene. If no positive gap exists the midpoint is absent, recorded explicitly.

Calculate fractional times with integer nanoseconds and floor division. Deduplicate identical absolute instants; preserve original as representative and retain all source labels. X/Y original duplicates p50. Generation reads only schedule/configuration, never policy decisions, SWITCH times or outcomes. Extra blocks use only their original attack instant. Detection is exactly attack+2 s, as in SE1; undetected has no notice. The same absolute target/time/delay applies to all four policies. NO_TARGET is retained and never moved.

## Common semantics and temporal alternatives

Retain the existing executor, lifecycle constraints, deadline/counter rules, mandatory incident response, actual outcome semantics and five-priority objective: served contacts, useful secure time, CPU, management bytes, created directional keys. Next H=1 and Multi H=2 share solver, candidates, constraints, canonical tie-break and search options. FixedPeriod renews from the last successful SWITCH; overdue requests coalesce. PerContact uses new material per contact with its existing just-in-time preparation.

One-time traffic-key disclosure captures an available pair in the selected context. Independent fresh material, uncompromised authenticated management protection, and delivery of the modeled revocation message are explicit assumptions. No compromise of future keys, no persistent foothold or broken authentication is inferred. Notice at the ground station and confirmed notice on the spacecraft remain separate; a committed downlink may continue radiating after ground notice but its delivery is rejected. No emission on revoked keys after spacecraft notification is allowed. Future attacks/detection times do not enter planning observations; no hidden event wakes a policy before ground knowledge.

Default time model: V2_EVENT_BOUNDARIES. Alternative: PREPARE_DURATION_WAIT, adding a wakeup at observed now+PREPARE duration within the frozen visible horizon, in both planning and actual execution, identically for all policies (including rule-policy timers). No operation or material is created by the timer. Do not prune off-horizon PREPARE based on an unproven equivalence.

Default NOTIFY_REVOCATION=0.4 s, CPU=0.04 s, 96 B (64 UL,32 DL). Alternative multiplies every notice phase duration by 2, keeping bytes unchanged: elapsed=0.8 s, CPU=0.08 s, radio=0.48 s, remaining wait=0.24 s. UL/DL management rate becomes 200 B/s; phases still use the common CPU/radio resources. Other operation costs and payload are unchanged. These are two modeled timing assumptions, not hardware calibration. Full canonical plans need not match across time models.

## Budget and execution

Every horizon decision: exact accelerated search, 100000 nodes and 5 s wall limit. No adaptive budget increase, approximate fallback, or outcome-driven experiment substitution. All 488 registered runs are attempted in a fixed order, one JVM with 1 GiB maximum heap. Timing is machine-dependent and measured separately from simulated time and security CPU. A truncated search stops that run with explicit status; no global-optimum or final-quality claim is made for it. Exceptions, missing/unrun cases and skipped cases remain visible. The runner refuses to overwrite an existing summary. An interrupted batch retains its NOT_RUN cases; a repeat requires a separate fresh registration and must remain distinguishable from the original batch.

Registration writes every model and case, configuration rules, schedule/protocol/executable hashes, budgets and JVM settings before any main simulation. Run mode verifies registration rather than silently replacing it. Test executions before registration are validation, not main measurements.

## Metrics, audit and decision criteria

For every completed run export journal, decisions, final state and ordinary cost/service metrics. Independent SE1 journal accounting is reused unchanged; SE2 audits attack generation, matching controls, complete registration, absence of changed decisions before knowledge, censoring and diagnostics built from factual event prefixes. Diagnostics are post-run explanations and never inserted as wakeup events.

Primary safety/service quantities: time from ground detection to first delivered independent valid frame in the target context, recovered/censored counts, confidentialDeliveredBytes, before/after disclosure exposure, emissions after GS/SC notice, served contacts, and delivered/useful losses against the same-policy no-attack control. No recovery is a censored value at observation end, never zero. NO_TARGET is separate from recovered and censored. Do not infer a main effect from an average over recovered runs alone.

Alongside these report total delivery/emissions, security CPU GS+SC, management UL+DL bytes, created keys, search seconds/nodes, completion coverage by policy/workload/block. Incomplete cases are excluded from final quality comparisons, not from coverage denominators. Compare Multi and Next only when both matching cases finished; compare each attack with its own finished control. Preserve all four policies and wins/ties/losses, including losses for Multi.

Explain reduced exposure using its pre/post split and delivered volume: less historical captured traffic, suppressed traffic, or independent recovery. An installed PRE_ACTIVE spare must be distinguished from merely prepared material and from an already active independent pair. Record factual frame phase immediately at attack/ground notice and time until next target contact; the latter is descriptive post-run geometry, not policy knowledge.

Use absolute counts, ranges and fractions within this registered deterministic grid. Do not interpret fractions as real-world success probabilities. Conclusions are classified as supported across tested conditions, conditional, or unconfirmed; distinguish extra horizon from shared mandatory response, frame phase, workload ceilings and model assumptions.

## Deliverables

Separate `target/security-evaluation-se2` raw outputs and `docs/security-evaluation-se2` audit tables, figures (PDF/SVG/PNG), report, manifest and evidence package. Include all registered cases/statuses, own-control deltas, paired four-policy metrics, attack-time recovery/exposure charts and compact workload/contact comparisons. Preserve hashes of frozen SE1/v2 artifacts and list tests actually run. No optimizer, objective or policy redesign is authorized by this experiment.
