# Frozen core workload descriptors

These are input-only descriptive calculations for the nine registered core models. No campaign results or checkpoints were read, and no JVM, simulation, new policy run or parameter adjustment was performed. Model hashes were checked against `target/final-research/protocol.json`. Every timestamp was converted to integer nanoseconds; all displayed seconds and medians use exact Decimal arithmetic. The JSON saves each window, recurrence, qualifying triple and payload calculation, with Decimal values represented as exact strings.

## Contact geometry and existing state

| Mission | Contacts | Initial contexts / key records | Minimum s | Median s | Maximum s | Total selected visible s |
| --- | --- | --- | --- | --- | --- | --- |
| ISS46 | 46 | 17 / 0 | 58.977355957 | 359.2410278325 | 409.598693848 | 14856.829833983 |
| NOAA38 | 38 | 16 / 0 | 122.687988281 | 584.418182373 | 668.019104004 | 19613.58215332 |
| HST48 | 48 | 13 / 0 | 74.815063477 | 391.193847656 | 451.794433594 | 16111.708374019 |

Total visible time sums the selected nonoverlapping contact lengths; it is not secure service or radio utilization. All three inputs start with zero key records. A repeated station identifier therefore does not imply preinstalled or still-valid material. Station equality uses the existing `(satelliteId, groundStationId)` context identity, checked against initial-state contexts.

## Same-station recurrences

A recurrence gap is the next occurrence's start minus the previous occurrence's end for the same context; other stations may lie between them. First occurrences have no recurrence gap and are excluded from gap medians. A consecutive A-B-A triple requires three adjacent selected contacts, equal first/third contexts and a distinct middle context. These are chronological descriptors, not selected successful handovers.

| Mission | Recurrences | First occurrences | Gap minimum s | Gap median s | Gap maximum s | Consecutive A-B-A triples |
| --- | --- | --- | --- | --- | --- | --- |
| ISS46 | 29 | 17 | 5453.85772705 | 5556.057128906 | 53949.838256836 | 1 |
| NOAA38 | 22 | 16 | 5429.92767334 | 19774.225158692 | 44305.838928222 | 5 |
| HST48 | 35 | 13 | 5528.383483887 | 5603.688354493 | 60185.625915528 | 3 |

The base cryptoperiod (`scenario.constraints.cryptoperiod`) was read and verified as exactly 5400 s in all nine core models, including every load. The following comparisons use integer nanoseconds and strict `gap > CP`; equality is counted separately.

| Mission | Base CP s | Recurrence gaps > CP | Gaps = CP | Gaps < CP |
| --- | --- | --- | --- | --- |
| ISS46 | 5400 | 29 | 0 | 0 |
| NOAA38 | 5400 | 22 | 0 | 0 |
| HST48 | 5400 | 35 | 0 | 0 |

Conditional deduction: let `e` be the previous same-station contact end, `s` the next start, and `a` a key's activation time. For a key already activated by `e`, `a <= e`. Under the lifecycle deadline bound `deadline <= a + CP`, any recurrence with `s - e > CP` gives `deadline <= a + CP <= e + CP < s`. That activated key therefore cannot be reused at the next occurrence under the base CP. This applies to all listed recurrences. It does not exclude unactivated staged candidates, whose later activation may still be possible subject to the remaining lifecycle and resource constraints. The CP=10800 sensitivity is outside this deduction's verified input scope. Neither this exclusion nor the duration counts establish that extra horizon is generally useless.

## Offered demand and conditional timing counts

The frozen base costs were summed from their phases and checked separately for every mission: INSTALL=4 s, SWITCH=1 s and OPEN=1 s. Each model uses 100 B/s, 1000 B complete frames and a 1000 B minimum in each direction. Two required frames therefore occupy 20 s. The following counts test only window duration against 20 s, an installed-entry serial chain SWITCH+OPEN+payload=22 s, or a prepared-entry serial chain INSTALL+SWITCH+OPEN+payload=26 s. Equality fits; no security state is inferred from that arithmetic.

