# PC1: practical controller on a fixed full schedule

Active objective: separate experiment process limits from per-decision deadlines; diagnose saved bounded-Multi losses; obtain complete matched Next/Multi runs over the unchanged 46-contact ISS schedule. Measured planning-delay execution and recovery after interrupted management operations follow this baseline. No security constraint, objective component or tie-break is relaxed.

## Work ownership and state

| Work | Owner | State / dependency |
| --- | --- | --- |
| R1 evidence preservation, isolated build, full ISS protocol and serial execution | Main | Complete: four offline executions FINISHED; 46 chronological contacts processed in each |
| Saved decision diagnosis | `practical_decision_diagnosis` | Complete; no JVM; see `decision-diagnosis.md` |
| Cooperative decision scope and deterministic deadline tests | `decision_deadline`, integrated by main | Complete; ten deterministic cases pass in the 51-test build |
| Same-root replay of retained versus newly found continuation | Main | Complete: both feasible, retained suffix saves 0.71 CPU s / 1080 B / two keys at the same root |
| Measured latency and interrupted management recovery | Main | Complete checks: four measured-delay executions FINISHED; 92 focused tests pass; INSTALL recovers, uncertain SWITCH/OPEN/REUSE remain blocked |

## Budgets and comparison

R1 had a 20-second cumulative controller limit in addition to the 5000-node/1-second search limit. Stopping an entire simulated day at 20 seconds was an experiment limit, not an individual decision miss. PC1 keeps the search cap, adds a separate two-second cooperative deadline around each whole observation/candidate/selection/reuse preparation, and uses one fixed 600-second child-process watchdog. No cumulative controller wall cap is imposed within that experiment. These limits are registered before any full ISS attempt and are identical for H1/H2 and both tie-breaks. They are not raised automatically.

On a local decision deadline, no proposed new payload or security action executes: the controller keeps the last immutable state and waits to the earliest permitted observed wakeup. Already committed work and mandatory incident response remain executor responsibilities. Re-observation needed to produce this safe response runs outside the expired decision scope and is separately timed; the external watchdog still bounds the process. This is cooperative cancellation, not a hard real-time guarantee against OS pauses/native blocking.

The first series remains an offline/zero-model-time reference. Measured host decision deadlines do not by themselves advance spacecraft time. The separate measured-delay series uses host-scale=1, fixed=0 and a two-second result-availability deadline. It advances the physical timeline at the critical decision points and revalidates actions. None of the eight executions missed the registered deadline. Two seconds is an experimental choice, not a calibrated mission requirement.

Both rules now have complete H1/H2 pairs in both modes. Offline service is 40/1 contacts for CANONICAL Next/Multi and 1/1 for CONTINUE_WAIT. With measured host delay it is 30/1 and 1/1. Finishing the schedule exposed serious bounded-search quality loss; it does not validate this controller for deployment. Full metrics, diagnostic evidence and limits are in [report.md](report.md).

Commands, once the corresponding stage is ready:

```powershell
python -X utf8 scripts/run_practical_controller.py build
python -X utf8 scripts/run_practical_controller.py register
python -X utf8 scripts/run_practical_controller.py run
python -X utf8 scripts/run_practical_controller.py analyze
python -X utf8 scripts/run_practical_controller.py build_latency
python -X utf8 scripts/run_practical_controller.py register_latency
python -X utf8 scripts/run_practical_controller.py run_latency
python -X utf8 scripts/run_practical_controller.py analyze_latency
python -X utf8 scripts/write_practical_controller_report.py
python -X utf8 scripts/run_practical_controller.py verify
```

Registration/build/run directories refuse overwrite. Existing R1 archives, JARs, protocols and figures remain unchanged. A new source manifest and JAR hash identify this version. An incomplete physical execution receives no final quality score.

The saved commands above describe stage creation, not an in-place rerun of existing evidence. To replay one case, use its `invocation.json` with the matching frozen JAR and change only `--out` to a new empty directory; retain the separately recorded process watchdog. Source archives `deadline-sources.zip` and `latency-verified-sources.zip` identify the tested variants. Java21 is required; the source build also needs the R1 fixture data and Maven dependencies described in [R1 reproduction](../research-audit/reproduction.md). No new commit was created; this is an identified dirty-source continuation.
