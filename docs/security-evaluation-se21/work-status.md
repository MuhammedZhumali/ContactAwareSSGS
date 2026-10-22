# SE2.1 ownership

| Work | Owner | Dependency | State |
|---|---|---|---|
| Recovery milestones, intervals, postprocessing tests | se21_metrics | Frozen SE2 journals | Done; 12 focused Python tests |
| Search checks, cancellation, CPU/GC/phase diagnostics, focused tests | se21_search | Existing exact solver | Done; 16 cancellation test invocations |
| Controller deadline, replay/checkpoint codec, subprocess watchdog | root | Shared SearchControl API | Done; 4 Java and 2 Python tests |
| Fixed repeat protocol, integration, Maven, repeats, corrected exposure figure, report/package | root | Above patches | Done; 344 Maven tests, 30 retained attempts |

Only two subagents; no delegated child agents. Shared builds are run by root.
SE2 source artifacts, journals, JAR and ZIPs are immutable inputs.
