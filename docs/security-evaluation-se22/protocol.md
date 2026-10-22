# SE2.2 — fixed, limited tie-break sensitivity protocol

Fixed before experimental execution. Research question: which effects persist
under horizon changes, which depend on selection among equally scored plans,
and which require both? No general claim over all tie-break rules is tested.

## Version and invariants

Preserve SE2, SE2.1 and Claude audit bytes. Baseline local commit:
`d3a3a4b77a3c9afa71420222d5fb70813b4d841a`.
Commit SE2.2 code/protocol before clean Maven verify in a separate directory
extracted from that commit, with no old target/classes. Bind the source commit,
Git tree, working-tree status, archive/source hashes, tool versions, build logs,
new JAR and preserved inputs in the machine manifest before experiments.
New code never replaces either previous frozen JAR.

Actions, constraints, threat model, incident response, traffic, cryptoperiods,
five objectives, time model V2_EVENT_BOUNDARIES and accelerated search options
remain unchanged. Next H1 and Multi H2 differ only in visible contacts within
each rule. Search still consumes no modeled contact time.

## Two complete orders

First compare exactly: maximize served contacts; maximize useful secure time;
minimize SC+GS control CPU; minimize UL+DL management bytes; minimize new keys.
Only when ALL five components are equal compare plans lexicographically:

* CANONICAL: original action tuple (kind PREPARE, INSTALL, SWITCH, OPEN, REUSE,
  CONTINUE, WAIT; at; station; context; key IDs; until; contact), shorter prefix first.
* CONTINUE_WAIT: action group CONTINUE, WAIT, security operations; then until;
  then the original action tuple. This makes Claude's stable group/until sort
  explicit, including its service/service order. Shorter prefix remains first.

The selected order controls final winner, parent/cached suffix winner, equivalent
WAIT representative, and equality-bound prefix pruning. The cache remains local
to one search. Traversal uses the same original canonical secondary ordering for
both treatments; changing traversal alone is not a change of objective/tie-break.

## Registered controller matrix: 52 runs, one attempt per cell

Every row below crosses Next/Multi with CANONICAL/CONTINUE_WAIT (4 cells).
Only detected attacks are in this finite series; definitions/absolute instants
are copied byte-for-byte from existing SE2 model files. Detection delay is 2 s.

| Fixture | Model variants | Cells |
|---|---|---:|
| X | none, original t10, p70 t14, p90 t18, gap-midpoint t25 | 20 |
| ISS first3 CP5400 LIGHT | none, original AOS+15s, p30 | 12 |
| Y | none, original t10 | 8 |
| ISS first3 CP7200 LIGHT | none, original AOS+15s, p30 | 12 |

Source names and hashes are in the manifest. No outcome-driven selection or
extension to the old 488-case grid. Controls are separate for each horizon/rule.
One heavy child JVM at a time, Java21, -Xmx1024m, GC logging. Fixed per-search
limit **2,000,000 visited nodes**, as in Claude's probes; **30 s elapsed** safety
limit per search, **60 s cumulative** controller budget, **90 s external**
process watchdog. These are new explicitly registered budgets, not SE2's 100k/5s.
No adaptive retries or budget escalation. INCOMPLETE stops a controller and has
no final quality score. Save atomic checkpoint, journals/partial progress and
reason. Retain errors/kills/not-run rows. Timing is descriptive, not a benchmark.

## X mechanism before expansion

Replay saved X-multi-none state before decision3 (t1 PREPARE) and decision12
(t3 INSTALL), without historical searches. For each state construct H1/H2
observations from the SAME factual snapshot. For each of two rules run the
unrestricted root search and a separate search constrained to each EXACT first
candidate. All searches retain the original evaluationEnd, score origin and
visible contacts. Do not rebase child scores or reveal new future contacts.

Each constrained search uses 2M nodes/30s; one state/horizon probe process gets
120s cumulative/150s external. Persist results after each search. Report candidates
whose completed continuation equals a proved root optimum. If any root/candidate
search is incomplete or absent, mark the optimum-action set incomplete; an inferior
incumbent cannot prove exclusion. Candidate lists alone are not evidence of ties.
Do not run these diagnostics at every actual controller decision.

Claude programs remain immutable. Compile TieProbe.java and TieBreakSensitivity.java
against frozen se21.jar, with sources/logs included in evidence. Run original
TieProbe X2 (external120s). A separate transparent adapter calls the original
TieBreakSensitivity.run for X original, H1/H2 x canonical/lazy only (4 processes,
60s cumulative/90s watchdog each). This reproduces its implementation without
invoking its unfiltered 56-case main. Original implementation's incomplete-score
caveat is retained, so only finished/proved results support comparisons.

Run original audit probes, X mechanism probes and the 20 X controllers first.
Write x-intermediate.json and an intermediate note before any ISS/Y execution.
If X exposes a correctness disagreement, stop expansion, preserve the attempt,
fix/version separately rather than silently reuse its registration.

## Outputs and validation

Raw actions, key observations, five final metrics, disclosed before/after bytes,
confidential delivered traffic, notice/pair/SA/frame recovery milestones and
readiness intervals from the unchanged SE2.1 postprocessor; search nodes, CPU,
elapsed and status. Compare full factual runs and own matched controls, not just
a horizon score. Canonical finished runs compare to frozen SE2 factual journals.
Include every registered cell in completion coverage; quality comparisons require
both runs finished. No zero substitution for missing recovery or failed search.

Tests: two orders against small full DFS, reversed traversal, memo and equality
pruning, five-metric dominance over tie-break, constrained-first oracle accounting,
existing cancellation and simulator tests; full clean Maven verify. Postprocess
all runs with independent factual security accounting and milestones. Report
reproduced/robust-within-these-two/conditional/incomplete findings separately.
