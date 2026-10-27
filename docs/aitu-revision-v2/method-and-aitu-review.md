# Method and AITU review for revision v2

Reviewed 2026-10-28. This is a read-only code/source audit and an integration note, not a new experiment or submission certification. The requested scope is sections 3 and 5 of `C:/Users/Muhalek/.codex/attachments/5cca16af-4be6-40a1-9871-63c55db639f8/pasted-text-1.txt`. Only this review file is authored here. No JVM, tests, manuscript edits or generator edits were performed.

## 1. Code identity and scope

The repository HEAD read during this audit was `3621333d8093b4cb0c232c7db9164c66e273053c`. The descriptions below concern the **working-tree source**, not that commit alone: `ExactHorizonPlanner.java` is modified; `FeasibleSeedPlanBuilder.java`, `PracticalPlanPortfolio.java` and `PlanningLatencyController.java` are untracked at inspection. A read-only SHA-256 comparison independently confirmed that all eleven specifically audited files (seed, result comparator, tie enum, action record, options, portfolio, latency controller, exact planner, action generator, branch engine and ordinary controller) match their entries in [final-v2-sources.json](C:/Users/Muhalek/ContactAwareSS/target/final-research/final-v2-sources.json). The parent additionally reports a complete 112-file source-manifest match and the frozen JAR/source-archive provenance check. This ties the inspected text to that frozen source snapshot; it is not independent bytecode analysis. The parent reports full-controller runs use the frozen JAR without the observational overlay; the new root diagnostics use the separately checked observational overlay. Preserve that distinction and the full hashes in the final package.

All code links below are absolute workspace paths with the starting line of the relevant implementation. The practical path defaults to `SEED_AND_RETENTION` in [BudgetedHorizonPolicy.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/BudgetedHorizonPolicy.java:26); the separate `PC1` variant bypasses the portfolio and has a narrower feasible-suffix reuse path. Do not describe PC1 as though it runs the default portfolio.

## 2. Actions and feasible candidate generation

An action is `(kind, at, until, contextId, keyPairIds, contactId)`. Its interval must be positive. The seven kinds are `PREPARE, INSTALL, SWITCH, OPEN, REUSE, CONTINUE, WAIT`. `WAIT` has no context, keys or contact. `PREPARE`, `INSTALL`, and `SWITCH` have uplink/downlink key IDs; the others do not. `PREPARE` has no contact binding; every other non-WAIT action has one. Deferred starts are represented by WAIT followed by a fresh decision. See [PlanningAction.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PlanningAction.java:7), lines 7–32.

The shared generator uses only the supplied observation. PREPARE requires an authorized, non-ABSENT context and no existing PRE_ACTIVE material or pending preparation; IDs are generated deterministically. Contact-bound INSTALL/SWITCH/OPEN/REUSE and CONTINUE additionally pass their ordinary state, admission, expiry, uncertainty and resource checks. CONTINUE admits one full frame in an available direction, with the ordinary security checker. WAIT targets the finite future wake-ups plus the horizon endpoint. Generated candidates are initially sorted with the **CANONICAL action comparator**, independently of the selected full-plan tie rule. See [SecurityActionGenerator.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/SecurityActionGenerator.java:12), lines 12–59 and 62–117.

Nominal branch execution rechecks explicit replayed actions against the generator. WAIT and CONTINUE advance only to the earliest relevant wake if it precedes the requested `until`; CONTINUE requests payload admission, WAIT does not. Starting a security operation is a separate transition. Thus, the stored `until` is an action field used in ordering; it is not a promise that no intervening event occurs. See [BranchEngine.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/BranchEngine.java:18), lines 18–46, and identical actual-controller dispatch in [SecurityPolicyController.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/SecurityPolicyController.java:317), lines 317–334.

### Resource restrictions: code rules versus scenario parameters

The ISS long/late setup table should identify these as shared **engine/generator assumptions**, not invented JSON fields:

| Restriction | Exact implementation and qualification |
|---|---|
| One ground CPU reservation per ground station | Each GROUND_CPU phase reserves `ground:<groundStationId>`; overlap on that named resource is forbidden. Different station names are distinct resources. This specifies exclusive phase occupancy, not hardware throughput or CPU cores. |
| One shared spacecraft CPU reservation | Each SPACECRAFT_CPU phase reserves `spacecraft`; overlapping reservations conflict. |
| One shared radio reservation | Both UPLINK and DOWNLINK phases reserve the same `radio` resource, so their management phases cannot overlap. This is not separate uplink/downlink capacity. WAIT phases reserve no resource. |
| One payload frame in flight; no conflicting online management | Contact-bound primitive generation rejects an in-flight frame and an already ongoing contact-bound operation for the context. CONTINUE rejects an in-flight frame or contact-bound work on that contact. Ground-only PREPARE is not intrinsically excluded by an in-flight frame in the general generator. The seed's choice to WAIT during any committed work is more conservative. |
| At most one spare directional key pair per context on generated paths | PREPARE is offered only when that context has no PRE_ACTIVE key and no pending PREPARE. Generation creates both directional keys together; INSTALL/SWITCH requires one PRE_ACTIVE uplink and one downlink key. This is a generator restriction, not a global storage capacity or numeric RAM parameter. The standalone `LifecycleTransitions.prepareKeys` does not enforce a separate spare-capacity bound, so do not generalize it to arbitrary injected states/manual transitions. |

Evidence: [SecurityActionGenerator.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/SecurityActionGenerator.java:27), lines 27–44, 69–95 and 101–117; [SimulationEngine.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/SimulationEngine.java:398), lines 398–405 and 501–519 (actual reservation conflict admission), 664–698 (frame admission); [LifecycleTransitions.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/LifecycleTransitions.java:46), lines 46–56. Cost-phase durations are scenario data; these resource-name mappings and exclusion checks are code behavior. No physical storage budget or cryptographic implementation performance is inferred.

## 3. Exact deterministic seed rule

The seed is a feasible rollout, not an optimizer, lower bound on runtime or optimality proof. It repeatedly uses the ordinary generator and immutable branch executor until the **whole current evaluation horizon** is reached. The default cap is 10,000 transition attempts; the portfolio additionally gives seed construction a 350 ms local scope subject to outer deadlines. Interrupted or nonterminal rollouts supply no terminal score. A completed rollout whose journal contains ACTION_FAILED or PAYLOAD_REJECTED is rejected. See [FeasibleSeedPlanBuilder.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/FeasibleSeedPlanBuilder.java:15), lines 15–72, and [PracticalPlanPortfolio.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PracticalPlanPortfolio.java:63), lines 63–79.

At each seed decision:

1. If any frame is in flight or any operation is ongoing, choose the earliest generated WAIT. The seed does not start overlapping new work in this case, even when some such action could be generated.
2. Otherwise, if the current contact has at least one remaining complete uplink or downlink frame, consider actions for its exact spacecraft/station context in this **seed service order**: **CONTINUE → OPEN → REUSE → SWITCH → INSTALL → PREPARE**. Select the first kind with a candidate passing the finish filter; within that kind use the selected `PlanTieBreak` action comparator.
3. The finish filter rejects uncertain contexts and requires `action.until + remainingService <= contact.end`. Remaining service is zero for CONTINUE; one frame for OPEN/REUSE; OPEN plus one frame for SWITCH; SWITCH+OPEN+one frame for INSTALL; INSTALL+SWITCH+OPEN+one frame for PREPARE. This is an optimistic fit check in addition to normal candidate feasibility, not a service guarantee.
4. If there is **no current contact**, scan visible contacts in their supplied chronological order. Skip contacts without full-frame demand or whose context is absent, ABSENT, uncertain or unauthorized. At the first remaining contact, select a generated PREPARE only when the context is UNKEYED and `now >= contact.start − PREPARE.duration`; choose among matching PREPARE actions with the selected action comparator. Then stop scanning that contact whether or not PREPARE was selected. An already keyed context is left for its ordinary opening/expiry behavior; this seed does not scan farther to prepare another contact in that case.
5. In all other cases, choose the generated WAIT with smallest `until`, using the selected action comparator only for equal `until` values. A current contact without remaining full-frame demand also takes this WAIT path; it does not use the no-current-contact preparation branch.

Exact locations: [FeasibleSeedPlanBuilder.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/FeasibleSeedPlanBuilder.java:75), lines 75–125 (choice), 128–135 (demand), 137–151 (fit). The resulting seed may miss a globally beneficial preparation despite using the same feasible action generator as search.

## 4. Full-result ordering, including complete action tuples

All candidates are compared lexicographically by: **maximize served contacts; maximize useful secure time; minimize control CPU duration; minimize management bytes; minimize new keys**. Only exact equality of all five invokes plan ordering. Instant and Duration comparisons retain nanosecond precision. See [PlanningResultComparator.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PlanningResultComparator.java:18), lines 18–31, and [PlanTieBreak.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PlanTieBreak.java:3).

