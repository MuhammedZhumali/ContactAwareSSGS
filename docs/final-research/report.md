# Final research campaign

Among 4 completed core Next/Multi pairs, Multi is better in 0, equal in 4, and worse in 0 under the five priorities. 5 core pairs remain incomplete.

The final core does not establish a practical advantage from the extra horizon at the fixed budget. This does not disprove the conditional exact short-return mechanism or rank unfinished pairs.

One frozen JAR: `bb694a9e1c3341ab7ab9602348a34a9340daf9fcf708d6906435dcaeb35439c6`. Full Maven suite: 478 passing tests; the sandbox loopback failure and successful identical-source rebuild are both preserved.

## Coverage and interpretation

Registered/attempted: 108/108; statuses: {'FINISHED': 93, 'PROCESS_TIMEOUT': 15}. Independently audited exported runs: 93; same-root full-comparator checks: 37164.

FINISHED means full physical execution, not globally optimal planning. Incomplete final scores are unavailable; checkpoint prefixes do not enter completed quality comparisons. Costs and traffic remain synthetic. Host execution times are single observations on this machine, not onboard measurements or independent statistical samples.

## All primary mission/load cells

Each finished cell is **served / useful seconds / operation CPU seconds / management bytes / new directional keys**. An unfinished cell retains its status. Full timing, nodes, deadline, checkpoint and security data are in runs.csv. That table also reports complete delivered frame counts and bytes separately for uplink/downlink, with retrospective confidential bytes in each direction. These final totals are unavailable for unfinished executions.

| Mission | Load | FixedPeriod | PerContact | Next | Multi | Seed H1 | Seed H2 |
|---|---|---|---|---|---|---|---|
| ISS46 | low | 46/920/35.66/49680/122 | 46/920/32.66/49680/92 | 46/920/32.66/49680/92 | 46/920/32.66/49680/92 | 46/920/32.66/49680/92 | 46/920/32.66/49680/92 |
| ISS46 | medium | 46/2740/35.66/49680/122 | 46/2740/32.66/49680/92 | 46/2740/32.66/49680/92 | PROCESS_TIMEOUT | 46/2740/32.66/49680/92 | 46/2740/32.66/49680/92 |
| ISS46 | high | 46/14390/35.66/49680/122 | 46/14390/32.66/49680/92 | PROCESS_TIMEOUT | PROCESS_TIMEOUT | 46/14390/32.66/49680/92 | 46/14390/32.66/49680/92 |
| NOAA38 | low | 38/760/29.78/41040/104 | 38/760/26.98/41040/76 | 38/760/26.98/41040/76 | 38/760/26.98/41040/76 | 38/760/26.98/41040/76 | 38/760/26.98/41040/76 |
| NOAA38 | medium | 38/2280/29.78/41040/104 | 38/2280/26.98/41040/76 | 38/2280/26.98/41040/76 | 38/2280/26.98/41040/76 | 38/2280/26.98/41040/76 | 38/2280/26.98/41040/76 |
| NOAA38 | high | 38/19210/29.78/41040/104 | 38/19210/26.98/41040/76 | PROCESS_TIMEOUT | PROCESS_TIMEOUT | 38/19210/26.98/41040/76 | 38/19210/26.98/41040/76 |
| HST48 | low | 48/960/36.28/51840/118 | 48/960/34.08/51840/96 | 48/960/34.08/51840/96 | 48/960/34.08/51840/96 | 48/960/34.08/51840/96 | 48/960/34.08/51840/96 |
| HST48 | medium | 48/2880/36.28/51840/118 | 48/2880/34.08/51840/96 | 48/2880/34.08/51840/96 | PROCESS_TIMEOUT | 48/2880/34.08/51840/96 | 48/2880/34.08/51840/96 |
| HST48 | high | 48/15560/36.28/51840/118 | 48/15560/34.08/51840/96 | PROCESS_TIMEOUT | PROCESS_TIMEOUT | 48/15560/34.08/51840/96 | 48/15560/34.08/51840/96 |

## Search and seed control

Matched core horizon outcomes: {'EQUAL': 4, 'INCOMPLETE_PAIR': 5}. Seed/Multi comparison coverage: {'EQUAL': 8, 'INCOMPLETE_PAIR': 10}.

Same-root portfolio dominance does not imply whole-mission dominance. A seed may follow a different future trajectory; two five-score ties can have different physical slack when actual computation consumes time. The final search ledger, not the legacy COMPLETE-only computation summary, supplies attempted searches and incomplete counts.

