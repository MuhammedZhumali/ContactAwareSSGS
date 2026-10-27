# Stage 2: finite synthetic horizon conditions

Source-derived choices fixed before execution of this block; see the independent `methodology-review.md`. Nine configurations, each compared with H1/H2 and CANONICAL/CONTINUE_WAIT using exact search and the common prescribed PREPARE initialization. This is 36 logical cells. The four unchanged baseline cells are reused from Stage 1, with explicit origins and verified identical input/checkpoint/settings; 32 new attempts. Autonomous initialization is isolated in Stage 1, not silently pooled into this family. This block evaluates the mechanism conditional on common preparation, not practical solver effectiveness.

| ID | C3 start T (s) | C3 length (s) | CP (s) | Purpose |
| --- | ---: | ---: | ---: | --- |
| X01 | 80 | 5 | 150 | Original baseline |
| X02 | 80 | 2 | 150 | Insufficient for installed, prepared and active-entry chains |
| X03 | 80 | 9 | 150 | Sufficient duration for both specified installed/prepared chains |
| X04 | 60 | 5 | 150 | Return before original A deadline |
| X05 | 180 | 5 | 150 | Late return; early activation may expire, pre-active installation can preserve material |
| X06 | 80 | 5 | 180 | Original A material survives return |
| X07 | 80 | 5 | 90 | Short lifetime also causes expiry inside C1; not a C3-only perturbation |
| X08 | 180 | 5 | 240 | Prespecified lifetime/return crossing |
| X09 | 430 | 5 | 150 | All material created by C1 end exceeds its 400 s material-age bound |

C1=A[0,20], C2=B[30,50], all other costs/traffic/limits unchanged. Material age400, reuse gap200. Histories A(-90 creation,-80 activation,-60 last contact), B(-40,-30,-20) are reconstructed through LifecycleTransitions with each CP, not by editing notAfter independently. New deadlines arise only from actual executor transitions. The generator exports initial deadline audits and preserves all resulting scenarios.

Installed-entry=SWITCH1+OPEN1+eight frames of .25s=4s; prepared-entry adds INSTALL4=8s. An already-active pair needs REUSE1+payload2=3s, hence the insufficient control is 2s. These sums describe particular serial chains, not a universal scheduling theorem. Tests execute the actual chains, include whole frames, and distinguish strict online-operation completion before LOS from payload completion at LOS. Actual policy outcomes, expiry and the finite candidate-time model govern feasibility.

Budgets identical to Stage 1 exact: 2,000,000 nodes,30s/search,120s/controller,150s watchdog,1GiB heap; one JVM. COMPLETE is required for an optimal finite-root claim. A stopped full run remains incomplete and is not ranked. All final metrics include warmup once. Historical ISS/NOAA/HST geometry remains a separately labelled transferability reference; no real window is clipped or regenerated here.
