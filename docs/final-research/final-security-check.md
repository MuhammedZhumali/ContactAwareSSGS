# Final security and management check: cases 097–108

## Scope and evidence boundary

All twelve cases are `FINISHED`. This focused read-only check compared their completed `result.json` and `actual.events.json` with the final `target/final-research/tables.json` and `audit.json`, after the parent reported analyzer completion. It did not run a JVM, another experiment, or the broad independent auditor. No active checkpoint was read.

`docs/final-research/validation.json` records `PASS`, 108 attempted cells, 93 full exported-run audits, 15 prefix-only process timeouts, and 37,164 same-root comparator checks. The final table and audit SHA-256 identities were checked, as were all 24 result/journal file hashes against `audit.inputHashes`:

- Tables: `799b9b816bb8778fc6f67b081bde99dcd09be4e4d38ff6b00b14ef47f1806f94`.
- Audit: `f46c6512e0e8fceb54401094b8978d67c834a1b3e22681f2f17a1e91449b6251`.

For these twelve rows, independent small aggregations of delivered frames, directional delivered bytes, retrospective confidential bytes, and disclosed-key emitted bytes agree with the final tables and exports. Every observed recovery milestone's audit sequence resolves to a journal event at the reported time; its delay from ground notice also agrees. No mismatch was found within this scope.

## Targeting, traffic, and the five scores

Cases 097–102 and 105–106 each contain a real `KEY_DISCLOSED` event with concrete directional key IDs. None is a no-target attack. Cases 103–104 and 107–108 have channel incidents and no attack; their recovery status is `NO_ATTACK`, not failed detection. No checked case has a `NO_TARGET` disclosure or an already-ready recovery stage.

Five-score order is `(served contacts, useful seconds, modeled operation CPU seconds, management bytes, newly generated directional keys)`. Modeled CPU is consumed GS+SC lifecycle-operation CPU, with the scope in `timing-definitions.md`; it is not search/runtime CPU. All values below are final observed values. UL/DL denote uplink/downlink.

| Cases | Five scores | Served / confidentially served | Delivered UL / DL bytes | Confidential UL / DL bytes | Exposed emitted UL / DL bytes |
| --- | --- | --- | --- | --- | --- |
| 097–098, undetected X | `(2,38,.12,160,0)` | 2 / 1 | 7,600 / 7,600 | 3,800 / 3,800 | 3,800 / 3,800 |
| 099–100, detected X | `(3,33.5,.93,1416,2)` | 3 / 2 | 6,700 / 6,700 | 4,900 / 4,900 | 1,800 / 1,800 |
| 101–102, gap X | `(2,38,.66,1056,2)` | 2 / 1 | 7,600 / 7,600 | 3,800 / 3,800 | 3,800 / 3,800 |
| 103–104, temporary channel X | `(3,32,.89,1320,2)` | 3 / 3 | 6,400 / 6,400 | 6,400 / 6,400 | 0 / 0 |
| 105, late-demand Next | `(3,400,2.88,4416,8)` | 3 / 3 | 20,000 / 20,000 | 3,000 / 3,000 | 17,042 / 17,000 |
| 106, late-demand Multi | `(3,410,2.88,4416,8)` | 3 / 3 | 21,000 / 20,000 | 4,000 / 3,000 | 17,000 / 17,000 |
| 107–108, INSTALL interruption | `(3,60,2.23,3600,6)` | 3 / 3 | 3,000 / 3,000 | 3,000 / 3,000 | 0 / 0 |

Confidential accounting is retrospective through observed disclosures. Thus service counted before later disclosure can cease to be confidential; ordinary served contacts do not imply confidential service. Exposed emitted traffic also includes an aborted frame's emitted prefix, whereas delivered traffic excludes that aborted frame.

Before/after-disclosure exposed bytes are respectively 2,800/4,800 (097–098), 2,800/800 (099–100), 7,600/0 (101–102), 33,842/200 (105), and 33,842/158 (106). The split is at disclosure, not ground notice. All detected attacked cases have zero compromised bytes emitted after ground notice and after spacecraft notice in the recorded execution. This is an observed blocking result, not proof of recovery or a general safety theorem.

## Detection and recovery are different events

Undetected X discloses `A-old-up` and `A-old-down` at `2026-01-01T00:00:08Z`, but has neither ground nor spacecraft notice. Its four stages are not applicable with reason undetected, not zero-delay recovery or right censoring after detection.

Detected X also discloses that pair at 8 s, with ground notice at 10 s. Spacecraft notice is 10.4 s (journal sequence 49), independent pair activation 17.4 s (61), independent SA readiness 18.4 s (65), and first independent delivered frame 18.65 s (67). Relative delays are `.4`, `7.4`, `8.4`, and `8.65` s in both 099 and 100.

Gap X discloses the same real target at 60 s, receives ground notice at 62 s, and spacecraft notice only at 80.4 s (sequence 180). PREPARE completes at 64 s (174), and INSTALL completes at 84.4 s (184). Neither SWITCH nor OPEN nor an independent frame occurs before the experiment ends at 85 s. The final three stages are right censored at that endpoint: `CENSORED_NO_RECOVERY` despite a completed run and zero post-disclosure exposed bytes. INSTALL completion alone does not establish activation or a ready independent SA.