## Unfinished execution diagnostics

The following numbers describe only the last saved physical prefix. They are not final service results and do not enter policy rankings. A watchdog may terminate the process before its in-memory search ledger is exported; unavailable node, CPU and decision counts remain unavailable. Checkpoint I/O diagnostics are retained when independently saved.

| Case | Status | Process s | Saved model time | Contacts started | Served in prefix | Checkpoint retries |
|---|---|---:|---|---:|---:|---:|
| 028-core-ISS46-medium-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.05 | 2026-10-10T20:59:18.699615479Z | 38 | 38 | 0 |
| 033-core-ISS46-high-offline-next-H1-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-10T09:03:55.219512939Z | 16 | 16 | 0 |
| 034-core-ISS46-high-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.05 | 2026-10-10T09:58:18.545013428Z | 17 | 17 | 0 |
| 051-core-NOAA38-high-offline-next-H1-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-25T06:13:48.108612061Z | 12 | 12 | 0 |
| 052-core-NOAA38-high-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-25T06:10:28.108612061Z | 12 | 12 | 0 |
| 064-core-HST48-medium-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-25T20:14:54.642608643Z | 41 | 40 | 1 |
| 069-core-HST48-high-offline-next-H1-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-25T07:59:43.216949463Z | 16 | 16 | 0 |
| 070-core-HST48-high-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-25T07:54:17.216949463Z | 16 | 15 | 0 |
| 081-measured-ISS46-medium-measured-multi-H2-canonical | PROCESS_TIMEOUT | 600.05 | 2026-10-10T20:59:29.107617879Z | 39 | 38 | 0 |
| 085-measured-ISS46-high-measured-next-H1-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-10T09:59:00.778015528Z | 17 | 17 | 0 |
| 086-measured-ISS46-high-measured-multi-H2-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-10T10:17:42.928993432Z | 19 | 19 | 0 |
| 089-sensitivity-ISS46-medium-install0.5-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.05 | 2026-10-10T20:59:22.699615479Z | 39 | 38 | 0 |
| 091-sensitivity-ISS46-medium-install2-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.05 | 2026-10-10T20:37:15.211395264Z | 38 | 37 | 0 |
| 093-sensitivity-ISS46-medium-cp2700-offline-multi-H2-canonical | PROCESS_TIMEOUT | 600.05 | 2026-10-10T20:59:56.699615479Z | 39 | 39 | 0 |
| 096-sensitivity-ISS46-medium-offline-multi-H3-canonical | PROCESS_TIMEOUT | 600.03 | 2026-10-10T20:37:42.211395264Z | 38 | 38 | 0 |

## Regression, measured delay, sensitivity and security

