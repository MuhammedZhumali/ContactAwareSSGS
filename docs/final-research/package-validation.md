# Archive closure and restored command validation

Status: **PASS**. This note is intentionally outside the immutable archive because the check
runs after archive closure. Download the archive together with this note or the two JSON sidecars.

- Archive: `target/final-research/final-reproduction.zip`.
- Size: 505093548 bytes; 2372 members.
- SHA-256: `a5b368a995b957982763b71e5d4f41ef251859187b93be9a3d3b788c4c54f46f`.
- ZIP CRC check passed. The entire archive was restored into `target/final-restored`.
- All 2371 payload members matched their SHA-256 values in the embedded `reproduction-files.json`;
  that index is the remaining member. No duplicate or escaping path was accepted.
- `scripts/replay_final_case.py --case 001-regression-X5-offline-next-H1-canonical --out target/final-replays/X5-next-01`
  ran from the restored root with Python3.11+ and Java21, original 5000-node/search/decision/watchdog limits.
- Result: FINISHED, process3.578s. Full simulation metrics and the entire physical event journal
  match registered case001; restored checkpoint journal matches the export.
- This is one separate command-validation replay, not a109th campaign attempt, not a replacement
  result and not proof of repeatable host timing or every time-limited trajectory.
- No new full Maven rebuild was performed after restoration. The full isolated final-v2 clean
  build before the campaign passed478tests/64classes with identical frozen source; the archive
  includes those build records and the exact tested JAR. The rebuild helper's command is documented,
  with external Maven/Python dependencies explicit.

Machine evidence: `target/final-research/package-manifest.json`,
`package-replay-validation.json`, `package-replay-command.log`; extraction/member check log:
`target/final-research-restore-check.log`. Administrative ledger closure postdates packaging;
scientific source, inputs, outcomes, paper, figures and reports were not changed by this validation.

The previous11943files and181frozenJava/build/wrapper sources were hash-verified again before
archive creation. All prior SE2/R1/PC2 results and archives retain their original identities.