**CANONICAL action tuple**, ascending:

`(kindRank, at, groundStationId, contextId, uplinkKeyId, downlinkKeyId, until, contactId)`

Here `kindRank` is PREPARE=0, INSTALL=1, SWITCH=2, OPEN=3, REUSE=4, CONTINUE=5, WAIT=6. Strings use Java `String.compareTo` lexical order, not numeric interpretation of suffixes. `contextId` compares spacecraft ID first and station ID second; station therefore appears both as the earlier explicit field and within context. Null sorts before a non-null value. See [PlanningResultComparator.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PlanningResultComparator.java:47), lines 47–100; [ContextId.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/ContextId.java:11); [KeyPairIds.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/KeyPairIds.java:4).

**CONTINUE_WAIT action tuple**, ascending:

`(group, until, kindRank, at, groundStationId, contextId, uplinkKeyId, downlinkKeyId, until, contactId)`

`group` is CONTINUE=0, WAIT=1, every security operation=2. The leading `until` is compared **before kindRank and start time**, including among different security-operation kinds. The later `until` is the unchanged canonical tuple field and is redundant once the leading comparison tied. This is more precise than saying merely “CONTINUE, then WAIT, then canonical.” See comparator lines 48–53 and 94–96.

**Whole-plan order** compares the first unequal action using the selected action tuple; a proper prefix sorts before its extension. There is no summed timing term or local action preference preceding the five objective components. The diagnostic enum name `CANONICAL_PLAN` covers the plan-order criterion even under CONTINUE_WAIT; it does not mean that the tie rule was silently changed. See comparator lines 39–44 and 71–79.

## 5. Traversal is a separate search configuration

`ExactHorizonPlanner` is a finite depth-first search with a selected full-result comparator and a **separate always-CANONICAL action comparator for traversal secondary ordering**. `candidateOrder` must be a permutation of all generated actions. Reordering does not itself delete feasible actions; under a budget it can change which incumbent is discovered before stopping. See [ExactHorizonPlanner.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/ExactHorizonPlanner.java:17), lines 17–20 and [ordering implementation](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/ExactHorizonPlanner.java:323), lines 323–379.

| Traversal option | Primary visitation rank; equal ranks use CANONICAL action tuple |
|---|---|
| CANONICAL | Leaves the generator's canonical action order intact, before any explicit candidate-order permutation. |
| SERVICE_FIRST | CONTINUE, SWITCH, INSTALL, OPEN, REUSE, WAIT, PREPARE. This differs from the seed's service order. |
| RENEWAL_FIRST | INSTALL=0; SWITCH when context is not OPERATIONAL=1; OPEN=2; REUSE=3; PREPARE when renewalVisible=4; CONTINUE=5; WAIT=6; SWITCH when context is OPERATIONAL=7; other PREPARE=8. |

`renewalVisible` is true if, for either direction, there is no ACTIVE key for the action's context whose hard deadline is strictly later than the **latest visible start** of a contact for that context. It is an observation-only traversal heuristic, not a feasibility test or final-plan tie-break (lines 354–367).

The constructors default to `PlannerOptions.accelerated()`: equivalent-WAIT collapse, memoization (50,000 entries), RENEWAL_FIRST, optimistic bounds, pre-execution WAIT reduction and canonical-bound pruning enabled. `reference()` disables the accelerations and uses CANONICAL traversal; `acceleratedServiceFirst()` changes traversal to SERVICE_FIRST while retaining the same acceleration flags. `reuseOnly()` uses SERVICE_FIRST, WAIT collapse and memoization but not those latter bound/reduction flags. See [PlannerOptions.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PlannerOptions.java:5), lines 5–36. The actual experiment configuration still must come from its saved configuration; constructor defaults alone are insufficient evidence for a run.

## 6. Portfolio, retained continuation and first-action execution

For the default practical portfolio, create the seed, validate/replay any retained suffix, run bounded unrestricted search, and compare their **fully evaluated** terminal results at the same current root. Search is called as `selectUnrestricted(root, ...)`; the seed and retained candidate are not passed into it as an incumbent. See [PracticalPlanPortfolio.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PracticalPlanPortfolio.java:63), lines 63–153.

