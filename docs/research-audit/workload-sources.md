# Preregistered public-orbit workload sources

Acquisition checkpoint: **two additional complete public GP snapshots acquired before propagation or policy runs**. Subsequent geometry and continuous execution outcomes are recorded separately in [the R1 results](../../RESEARCH_VALIDATION_RESULTS.md); this file preserves the source-selection evidence.
Protocol was written before either network request at `2026-10-24T20:07:26Z`:
[`protocol.json`](../../target/research-validation/orbit-sources/protocol.json), SHA-256
`58ea9daa54054f36da221fd87491aa734271d14ba38e53b71b24b6281ff814f0`.

The orbit identities were fixed before acquisition: existing ISS 25544, NOAA-19 33591
(intended sun-synchronous LEO contrast), and Hubble/HST 20580 (lower-inclination LEO contrast).
No planner outcome, contact count or successful result was used to replace an orbit.
The GP snapshots confirm the inclinations below; this acquisition step does not independently
validate long-term sun-synchronous precession or generate contact windows.

| Orbit | Exact GP epoch, UTC | Inclination | Mean motion, rev/day | Saved source |
|---|---|---:|---:|---|
| NOAA 19, 33591 | 2026-10-24 05:39:23.871744 | 98.9398° | 14.13490666 | [full GP JSON](../../target/research-validation/orbit-sources/33591/gp.json) |
| HST, 20580 | 2026-10-24 07:20:21.531264 | 28.4737° | 15.32067126 | [full GP JSON](../../target/research-validation/orbit-sources/20580/gp.json) |

Primary endpoints: [CelesTrak NOAA-19 GP](https://celestrak.org/NORAD/elements/gp.php?CATNR=33591&FORMAT=JSON)
and [CelesTrak HST GP](https://celestrak.org/NORAD/elements/gp.php?CATNR=20580&FORMAT=JSON).
The accepted files preserve the full original HTTP response bytes, including every returned GP field.

- NOAA-19: 415 bytes, SHA-256 `5ddc16975bcb9f79a572d0760c9258dd95ae07908541f62bf1cd9de6c09cb1dc`.
- HST: 416 bytes, SHA-256 `0266ccdb04d26e0d02eac17ce471f3581cca2ca92e095b6e313a0ac41505ec8b`.

Each folder includes `gp.json.source.json` with URL, host UTC retrieval timestamp, HTTP headers,
SHA-256, record identity/epoch, chosen interval and protocol hash. The server Date header is
retained separately from the host retrieval clock; they differ by about one minute.
Web retrieval reported inaccessible URLs. The sandbox direct attempt failed DNS with
`[Errno 11001] getaddrinfo failed`. One approved retry batch succeeded with HTTP 200 for both.
Those failures remain in `web-attempts.json` and each `attempt-sandbox.json`; success metadata
is also retained in `attempt-approved-retry.json`. No additional endpoints or replacements were needed.

The preregistered interval is the 24-hour UTC day immediately after each GP epoch's calendar day.
Both new snapshots therefore select **2026-10-25 00:00:00Z to 2026-10-26 00:00:00Z**.
Endpoint distances from the NOAA-19 epoch are 18.34336896–42.34336896 h; for HST they are
16.66068576–40.66068576 h. Every endpoint is below the 72-hour limit. The snapshots were
about 14.49 and 12.81 hours old at acquisition. A later run must use these fixed intervals;
it must not roll the date forward while silently retaining the same GP snapshot.

## Common geometry input and existing ISS schedules

The frozen station file is
[`data/runs/iss-20261010/orekit/raw/stations.json`](../../data/runs/iss-20261010/orekit/raw/stations.json):
32 stations, SHA-256 `921201a236337afa86d98da592e0a42e20eb0c043978e153531139a17aff44e9`.
This pass read the file and verified its count/hash and equality with the copies underlying both
new-day datasets. The future geometry settings are elevation mask 10°, switch guard 0 s,
check interval 30 s, root tolerance 0.001 s, and maximum epoch distance 72 h.
Station use is **hypothetical research authorization** for public coordinates, not a claim of
actual station availability, satellite compatibility, link budget or permission to communicate.

All three already saved ISS schedules use one exact GP epoch,
`2026-10-10T18:56:31.282080Z`, and the same source hash
`9d78055cff2573f4d18b1f367050542595cb8f714a4de57e118bc42bd9f56a73`.
They use mask 10° and switch guard 0 s. Source data and schedules were only read, not regenerated.

| Saved schedule | UTC interval | Candidate / selected windows | Signed endpoint distance from epoch |
|---|---|---:|---|
| [original 46](../../data/runs/iss-20261010/schedule/schedule.json) | Oct 10–11, 2026 | 146 / 46 | −18.9420228 to +5.0579772 h |
| [day1 44](../../target/security-evaluation-new-days/day1/schedule/schedule.json) | Oct 11–12, 2026 | 144 / 44 | +5.0579772 to +29.0579772 h |
| [day2 45](../../target/security-evaluation-new-days/day2/schedule/schedule.json) | Oct 12–13, 2026 | 141 / 45 | +29.0579772 to +53.0579772 h |

Schedule SHA-256 values, exact source paths, station references, intervals and settings are in
[`inventory.json`](../../target/research-validation/orbit-sources/inventory.json).
All three intervals pass their existing absolute 72-hour epoch-distance limit. The original 46
includes backward propagation before the GP epoch. These are three dates of one ISS element set,
not three independent orbits. The old ISS snapshot is unsuitable for Oct 25–26 propagation;
a contemporaneous three-orbit comparison would require a separately frozen fresh ISS source.

The existing frozen EOP dataset declares coverage through 2027-10-04 and may include predictions;
it and the frozen leap-second file are available under `data/runs/iss-20261010/orekit/raw/earth/`.
No fresh EOP acquisition, propagation accuracy assessment, station compatibility audit or numerical
convergence run was performed here. A source snapshot and valid epoch window establish reproducible
input availability, not measured mission contact accuracy or successful policy execution.

Validation performed: `inventory.py` reread both new GP snapshots, verified metadata hashes and
protocol linkage, checked endpoint ages, verified the station count/hash, and enumerated the three
existing schedules with their source epochs. Result: `SOURCE_ACQUISITION_COMPLETE_PROPAGATION_NOT_RUN`.
No JVM, Maven, contact generation or security-policy experiment was started in this subtask.