| Mission | Load | Offered UL B | Offered DL B | Offered total B | L >= 20 s | L >= 22 s | L >= 26 s |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ISS46 | low | 46000 | 46000 | 92000 | 46 | 46 | 46 |
| ISS46 | medium | 138000 | 138000 | 276000 | 46 | 46 | 46 |
| ISS46 | high | 1458000 | 1458000 | 2916000 | 46 | 46 | 46 |
| NOAA38 | low | 38000 | 38000 | 76000 | 38 | 38 | 38 |
| NOAA38 | medium | 114000 | 114000 | 228000 | 38 | 38 | 38 |
| NOAA38 | high | 1941000 | 1941000 | 3882000 | 38 | 38 | 38 |
| HST48 | low | 48000 | 48000 | 96000 | 48 | 48 | 48 |
| HST48 | medium | 144000 | 144000 | 288000 | 48 | 48 | 48 |
| HST48 | high | 1590000 | 1590000 | 3180000 | 48 | 48 | 48 |

Low offers 1000 B/direction, medium 3000, and high `max(3, floor(window duration / 10 s))*1000` per direction. The service minimum is identical at every load, so timing-fit counts are identical even though offered volume changes. Offered UL+DL is demand, not jointly deliverable capacity on the shared serial radio.

| Mission | Distinguishing windows: 22 <= L < 26 s | Of these, prior same context exists |
| --- | --- | --- |
| ISS46 | 0 | 0 |
| NOAA38 | 0 | 0 |
| HST48 | 0 | 0 |

The 22-to-26-second band describes windows where these two particular chains differ in their duration test. A matching earlier station is necessary only for the stated recurrence descriptor; it does not certify usable installed keys. These counts omit PREPARE's 2 s, valid-active-key OPEN/REUSE alternatives, key expiry/material-age/reuse-gap checks, authorization changes, pending work, resource conflicts, planning delays and channel interruption. They are neither achievable-service bounds nor optimum/service guarantees, and absence of a window in this band is not proof that extra horizon is useless. Wider horizons can change preparation timing, reuse, costs or execution even outside this narrow chain comparison. No attacks were added or inferred.

## Exact input identities

| Mission | Load | Model | SHA-256 |
| --- | --- | --- | --- |
| ISS46 | low | `target/final-research/models/ISS46-low.json` | `eacfa95e4b90c9416c38f8b16006481743e56ff8bcdfcab404b6bd50bab44a08` |
| ISS46 | medium | `target/final-research/models/ISS46-medium.json` | `528bc658aabf66e6d507d693cfa29ebe24e700cb6071b32948ca741412b39002` |
| ISS46 | high | `target/final-research/models/ISS46-high.json` | `db7d25505381fb37ddb145e1da919a42b071119faef4b35d5e817fa1f62983ef` |
| NOAA38 | low | `target/final-research/models/NOAA38-low.json` | `caa436df61c5e8f12f5ad1a6005396ec27473b643cffa60143d0298ce27b301e` |
| NOAA38 | medium | `target/final-research/models/NOAA38-medium.json` | `8eed33bf64b2cb607dd2e58cbb26274622ed418d45af558a16d66b5b29bcca63` |
| NOAA38 | high | `target/final-research/models/NOAA38-high.json` | `48402e3d8251cccc3f2521ba2953d7bd563c877ba73de635605fad126e9c2431` |
| HST48 | low | `target/final-research/models/HST48-low.json` | `a3d21ff70e5123e9bcf72c9ac28c4a8e2ccf01a8249b55136bfdb63245f91ff3` |
| HST48 | medium | `target/final-research/models/HST48-medium.json` | `2d16fde0c484edc78cc41f7a9f2e1ae18e47f24a9e6e59b439204965f22b6074` |
| HST48 | high | `target/final-research/models/HST48-high.json` | `7d4f00d87c0573b29bfaba4354788efa727095013e50abdaba5a85df8603948e` |

Machine calculations: `target/final-research/workload-descriptors.json`. Generator: `scripts/describe_final_inputs.py`. Reproduction command: `python -X utf8 scripts/describe_final_inputs.py`. This command reads only the registration and its nine core input models and writes only this report and its JSON companion.
