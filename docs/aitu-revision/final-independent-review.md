# Independent bounded review of Stages 2-4

2026-10-28. **PASS within the inspected scope; no blocking numerical inconsistency found.** Sources were limited to the three stage protocols and each stage's saved `tables.json` and `audit.json`. Checks comprised read-only structural comparisons, exact decimal arithmetic, and paired-table aggregation. No Java, experiment, campaign analyzer, source modification, or whole-project audit was performed. This is consistency review of exported evidence, not an independent replay of raw journals or verification of every referenced file hash.

## Denominators and accounting

| Stage | New planned / attempted | New FINISHED | New incomplete | Reused | Logical table rows |
|---|---:|---:|---:|---:|---:|
| 2 | 32 / 32 | 28 | 4 SEARCH_INCOMPLETE | 4 | 36 |
| 3 | 26 / 26 | 26 | 0 | 2 | 28 |
| 4 | 36 / 36 | 36 | 0 | 0 | 36 |

All three audits report zero not-run cells. All **100 logical rows** agree with their audit's five-score records; the four incomplete Stage 2 rows retain unavailable final metrics. Stages 2-4 therefore contain **94 new attempts, 90 finished and four incomplete**, plus six explicitly reused cells. Neither reused results nor earlier historical campaigns may be added to the new-execution denominator.

## Stage 2: conditional mechanism

The table supports the following configuration-level conclusions, identically under CANONICAL and CONTINUE_WAIT:

- **X01 and X08:** Multi serves three contacts, Next two; the gain is conditional on common prescribed preparation and the registered model.
- **X02, X03, X04, X06, X07:** complete five-score ties. The short-window control serves two; the other four serve three.
- **X05 and X09:** Next completes with two contacts, while Multi stops on TIME_LIMIT under both tie rules. Their relative full-execution quality is **unknown**, not tied and not a Multi loss.

This is four winning matched pairs, ten tied pairs, and four unresolved pairs when both tie rules are counted. It does not establish practical-controller effectiveness, a universal 4/8 s threshold, or the absence of a mechanism in X05/X09. X07 also changes expiry inside C1 and must retain that qualification. The inspected X06 deadline audit has old A created at -90 s, activated at -80 s, and notAfter=100 s; this is consistent with CP180 reconstruction rather than retaining the old CP150 deadline. No counterexample to the registered conditional interpretation was found in these exported records.

## Stage 3: allowances are not achieved node budgets

All ten offline horizon pairs tie on the five-score tuple. In X5, disabling DFS produces the same service/useful time but 0.32 s lifecycle CPU and two new keys, versus 0.12 s and zero new keys for positive search allowances. The long-window offline control ties at every level. Thus this block finds no practical Multi advantage on either offline case.

Measured mode has **zero Multi wins, one tie, and three losses** among four pairs: X5 useful time is 33.5 versus 32.25 s at N=1000 and 33.5 versus 23 s at N=20000; the long control ties at 410 s for N=1000 and gives 410 versus 400 s for N=20000. These are registered single-host executions, not statistical estimates.

The 1 s search timer materially limits the higher node allowances. X5 H2 has 63 TIME_LIMIT searches at both N=20000 and N=100000; the long control H2 has 48 at both levels. Therefore a label such as "evaluated using 100000 nodes per decision" would be unsupported. Use "100000-node allowance with a 1 s search cap" and report actual totals/stop counts. Total explored nodes sum many root searches and are not a per-root consumption figure. All 28 search-stop counts sum to the exported search counts. No-DFS rows correctly have zero searches/nodes while retaining nonzero host work. The eight measured rows separately report charged decision delay; it equals computed latency in these executions, and all have zero reported decision deadline misses. Lifecycle CPU remains a different metric.

The protocol restricts this budget series to CANONICAL. Do not infer budget robustness under CONTINUE_WAIT or monotonic whole-policy improvement from these measurements. The separate seed/retention harness verification was outside this final audit's requested source scope.

## Stage 4: recovery, censoring, and traffic

All **36 executions are FINISHED, 36 targets are DISCLOSED, and 36 have RECOVERED**. All **144 milestone statuses are observed events**; this new matrix contains no censored, no-target, already-ready, or incomplete recovery case. The manuscript may explain censoring semantics, but must not claim that this matrix empirically exercised censoring. Historical censored examples remain separately identified evidence.

Every table milestone agrees with its exported audit milestone. Exact decimal epoch subtraction verifies all 36 compromise-to-ground and compromise-to-independent-frame delays, and each total equals compromise-to-ground plus ground-to-independent-frame. Every exported delivered/emitted/confidential/compromised byte value agrees with the corresponding security audit. Delivered bytes equal confidential plus compromised delivered bytes in these rows; decryptable emitted bytes use a different base and include aborted prefixes.

Across 18 matched horizon pairs, Multi has earlier independent delivery in **seven**, equal delivery time in **eleven**, and later delivery in none. All seven earlier cases are CANONICAL. Multi delivers **1000 additional confidential bytes in five pairs**, with equal confidential delivery in the other thirteen. Spacecraft notice is nevertheless **later in three CANONICAL pairs**. These findings support conditional timing/traffic improvements, not uniform recovery-stage dominance or general cryptographic security.

A necessary readiness qualification is directly preserved in the audit for `Eminus5-delay0-H2-CANONICAL`: independent generation-2 activation occurs 1.4 s after disclosure, but that pair expires at `2026-10-12T01:44:56.390386963Z`, before the next relevant contact. Generation 3 activates at `01:47:23.202056885Z`; independent SA opens one second later, and first independent delivery occurs at `01:47:34.202056885Z`, **5559.211669922 s after compromise**, equal to Next. Early first activation therefore does not imply uninterrupted readiness or earlier confidential delivery.

For the same cell, 40000 bytes are delivered, 40769 emitted, 4000 confidentially delivered, and 36769 retrospectively decryptable emitted before disclosure. The 769-byte aborted prefix explains why decryptable emitted and compromised delivered counts differ. A decrease in exposed emitted bytes alone must not be described as improved confidentiality without the associated traffic denominator.

## Disposition

No blocker was found in these saved protocol/table/audit consistency checks. Preserve the unresolved X05/X09 comparisons, timer-limited budget interpretation, CANONICAL-only budget scope, zero observed censoring in the new security matrix, and first-readiness versus sustained-readiness distinction in the article and closure table. Full provenance/hash validation and visual manuscript QA remain the parent's separate checks.