## Late-demand pair: exact milestones and the observed difference

Both 105 and 106 disclose `planner-key:11:NORAD-25544:12:SATNOGS-3634:g1:up` and its `:down` mate at `2026-10-12T00:14:21.720977783Z`. Ground notice is `00:14:23.720977783Z` (105 sequence 56; 106 sequence 62). All following absolute times are UTC on that date. Parentheses give seconds since ground notice and the actual journal sequence.

| Milestone | 105 Next | 106 Multi |
| --- | --- | --- |
| Spacecraft notice | `00:14:24.120977783` (`.400000000`, 64) | `00:14:27.696295166` (`3.975317383`, 71) |
| Independent pair activated | `00:14:31.120977783` (`7.400000000`, 76) | `00:14:28.696295166` (`4.975317383`, 75) |
| Independent SA ready | `00:14:32.120977783` (`8.400000000`, 80) | `00:14:29.696295166` (`5.975317383`, 79) |
| First independent delivered frame | `00:14:42.120977783` (`18.400000000`, 82) | `00:14:39.696295166` (`15.975317383`, 85) |

The journal shows Multi PREPARE its generation-2 pair at `00:13:53.296295166`–`00:13:55.296295166` (52–54), before disclosure. It starts INSTALL at `00:14:23.296295166` (60), after finishing the preceding old-key downlink frame and before ground notice. Ground notice occurs while INSTALL is in progress; the operation completes at `00:14:27.296295166` (66), then spacecraft notification completes at the time above. Next prepares generation 2 after spacecraft notice, completing PREPARE at `00:14:26.120977783` (68) and INSTALL at `00:14:30.120977783` (72). These are observed timings; they do not establish why a particular search selected the actions.

Next aborts an uplink frame at ground notice (57): 42 of 1,000 bytes were emitted from `00:14:23.296295166` until notice, with no delivery. Its total emitted traffic is 40,042 bytes and compromised delivered traffic is 34,000 bytes. Multi aborts no frame: it emits/delivers 41,000 bytes, of which 34,000 delivered bytes are compromised. The 42-byte distinction explains 34,042 versus 34,000 exposed emitted bytes; it is not an additional delivered frame.

Multi delivers exactly one additional confidential uplink frame (1,000 bytes) and 10 more useful seconds, with identical modeled costs and ordinary/confidential served counts. Both complete four each of PREPARE, INSTALL, SWITCH and OPEN. Spacecraft notice is later for Multi while its three independent-material milestones are earlier. The data therefore support this specific timing/traffic tradeoff, not uniformly faster recovery or security dominance.

## Channel outage coverage: original case versus actual interruption

In 103–104, channel DOWN occurs at `2026-01-01T00:00:05Z` (21), followed by STOP at the same time (23–24); UP occurs at 7 s (25). No `STEP_INTERRUPTED` or `PAYLOAD_ABORTED` occurs. The outage falls at a frame boundary and hits no running management operation. After UP, PREPARE runs 7–9 s, INSTALL 9–13 s, SWITCH 13–14 s, and OPEN 14–15 s. This case demonstrates the recorded outage response and resumption; it does not exercise interrupted management execution.

Cases 107–108 do interrupt INSTALL. On `2026-10-10`, it starts at first contact AOS `00:07:54.680328369Z` (7–8); DOWN at `00:07:55.680328369Z` (9) produces `STEP_INTERRUPTED` (10) and `ACTION_FAILED` with `CHANNEL_DOWN` (11). The consumed prefix is 1 s elapsed, .10 s ground CPU, 0 s spacecraft CPU, .90 s radio, and 360 uplink management bytes. UP is `00:07:57.680328369Z` (14); retried INSTALL completes at `00:08:01.680328369Z` (17), SWITCH at `00:08:02.680328369Z` (21), and OPEN at `00:08:03.680328369Z` (25). Payload first completes at `00:08:13.680328369Z` uplink (27), then `00:08:23.680328369Z` downlink (28).

The partial .10 modeled CPU seconds and 360 management bytes are already included in the five-score totals; adding them again would double count. Both policies serve all three contacts confidentially, with no aborted payload frame. The evidence supports retry after interrupted INSTALL and service only after actual INSTALL/SWITCH/OPEN completion. It does not exercise uncertainty after an interrupted SWITCH, OPEN, or REUSE.

## Source locator

Each case uses `target/final-research/runs/<full-id>/result.json` and `actual.events.json`; sequences above are journal `sequence` values. Final comparisons use rows with these exact IDs in `target/final-research/tables.json` and corresponding keys in `audit.runs`:

```text
097-security-X-undetected-offline-next-H1-canonical
098-security-X-undetected-offline-multi-H2-canonical
099-security-X-detected2-offline-next-H1-canonical
100-security-X-detected2-offline-multi-H2-canonical
101-security-X-gap-offline-next-H1-canonical
102-security-X-gap-offline-multi-H2-canonical
103-security-X-channel-temporary-offline-next-H1-canonical
104-security-X-channel-temporary-offline-multi-H2-canonical
105-security-demand-late-detected-offline-next-H1-canonical
106-security-demand-late-detected-offline-multi-H2-canonical
107-management-ISS3-low-management-interruption-offline-next-H1-canonical
108-management-ISS3-low-management-interruption-offline-multi-H2-canonical
```
