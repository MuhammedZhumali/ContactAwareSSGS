# Article update proposal retained as review history

The following proposal was prepared before approval. The user subsequently
approved editing, and the actual changes are documented in
`../article-x01-update/README.md`. The proposal below is retained as review history.

**Experimental design.** We repeated the autonomous X01 scenario using the archived physical model and150000nodes per search. Four offline controls and four measured-delay pilots preceded80registered main attempts:20Next/Multi pairs for each of two tie-breaking rules. Fresh Java21 processes ran serially without explicit warmup, alternating which horizon ran first. The measured decision pipeline was charged before fresh intent admission; other host overhead was reported separately.

**Results.** The main series completed 80/80 executions. CONTINUE_WAIT: 20/20 complete pairs, with 0 service wins, 0 ties and 20 losses for Multi; CANONICAL: 20/20 complete pairs, with 0 service wins, 5 ties and 15 losses for Multi. Unfinished executions were not assigned final service scores.

**Discussion.** The offline horizon advantage describes a feasible and discoverable continuation under the tested budget; execution with measured decision latency can consume the very contact capacity needed to realize it. The observation is specific to X01 and the tested desktop/JVM timing boundary.

**Conclusion.** Report the observed paired outcomes above alongside the preserved offline gain. Do not generalize to all hardware, contact schedules or tie rules.

**Abstract.** Revise last, after approval, to include the registered main-series denominator and both offline and measured outcomes. Do not replace the current abstract automatically.