| Case | Status | Five components | Host controller s | Search nodes | Deadline misses |
|---|---|---|---:|---:|---:|
| 001-regression-X5-offline-next-H1-canonical | FINISHED | 2/38/0.12/160/0 | 2.34 | 3738 | 0 |
| 002-regression-X5-offline-multi-H2-canonical | FINISHED | 2/38/0.12/160/0 | 19.63 | 340824 | 0 |
| 003-regression-X5-offline-next-H1-continue_wait | FINISHED | 2/38/0.12/160/0 | 2.36 | 3738 | 0 |
| 004-regression-X5-offline-multi-H2-continue_wait | FINISHED | 2/38/0.12/160/0 | 21.2 | 340824 | 0 |
| 005-regression-Y-offline-next-H1-canonical | FINISHED | 3/42/0.18/240/0 | 2.78 | 4215 | 0 |
| 006-regression-Y-offline-multi-H2-canonical | FINISHED | 3/42/0.18/240/0 | 2.98 | 4932 | 0 |
| 007-regression-Y-offline-next-H1-continue_wait | FINISHED | 3/42/0.18/240/0 | 2.84 | 4215 | 0 |
| 008-regression-Y-offline-multi-H2-continue_wait | FINISHED | 3/42/0.18/240/0 | 2.8 | 4932 | 0 |
| 009-regression-demand-none-offline-next-H1-canonical | FINISHED | 3/410/2.13/3240/6 | 2.94 | 7878 | 0 |
| 010-regression-demand-none-offline-multi-H2-canonical | FINISHED | 3/410/2.13/3240/6 | 17.9 | 241778 | 0 |
| 011-regression-demand-none-offline-next-H1-continue_wait | FINISHED | 3/410/2.13/3240/6 | 2.14 | 3390 | 0 |
| 012-regression-demand-none-offline-multi-H2-continue_wait | FINISHED | 3/410/2.13/3240/6 | 5.75 | 41546 | 0 |
| 013-regression-X5-measured-next-H1-canonical | FINISHED | 2/33.75/0.12/160/0 | 5.61 | 43379 | 0 |
| 014-regression-X5-measured-multi-H2-canonical | FINISHED | 2/27.5/0.12/160/0 | 12.12 | 177633 | 0 |
| 015-regression-Y-measured-next-H1-canonical | FINISHED | 3/37/0.18/240/0 | 6.4 | 47574 | 0 |
| 016-regression-Y-measured-multi-H2-canonical | FINISHED | 3/37/0.18/240/0 | 6.63 | 52701 | 0 |
| 017-regression-demand-none-measured-next-H1-canonical | FINISHED | 3/410/2.13/3240/6 | 3.98 | 15845 | 0 |
| 018-regression-demand-none-measured-multi-H2-canonical | FINISHED | 3/410/2.13/3240/6 | 7.33 | 61214 | 0 |
| 073-measured-ISS46-low-measured-fixedperiod-H1-canonical | FINISHED | 46/920/35.66/49680/122 | 9.38 | 0 | 0 |
| 074-measured-ISS46-low-measured-percontact-H1-canonical | FINISHED | 46/920/32.66/49680/92 | 8.55 | 0 | 0 |
| 075-measured-ISS46-low-measured-next-H1-canonical | FINISHED | 46/920/32.66/49680/92 | 245.48 | 1089372 | 0 |
| 076-measured-ISS46-low-measured-multi-H2-canonical | FINISHED | 46/920/32.66/49680/92 | 544 | 2524221 | 0 |
| 077-measured-ISS46-low-measured-seed-h1-H1-canonical | FINISHED | 46/920/32.66/49680/92 | 17.91 | 0 | 0 |
| 078-measured-ISS46-medium-measured-fixedperiod-H1-canonical | FINISHED | 46/2740/35.66/49680/122 | 12.44 | 0 | 0 |
| 079-measured-ISS46-medium-measured-percontact-H1-canonical | FINISHED | 46/2740/32.66/49680/92 | 11.22 | 0 | 0 |
| 080-measured-ISS46-medium-measured-next-H1-canonical | FINISHED | 46/2720/32.66/49680/92 | 517.06 | 2481749 | 0 |
| 081-measured-ISS46-medium-measured-multi-H2-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 082-measured-ISS46-medium-measured-seed-h1-H1-canonical | FINISHED | 46/2740/32.66/49680/92 | 28.35 | 0 | 0 |
| 083-measured-ISS46-high-measured-fixedperiod-H1-canonical | FINISHED | 46/14390/35.66/49680/122 | 36.29 | 0 | 0 |
| 084-measured-ISS46-high-measured-percontact-H1-canonical | FINISHED | 46/14390/32.66/49680/92 | 34.64 | 0 | 0 |
| 085-measured-ISS46-high-measured-next-H1-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 086-measured-ISS46-high-measured-multi-H2-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 087-measured-ISS46-high-measured-seed-h1-H1-canonical | FINISHED | 46/14290/32.66/49680/92 | 132.28 | 0 | 0 |
| 088-sensitivity-ISS46-medium-install0.5-offline-next-H1-canonical | FINISHED | 46/2740/25.76/49680/92 | 514.33 | 2330193 | 0 |
| 089-sensitivity-ISS46-medium-install0.5-offline-multi-H2-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 090-sensitivity-ISS46-medium-install2-offline-next-H1-canonical | FINISHED | 46/2730/46.46/49680/92 | 509.22 | 2310270 | 0 |
| 091-sensitivity-ISS46-medium-install2-offline-multi-H2-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 092-sensitivity-ISS46-medium-cp2700-offline-next-H1-canonical | FINISHED | 46/2740/32.66/49680/92 | 506.22 | 2410550 | 0 |
| 093-sensitivity-ISS46-medium-cp2700-offline-multi-H2-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 094-sensitivity-ISS46-medium-cp10800-offline-next-H1-canonical | FINISHED | 46/2740/22.91/34680/62 | 266.51 | 1571855 | 0 |
| 095-sensitivity-ISS46-medium-cp10800-offline-multi-H2-canonical | FINISHED | 46/2740/22.91/34680/62 | 462.1 | 2493989 | 0 |
| 096-sensitivity-ISS46-medium-offline-multi-H3-canonical | PROCESS_TIMEOUT | PROCESS_TIMEOUT | NA | NA | NA |
| 097-security-X-undetected-offline-next-H1-canonical | FINISHED | 2/38/0.12/160/0 | 3.07 | 3738 | 0 |
| 098-security-X-undetected-offline-multi-H2-canonical | FINISHED | 2/38/0.12/160/0 | 24.03 | 340680 | 0 |
| 099-security-X-detected2-offline-next-H1-canonical | FINISHED | 3/33.5/0.93/1416/2 | 3.35 | 6210 | 0 |
| 100-security-X-detected2-offline-multi-H2-canonical | FINISHED | 3/33.5/0.93/1416/2 | 16.31 | 195794 | 0 |
| 101-security-X-gap-offline-next-H1-canonical | FINISHED | 2/38/0.66/1056/2 | 2.73 | 3764 | 0 |
| 102-security-X-gap-offline-multi-H2-canonical | FINISHED | 2/38/0.66/1056/2 | 22.45 | 340293 | 0 |
| 103-security-X-channel-temporary-offline-next-H1-canonical | FINISHED | 3/32/0.89/1320/2 | 3.62 | 8405 | 0 |
| 104-security-X-channel-temporary-offline-multi-H2-canonical | FINISHED | 3/32/0.89/1320/2 | 10.34 | 99999 | 0 |
| 105-security-demand-late-detected-offline-next-H1-canonical | FINISHED | 3/400/2.88/4416/8 | 3.35 | 8054 | 0 |
| 106-security-demand-late-detected-offline-multi-H2-canonical | FINISHED | 3/410/2.88/4416/8 | 18.71 | 230766 | 0 |
| 107-management-ISS3-low-management-interruption-offline-next-H1-canonical | FINISHED | 3/60/2.23/3600/6 | 3.9 | 18224 | 0 |
| 108-management-ISS3-low-management-interruption-offline-multi-H2-canonical | FINISHED | 3/60/2.23/3600/6 | 9.14 | 77606 | 0 |