Retention requires the predicted post-first-action observation to match current time, horizon, model, contact, state/counters, committed work, uncertainty, progress, costs and issued IDs. Only permitted forecast/horizon-end growth is tolerated: the old contacts must remain an identical prefix and the endpoint cannot shrink. The old actions are replayed with ordinary feasibility checks **from the new root**, must reach the old endpoint, and are then extended by the same seed builder to the new endpoint. Its prior score is not reused. A separate 350 ms local scope and parent stop checks apply. Timestamps are not shifted. See lines 82–104 and [anchor checks](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PracticalPlanPortfolio.java:156), lines 156–176.

Candidates are appended SEED, RETAINED, SEARCH. The five-objective/full-plan comparator chooses the winner. On complete equality of both evaluation and action list, SEARCH wins the source tie; otherwise an earlier identical seed is retained over an identical retained candidate. A terminal WAIT search incumbent receives no special exemption from this comparison. The selected first action must belong to the root's generated candidate list. If no fully evaluated candidate exists, the local fallback targets the current contact or the first visible contact, applies CONTINUE→OPEN→REUSE→SWITCH→INSTALL→PREPARE within that target and selected action comparator, else takes earliest WAIT. Unlike the seed, this fallback does not run a full-horizon rollout, demand scan or optimistic service-fit test. See portfolio lines 124–153 and [BudgetedHorizonPolicy.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/BudgetedHorizonPolicy.java:126), lines 126–139.

Only a winning SEARCH candidate from an actual COMPLETE unrestricted search exposes its certificate. Seed/retained/bounded-search-incumbent/fallback selections are rules without an optimality certificate. Global cancellation, interruption and controller stops are not converted to fallback permission. The source-tag equality tie is not a sixth scientific objective. See portfolio lines 115–120, 148–153 and [PolicySelection.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PolicySelection.java:5), lines 5–17.

The **ordinary controller** can separately reuse a proven exact suffix through `ContinuationReuseGuard`. It stops on a non-COMPLETE attached search result and executes only a generated first action. This exact-proof mechanism must not be conflated with the practical portfolio's feasible retained candidate. See [SecurityPolicyController.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/SecurityPolicyController.java:129), lines 129–191.

The **latency controller** is a separate sensitivity path. It measures observation construction, candidate generation and selection; checkpoint persistence, actual execution and fresh admission are outside that measured interval. It sets modeled latency to `fixedLatency + ceil(hostRuntimeScale × hostNanoseconds)`, advances the physical model while existing committed work/events continue, and regenerates candidates at the new instant. Mandatory incident response takes precedence. A fresh candidate must have the same kind, context, directional key IDs and contact binding as the selected intent; among matches it takes smallest `until`. It does not shift an old schedule or transfer an old proof. Reached or changed WAIT events and stale intents trigger replanning; a missed decision deadline discards the result and uses earliest admissible WAIT. See [PlanningLatencyController.java](C:/Users/Muhalek/ContactAwareSS/src/main/java/org/contactaware/security/simulation/PlanningLatencyController.java:11), lines 11–20, 54–75, 85–149, 174–176. Revalidation/dispatch remains essential even when the optimizer found a good plan at its original instant.

## 7. Compact integration pseudocode

```text
repeat at each actual decision boundary:
    O := construct observation under the chosen contact horizon
    A := shared admissible action generator(O)
    if mandatory incident response exists: execute that response; continue
    within the common decision deadline:
        C := empty candidate collection
        S := deterministic seed rollout(O, 350 ms local scope, 10000-transition cap)
        if S reaches O.endpoint without failed/rejected work: add S to C
        if retention enabled and predicted child matches O:
            replay retained actions from O using ordinary admissibility
            require old endpoint; seed-extend to O.endpoint within 350 ms
            if a complete feasible result exists: add it to C
        R := bounded unrestricted DFS(O, configured traversal, full-result comparator)
        propagate cancellation/controller stops
        if R has a terminal feasible incumbent: add it to C
        P := best candidate by five objectives, then selected whole-plan tie rule
        a := P.firstAction, or deterministic local fallback if C is empty
        if retention enabled and P exists: predict a's child; store P's suffix
    if using the latency sensitivity controller:
        advance actual model by measured/modeled computation delay
        rebuild observation and candidates; give incident response priority
        replan for reached/changed WAIT event or stale intent after time advance
        revalidate a's intent; deadline miss discards a and chooses earliest WAIT
    execute only the admissible first action; return to observation
```

