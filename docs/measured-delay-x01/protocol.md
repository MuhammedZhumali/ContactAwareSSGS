# Measured decision latency in X01

This experiment follows `instruktsiya_magistrantu_eksperiment.docx`. The question
is whether the larger-budget offline Multi advantage survives charging the
measured selection pipeline to the contact timeline. Outcomes are not prescribed.
The article is not edited until the user reviews these results and approves it.

## Registration and ownership

Parent owns preservation, configuration, all serial JVM execution, analysis,
figures and packaging. A single implementation agent owns only the measured
controller compatibility fix and its focused test; a second read-only agent
audited the archived configuration. No concurrent heavy JVMs or automatic retries.
The machine-readable manifest and 88 configurations are frozen before execution.

## Archived configuration and explicit corrections

Use the exact archived `target/final-research/models/X5.json`, labelled X01 in
this series. C1=A[0,20], C2=B[30,50], C3=A[80,85] seconds. No initial spare pair,
no attacks or failures. Preserve all initial keys, counters, deadlines, resources,
costs and payload from the file; cryptoperiod150s, material age400s, reuse gap200s,
100B frames at400B/s, minimum400B per direction per contact.

The instruction's summary says t=0, but the successful full autonomous archived
controllers start at t=-2. The instruction also requires transferring the complete
archived configuration rather than reconstructing it from the summary. We retain
t=-2 for every new run. The saved diagnostic root alone starts at t=0.
The actual archived full invocations confirm search30s and decision32s, not only
the diagnostic protocol. Preserve these timers and 150000 nodes per search,
controller180s, external watchdog210s and `-Xmx1024m` for both horizons and rules.
Next H1 and Multi H2 differ only in visible horizon within each timing mode/rule.

Keep acceleratedServiceFirst traversal, V2_EVENT_BOUNDARIES, both existing final
tie orders, five criteria, `SEED_AND_RETENTION`, seed/suffix350ms and10000 transition
caps. No oracle, external incumbent, precomputed plan or warmed JVM. Each attempt
is a fresh process with default JDK21.0.10 JIT/GC settings and no explicit warmup.
The common no-warmup choice includes cold process effects in controller decisions;
JVM startup, initial deserialization and post-run exports are measured separately
by the process wall clock, not charged as decision time.

## Compatibility fix before registration

The frozen measured controller omits the certified ContinuationReuseGuard used
by the offline controller. Integrate its existing assess/start/derive rules into
the measured decision scope, without changing planner/portfolio/physical intent
revalidation. This is a compatibility correction, not a solver optimization.
The old final-v2 JAR stays immutable; compile the changed controller into a new
identified JAR derived from it. Run all controls and pilots on this same identity.
Focused deterministic tests cover charged proof verification, zero-delay behavior,
stale proofs, WAIT revalidation and in-flight work. No pilot starts before passing.

Measured mode uses fixed latency0 and host scale1. The monotonic interval includes
observation/wakeups, candidate generation, guard assessment/proof preparation,
seed construction, retained-plan validation, bounded search and final selection.
Checkpoint persistence, fresh admission, execution and post-run exports remain
outside this charged interval, as in the original latency model. They are included
in controller/process elapsed where applicable; do not claim all host overhead is
charged. Each measured interval advances committed work/events immediately through
the shared executor, then revalidates intent. No retrospective subtraction.

## Fixed order and gates

1. Four offline controls: CONTINUE_WAIT H1,H2 then CANONICAL H1,H2.
2. Check CW final five-coordinate scores against archived H1=(2,38,.12,160,0)
   and H2=(3,37,.83,1240,2). A failure blocks further science until explained;
   do not tune settings to obtain a measured result. CAN limits are outcomes.
3. Four measured pilots in the same order; inspect decision/event evidence,
   charging, autonomous start, revalidation, costs/counters and incomplete status.
4. After a saved pilot approval, 80 main attempts: CONTINUE_WAIT20 pairs followed
   by CANONICAL20 pairs. Odd pairs H1 thenH2; even pairs H2 thenH1. One JVM at a
   time, identical machine/runtime configuration, no budget escalation.

88 planned attempts excludes tests and document/analysis tools. No automatic
technical retries. An interrupted process keeps its directory/checkpoint; do not
silently reuse its ID. Report any missing attempts and the technical reason.

## Analysis and deliverables

Keep runs.csv, decisions.csv, contacts.csv, all raw exports, per-attempt invocation,
process timing/status, fixed configurations and hashes. Finished final scores are
reconstructed from events; incomplete S/U and other final metrics stay empty,
with prefix metrics separate. CPU means modeled GS+SC operation CPU; bytes UL+DL;
new keys include all created directional material. Planner/host costs stay separate.
The legacy top-level measured deadline counter is not used: count actual latency
records. The adapter exports the effective32s deadline explicitly.

For each rule show20 planned pairs, completed pairs, S wins/ties/losses, incomplete
pairs, paired DeltaS/DeltaU and relative changes (no percentage when Next=0).
Give medians/ranges for DeltaU and decision durations, with denominators. Count
stale/replan/deadline reasons separately. Any unfinished pair has no policy ranking.
Choose the median-DeltaU completed pair per observed S outcome, breaking selection
ties by pair ID, for a compact contact/planning/INSTALL/payload timeline. If only one
outcome exists show one representative pair. Trace the first loss of opportunity
from actual logs, without attributing unmeasured causes or inventing a new series.

Deliver a short results-first report and separate proposed article wording, a
reproduction README, manifest, source/JAR/configuration hashes, original journals,
analysis/plot scripts and an additive package. Preserve the published archive and
the user's modified article. Any article update and new publication wait for user
approval after results. Repeated X01 observations do not establish onboard
feasibility, universal horizon superiority or per-decision optimality.