## Disclosure and recovery stages

Times originate at GS notice. A valid independent replacement pair excludes disclosed material, respects its original material-age/activation deadlines and counter constraints, and is confirmed at both endpoints. SA readiness additionally requires an actually open admissible session. Frame recovery requires an actual delivered independent complete frame. Already-ready states, censoring, unavailable origins and no-target events remain explicit. Readiness intervals and overlapping blocking conditions are retained in audit.json; first readiness does not imply continuous availability.

Only FINISHED executions have final recovery milestones here. For other statuses, observed events remain prefix evidence in audit.json; missing future events are not presented as final censoring.

| Case | Execution status | Incident/recovery state | SC notice | Independent keys | Independent SA | First independent frame |
|---|---|---|---|---|---|---|
| 097-security-X-undetected-offline-next-H1-canonical | FINISHED | UNDETECTED | not-applicable:NA | not-applicable:NA | not-applicable:NA | not-applicable:NA |
| 098-security-X-undetected-offline-multi-H2-canonical | FINISHED | UNDETECTED | not-applicable:NA | not-applicable:NA | not-applicable:NA | not-applicable:NA |
| 099-security-X-detected2-offline-next-H1-canonical | FINISHED | RECOVERED | event:0.4 | event:7.4 | event:8.4 | event:8.65 |
| 100-security-X-detected2-offline-multi-H2-canonical | FINISHED | RECOVERED | event:0.4 | event:7.4 | event:8.4 | event:8.65 |
| 101-security-X-gap-offline-next-H1-canonical | FINISHED | CENSORED_NO_RECOVERY | event:18.4 | censored:NA | censored:NA | censored:NA |
| 102-security-X-gap-offline-multi-H2-canonical | FINISHED | CENSORED_NO_RECOVERY | event:18.4 | censored:NA | censored:NA | censored:NA |
| 103-security-X-channel-temporary-offline-next-H1-canonical | FINISHED | NO_ATTACK | not-applicable:NA | not-applicable:NA | not-applicable:NA | not-applicable:NA |
| 104-security-X-channel-temporary-offline-multi-H2-canonical | FINISHED | NO_ATTACK | not-applicable:NA | not-applicable:NA | not-applicable:NA | not-applicable:NA |
| 105-security-demand-late-detected-offline-next-H1-canonical | FINISHED | RECOVERED | event:0.4 | event:7.4 | event:8.4 | event:18.4 |
| 106-security-demand-late-detected-offline-multi-H2-canonical | FINISHED | RECOVERED | event:3.98 | event:4.98 | event:5.98 | event:15.98 |