This pseudocode is the **default practical portfolio path**, with an explicit optional latency branch. The offline exact controller has no seed portfolio requirement and does not charge host search time as physical delay. Nonterminal bounded work has no score; a certificate requires the actual COMPLETE unrestricted result. Exact diagnostic results after a common prescribed PREPARE belong to that prescribed-root series, not to nine autonomous practical-policy comparisons. A first useful incumbent observed at 134,269 nodes in one search is not a universal lower bound.

## 8. AITU and bibliography verification

### Official requirements refreshed

The [official AITU requirements page](https://journal.astanait.edu.kz/index.php/ojs/requirements) was reopened on 2026-10-28. It still sets minimums of eight pages and twenty references; fundamental/applied source-age limits of ten/five years; a 30% Scopus/Web of Science requirement; 60% foreign sources; and at most 20% self-citation. It gives both 250–1900 abstract characters including spaces and 250–280 English words. It requires APA entries in first-citation numeric order, MathType equations, and author name, degree, department, university, email and ORCID. Margins remain top 2, bottom 2.5, left 3, right 1.5 cm. Its wording does not define whether “cited in Scopus and Web of Science” means indexed records or cited-reference occurrences, or whether both databases are required. Twenty references imply at least six qualifying records if 30% is applied to that denominator. No standards exemption or age-window convention is stated. The page remains the primary authority; the prior [submission checklist](C:/Users/Muhalek/ContactAwareSS/docs/aitu-revision/submission-checklist.md) records the template conflicts and unresolved author fields.

The draft's **native editable Word OMML** objects are not MathType. Keep that factual format description. Visual inspection and editable equations do not resolve the journal's object-format requirement; obtain editorial acceptance or use and verify a supported MathType conversion before certifying conformity. Previously measured v1 abstract/title counts are not automatic evidence for revised v2 text: recount the final content and rendered pages. No editorial acceptance or submission is claimed.

### Age classification of the existing twenty records

This is a classification of the existing [literature-draft.json](C:/Users/Muhalek/ContactAwareSS/docs/aitu-revision/literature-draft.json), retaining its primary metadata verification and caveats. It is not a new full bibliography audit. With a strict rolling cutoff on 2026-10-28, five years means publication on/after 2021-10-28, ten years on/after 2016-10-28.

| Group | Keys and evidence | Assessment |
|---|---|---|
| Eleven dated records from 2022–2026 | `sdls`, `skm`, `iota`, `bp`, `bpsec`, `detke`, `bermuda`, `bpsec_contexts`, `madoery`, `bpsec_analysis`, `kwon` | Within five years by their recorded publication dates. This 11/20 (55%) count is age evidence, not an indexed-share or overall-compliance claim. |
| Five sources outside five years, within ten | `ep` (2020 [CCSDS edition](https://ccsds.org/Pubs/355x1b1.pdf)); `sabr` (2019 [CCSDS edition](https://ccsds.org/Pubs/734x3b1.pdf)); `dtka` (2018 [expired individual draft](https://datatracker.ietf.org/doc/html/draft-burleigh-dtnwg-dtka-02)); `zhao` (2020-11-01 [publisher](https://rebicte.org/index.php/rebicte/article/view/94)); `cgr` (2021-01-15 [publisher](https://www.sciencedirect.com/science/article/pii/S1084804520303489)) | Require an honest fundamental/applied classification or editorial exception. CGR is outside a rolling five-year window despite its 2021 calendar year. Standards and the historically relevant preauthentication/DTKA precedents should not be silently re-dated or replaced by irrelevant newer citations. |
| One source outside ten years | `rhc`: 2010 [author-hosted published paper](https://web.stanford.edu/~boyd/papers/pdf/code_gen_rhc_msc.pdf) | Foundational relevance does not itself satisfy the stated age rule. Justify an exception or replace only with a substantively appropriate recent source. |
| Three evolving documentation sources | `celestrak`: original 2020 page updated 2026, [official documentation](https://celestrak.org/NORAD/documentation/gp-data-formats.php); `satnogs`: n.d., [official API documentation](https://docs.satnogs.org/projects/satnogs-network/en/stable/api.html); `orekit`: n.d., [official project](https://www.orekit.org/) | A retrieval/update date is not a new research publication. Keep these for actual software/input provenance with archived versions and dates; obtain interpretation of their treatment under the age rule. |

In total, six dated records exceed five years, including the one exceeding ten; three documentation records need separate treatment. No blanket recency-compliance claim follows from the recent eleven. Keep the publisher-confirmed 2025 date for `bpsec_analysis`, the cited 2026 volume year for `iota`, and the 2025 book-chapter date for `bermuda`; later preprint revisions do not change their publication years. `madoery` remains metadata/topic verified with detailed full-text claims limited; that is independent of age/index status.

### Database evidence obtained, and its precise limit

This focused refresh did not add references or attempt a twenty-item database audit. Only primary database-owner, publisher or author-institution sources are used below. Third-party index aggregators encountered in search were not accepted as evidence.

| Record / evidence level | Current check | What is and is not established |
|---|---|---|
| `kwon`, institutional article-level Scopus evidence | [KNU's publication record](https://research.knu.ac.kr/en/publications/an-efficient-handover-authentication-scheme-for-6g-enabled-space-/) explicitly identifies DOI `10.1109/TIFS.2025.3628470`, a Scopus publication link and accession `SCOPUS:105020927427`. The [linked Scopus record](https://www.scopus.com/pages/publications/105020927427) could not be opened by the web tool. | One of twenty (5%) has an independently inspectable author-institution record reporting an exact Scopus accession. Direct live Scopus verification and item-level Web of Science verification remain absent. Do not promote this to a joint-database count. |
| `iota`, publisher journal-level claim | [Tech Science Press CMC indexing page](https://www.techscience.com/cmc/info/Indexed) names the exact journal/ISSNs 1546-2218 and 1546-2226 and lists Scopus plus Science Citation Index Expanded under Web of Science. | One of twenty (5%) has a publisher claim of both databases for its journal. This does not independently verify this particular 2026 article, its citation status, coverage dates or current database-owner acceptance. |
| `cgr`, attempted publisher coverage check | [ScienceDirect journal insights](https://www.sciencedirect.com/journal/journal-of-network-and-computer-applications/about/insights) returned HTTP 403. | No new indexing evidence accepted; do not count the failed attempt. |
| Database-owner verification route | [Clarivate's policy](https://clarivate.com/academia-government/scientific-and-academic-research/research-discovery-and-referencing/web-of-science/web-of-science-core-collection/editorial-selection-process/editorial-policies-category-change-requests-appeals/) identifies its Master Journal List as authoritative for journal coverage. The [MJL](https://mjl.clarivate.com/home) returned only a dynamic shell in this check. [Elsevier's Scopus content guidance](https://www-prod.elsevier.com/products/scopus/content) directs users to Source Title pages/title lists, including discontinued sources. | These establish where coverage should be checked, not that any of the twenty particular records is covered. Journal coverage and a cited-reference occurrence are distinct evidence types. |

**Proven share under a strict direct item-level, both-database interpretation: no qualifying records verified in this audit (0/20 verified); actual qualifying share remains unknown.** This means an evidence gap, not that the actual indexed share is zero. The 1/20 institutional Scopus indication and 1/20 publisher journal claim are different, overlapping evidence standards and must not be added into a purported 10% qualifying share. The available evidence does not establish the required six records.

Actionable completion: ask the editor which interpretation of the ambiguous database phrase and age categories applies; then retain DOI/title-matched Scopus and Web of Science records (or the accepted cited-reference evidence) for each proposed qualifying source, including accession, collection, year/coverage and access date. For twenty references, count unique qualifying records once and document at least six. Preserve essential primary standards and nearest precedents; never pad the bibliography with unrelated indexed papers. If evidence or an exception remains unavailable, leave the checklist explicitly unresolved.

### Scientific source boundary retained

The prior literature draft's nearest-work distinction remains appropriate: IOTA already has contact-dependent onboard authorization allowlists, revocation handling and short-lived tokens; Detke already maps time-feasible routes into security-dependent key scopes and minimizes provisioning footprint. The proposed distinction is the matched-horizon study of sequential joint traffic-key/security-association lifecycle timing, with conditional feasibility and computational-budget results. Neither contact awareness, preauthentication, proactive key provisioning nor receding-horizon optimization is claimed as first-ever. Public baseline release provenance and the new revision's unpublished results must remain separate. No numerical findings were revalidated in this audit.

## 9. Handoff checks

- Method description was saved before the requirements refresh. The only authored file is this review.
- Eleven audited source hashes matched the frozen source manifest by a read-only check; the parent owns the wider 112-source/JAR/overlay provenance evidence.
- No experiment, JVM, build, test, manuscript mutation or source-code change was performed here.
- The table should cite resource restrictions as shared code assumptions and time/payload/security values as their actual frozen-model fields.
- Recheck revised title/abstract/page/reference counts on the final v2 artifact; this report does not certify submission readiness.