## Delivered traffic and emitted exposure

UL/DL delivered bytes and frames count only complete delivered frames. Confidential delivery is retrospective through experiment end. Decryptable emitted bytes include recorded traffic before disclosure and emitted traffic afterward, including aborted prefixes. Exposure and confidential delivery therefore do not partition the same delivered total. I means unavailable final totals for an unfinished execution.

| Case | Status | Delivered UL/DL B | Complete UL/DL frames | Confidential UL/DL B | Decryptable emitted before/after B |
|---|---|---:|---:|---:|---:|
| 097-security-X-undetected-offline-next-H1-canonical | FINISHED | 7600/7600 | 76/76 | 3800/3800 | 2800/4800 |
| 098-security-X-undetected-offline-multi-H2-canonical | FINISHED | 7600/7600 | 76/76 | 3800/3800 | 2800/4800 |
| 099-security-X-detected2-offline-next-H1-canonical | FINISHED | 6700/6700 | 67/67 | 4900/4900 | 2800/800 |
| 100-security-X-detected2-offline-multi-H2-canonical | FINISHED | 6700/6700 | 67/67 | 4900/4900 | 2800/800 |
| 101-security-X-gap-offline-next-H1-canonical | FINISHED | 7600/7600 | 76/76 | 3800/3800 | 7600/0 |
| 102-security-X-gap-offline-multi-H2-canonical | FINISHED | 7600/7600 | 76/76 | 3800/3800 | 7600/0 |
| 103-security-X-channel-temporary-offline-next-H1-canonical | FINISHED | 6400/6400 | 64/64 | 6400/6400 | 0/0 |
| 104-security-X-channel-temporary-offline-multi-H2-canonical | FINISHED | 6400/6400 | 64/64 | 6400/6400 | 0/0 |
| 105-security-demand-late-detected-offline-next-H1-canonical | FINISHED | 20000/20000 | 20/20 | 3000/3000 | 33842/200 |
| 106-security-demand-late-detected-offline-multi-H2-canonical | FINISHED | 21000/20000 | 21/20 | 4000/3000 | 33842/158 |
| 107-management-ISS3-low-management-interruption-offline-next-H1-canonical | FINISHED | 3000/3000 | 3/3 | 3000/3000 | 0/0 |
| 108-management-ISS3-low-management-interruption-offline-multi-H2-canonical | FINISHED | 3000/3000 | 3/3 | 3000/3000 | 0/0 |

## Management interruption

- `107-management-ISS3-low-management-interruption-offline-next-H1-canonical`: FINISHED; interrupted={"INSTALL": 1}; aborted frames=0; partial CPU=0.1 s, partial management=360 B; five=3/60/2.23/3600/6.
- `108-management-ISS3-low-management-interruption-offline-multi-H2-canonical`: FINISHED; interrupted={"INSTALL": 1}; aborted frames=0; partial CPU=0.1 s, partial management=360 B; five=3/60/2.23/3600/6.

The loss interval is fixed in the registration. It is not shifted to force a particular operation to fail. Interrupted INSTALL may be retried under existing rules; uncertain SWITCH/OPEN/REUSE remains fail-closed without reconciliation. No safety claim beyond the verified model and interventions follows.

## Evidence and limits

- [Protocol](protocol.md), [all runs](runs.csv), [matched horizons](pairs.csv), [factor deltas](factor-comparisons.csv).
- [Frozen workload descriptors](workload-descriptors.md) and [timing measurement boundaries](timing-definitions.md).
- [Regression diagnosis](regression-diagnosis.md) separates prepared exact reference from the autonomous bounded campaign.
- [Reproduction](reproduction.md), [reference verification](../paper-final/reference-verification.md).
- No outcomes were used to select new model parameters or increase budgets. No unfinished comparison is called a policy loss.
- Historical SE2/R1/PC2 files and archives retain their identities; the final suite is not added to overlapping historical test counts.
