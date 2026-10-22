"""Run the preregistered ContactAwareSS grid with serial JVMs and immutable attempts.

First build the current Java 21 artifact with Maven, then run, for example:
  python scripts/run_experiment_grid.py --output target/grid-run --mode growth
  python scripts/run_experiment_grid.py --output target/grid-run --mode demand --resume

Modes are cumulative: growth, demand, lifecycle, horizon, all. Default: growth.
The immutable plan lists the entire 210-case grid, including unrequested cells.
--plan-only writes the plan/registry without launching Java. --resume requires
unchanged script/protocol/JAR/schedule hashes and never overwrites an attempt.

Budgets per decision: tier 1 = 100,000 nodes / 10 s; tier 2 = 2,000,000 / 60 s.
Only SEARCH_INCOMPLETE triggers a fresh tier-2 attempt. A second budget failure
blocks larger matching prefixes with NOT_RUN_AFTER_BUDGET_LIMIT. A process
timeout/failure is recorded separately and stops this invocation. Default wall
caps are 1800 s/attempt and 3600 s/invocation; these do not change solver budgets.

Artifacts: plan.json, atomically updated registry.json, and separate directories
for each attempt's command/environment, stdout/stderr, Java output and outcome.
Finished identical configurations are reused across resumed/cumulative stages.
Exit 0 means all requested cases FINISHED (or plan-only); 2 means incomplete or
unrun cases remain. No approximate incumbent is executed by this runner.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
MAIN = "org.contactaware.security.simulation.FourPolicyExperimentMain"
PREFIXES = (3, 6, 12, 24, 46)
WORKLOADS = ("light", "moderate", "saturated")
CRYPTOPERIODS = (5400, 7200)
MODES = {"growth": 1, "demand": 2, "lifecycle": 3, "horizon": 4, "all": 5}
TIERS = ((100_000, 10), (2_000_000, 60))


@dataclass(frozen=True)
class Case:
    contact_count: int
    traffic: str
    cryptoperiod: int
    policy: str
    fixed_period: int | None = None
    horizon: int | None = None

    @property
    def id(self):
        suffix = f"t{self.fixed_period}" if self.policy == "fixed" else "jit" if self.policy == "percontact" else f"h{self.horizon}"
        return f"n{self.contact_count:02d}-{self.traffic}-cp{self.cryptoperiod}-{self.policy}-{suffix}"

    @property
    def chain(self):
        return self.traffic, self.cryptoperiod, self.policy, self.fixed_period, self.horizon


def grid():
    """First-stage membership is fixed, independent of observed outcomes."""
    cases: dict[Case, int] = {}

    def add(stage, prefixes, workloads, periods, configurations):
        for prefix in prefixes:
            for workload in workloads:
                for period in periods:
                    for policy, fixed, horizon in configurations:
                        cases.setdefault(Case(prefix, workload, period, policy, fixed, horizon), stage)

    common = (("fixed", 3600, None), ("percontact", None, None), ("next", None, 1), ("multi", None, 2))
    extra_fixed = (("fixed", 1800, None), ("fixed", 7200, None))
    h3 = (("multi", None, 3),)
    add(1, PREFIXES, ("light",), (7200,), common)
    add(2, PREFIXES[:3], ("moderate", "saturated"), (7200,), common)
    add(3, PREFIXES[:3], WORKLOADS, CRYPTOPERIODS, common + extra_fixed)
    add(4, PREFIXES[:3], WORKLOADS, CRYPTOPERIODS, h3)
    add(5, PREFIXES, WORKLOADS, CRYPTOPERIODS, common + extra_fixed + h3)
    return sorted(cases.items(), key=lambda item: (item[1], item[0].contact_count, item[0].id))


def stamp():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_new(path, value):
    with path.open("x", encoding="utf-8") as handle:
        json.dump(value, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def save_registry(root, registry):
    temporary = root / "registry.json.tmp"
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(registry, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(root / "registry.json")


def read_json(path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def java_command(value):
    if value:
        resolved = shutil.which(value)
        return str(Path(resolved or value).resolve())
    java_home = os.environ.get("JAVA_HOME")
    executable = "java.exe" if os.name == "nt" else "java"
    if java_home and (Path(java_home) / "bin" / executable).is_file():
        return str((Path(java_home) / "bin" / executable).resolve())
    resolved = shutil.which("java")
    if not resolved:
        raise ValueError("Java 21 is required: set JAVA_HOME or --java")
    return str(Path(resolved).resolve())


def identity(args):
    java = java_command(args.java)
    if not Path(java).is_file():
        raise ValueError(f"Java executable not found: {java}")
    return {
        "scriptSha256": sha(Path(__file__)),
        "protocolSha256": sha(ROOT / "docs" / "experiment-grid.md"),
        "jar": str(args.jar), "jarSha256": sha(args.jar),
        "schedule": str(args.schedule), "scheduleSha256": sha(args.schedule),
        "java": java, "javaExecutableSha256": sha(Path(java)),
        "heapMegabytes": args.heap_mb,
        "host": {"system": platform.system(), "release": platform.release(), "machine": platform.machine(),
                 "processor": platform.processor(), "logicalCpus": os.cpu_count(), "python": platform.python_version()},
        "securityCostScale": 1, "perContactPreparation": "JUST_IN_TIME",
        "decisionBudgets": [{"tier": i + 1, "maxNodes": nodes, "maxSeconds": seconds}
                            for i, (nodes, seconds) in enumerate(TIERS)],
    }


def command_for(case, tier, output, run_identity):
    nodes, seconds = TIERS[tier - 1]
    command = [run_identity["java"], f"-Xmx{run_identity['heapMegabytes']}m", "-cp", run_identity["jar"], MAIN,
               "--scope", "schedule", "--contact-count", str(case.contact_count), "--traffic", case.traffic,
               "--cryptoperiod-seconds", str(case.cryptoperiod), "--policies", case.policy,
               "--per-contact", "just-in-time", "--cost-scale", "1", "--max-nodes", str(nodes),
               "--max-seconds", str(seconds), "--schedule", run_identity["schedule"], "--out", str(output)]
    if case.fixed_period is not None:
        command += ["--fixed-period-seconds", str(case.fixed_period)]
    if case.policy == "multi":
        command += ["--multi-horizon", str(case.horizon)]
    return command


def stop_process(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def exported_outcome(results):
    summary_file = results / "summary.json"
    if not summary_file.is_file():
        return {"status": "INVALID_OUTPUT", "reason": "Successful JVM exit without summary.json"}
    summary = read_json(summary_file)
    if len(summary) != 1:
        return {"status": "INVALID_OUTPUT", "reason": "Expected exactly one policy case per JVM"}
    name, row = next(iter(summary.items()))
    status = row.get("status")
    if status not in ("FINISHED", "SEARCH_INCOMPLETE", "NO_FEASIBLE_PLAN", "EXECUTION_STALLED"):
        return {"status": "INVALID_OUTPUT", "reason": f"Unknown actual status: {status}"}
    if (status == "FINISHED") != isinstance(row.get("finalMetrics"), dict):
        return {"status": "INVALID_OUTPUT", "reason": "Status and final metrics disagree"}
    decision_file = (results / row["compactDecisionsFile"]).resolve()
    if not decision_file.is_relative_to(results.resolve()):
        return {"status": "INVALID_OUTPUT", "reason": "Decision reference leaves attempt output"}
    decisions = read_json(decision_file)
    search_decisions = [d for d in decisions if d.get("source") == "SEARCH"]
    reasons = row.get("searchPerformance", {}).get("stopReasons", {})
    terminal_reason = search_decisions[-1].get("searchStopReason") if search_decisions else None
    if status == "SEARCH_INCOMPLETE" and terminal_reason not in ("NODE_LIMIT", "TIME_LIMIT"):
        return {"status": "INVALID_OUTPUT", "reason": "Incomplete search lacks an explicit node/time stop reason"}
    return {"status": status, "caseInSummary": name, "stoppedAt": row.get("stoppedAt"),
            "searchStopReasons": reasons, "terminalSearchStopReason": terminal_reason,
            "searchStatuses": row.get("searchStatuses"), "searchPerformance": row.get("searchPerformance"),
            "finalMetrics": row.get("finalMetrics"), "partialProgress": row.get("partialProgress"),
            "summarySha256": sha(summary_file), "decisionsSha256": sha(decision_file)}


def attempt(root, case, tier, run_identity, limit_seconds, timeout_status):
    directory = root / "attempts" / case.id / f"tier{tier}"
    directory.mkdir(parents=True, exist_ok=False)
    results = directory / "results"
    command = command_for(case, tier, results, run_identity)
    write_new(directory / "attempt.json", {"case": asdict(case), "tier": tier, "startedAt": stamp(),
              "command": command, "workingDirectory": str(ROOT), "wallTimeoutSeconds": limit_seconds,
              "wallTimeoutClassification": timeout_status, "identity": run_identity})
    started = time.monotonic()
    process = None
    outcome = {}
    try:
        with (directory / "stdout.log").open("x", encoding="utf-8") as stdout, (directory / "stderr.log").open("x", encoding="utf-8") as stderr:
            process = subprocess.Popen(command, cwd=ROOT, stdout=stdout, stderr=stderr, shell=False,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            write_new(directory / "process.json", {"pid": process.pid, "startedAt": stamp()})
            try:
                code = process.wait(timeout=limit_seconds)
            except subprocess.TimeoutExpired:
                stop_process(process)
                outcome = {"status": timeout_status, "reason": "External wall cap terminated JVM; not a solver search status"}
            else:
                if code == 0:
                    try:
                        outcome = exported_outcome(results)
                    except Exception as error:
                        outcome = {"status": "INVALID_OUTPUT", "reason": f"{type(error).__name__}: {error}"}
                else:
                    outcome = {"status": "PROCESS_FAILED", "exitCode": code,
                               "reason": "JVM exited unsuccessfully; inspect stderr.log"}
    except KeyboardInterrupt:
        if process is not None:
            stop_process(process)
        outcome = {"status": "INTERRUPTED", "reason": "Runner interrupted; JVM terminated"}
    except Exception as error:
        if process is not None:
            stop_process(process)
        outcome = {"status": "PROCESS_FAILED", "reason": f"{type(error).__name__}: {error}"}
    outcome.update(tier=tier, endedAt=stamp(), wallSeconds=time.monotonic() - started,
                   directory=directory.relative_to(root).as_posix())
    write_new(directory / "outcome.json", outcome)
    return outcome


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, required=True, help="New/empty run directory, or matching directory with --resume")
    parser.add_argument("--mode", choices=MODES, default="growth")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--jar", type=Path, default=ROOT / "target" / "contact-aware-contacts.jar")
    parser.add_argument("--schedule", type=Path, default=ROOT / "data" / "runs" / "iss-20261010" / "schedule" / "schedule.json")
    parser.add_argument("--java", help="Java 21 executable; defaults to JAVA_HOME, then PATH")
    parser.add_argument("--heap-mb", type=int, default=1024)
    parser.add_argument("--attempt-timeout-seconds", type=float, default=1800)
    parser.add_argument("--invocation-timeout-seconds", type=float, default=3600)
    args = parser.parse_args()
    if args.heap_mb <= 0 or not 0 < args.attempt_timeout_seconds < float("inf") or not 0 < args.invocation_timeout_seconds < float("inf"):
        parser.error("Heap and wall budgets must be finite and positive")
    args.output, args.jar, args.schedule = args.output.resolve(), args.jar.resolve(), args.schedule.resolve()
    return args


def run(args):
    cases = grid()
    run_identity = identity(args)
    root = args.output
    plan = {"schemaVersion": "1.0", "protocol": "docs/experiment-grid.md", "identity": run_identity,
            "modeSemantics": "Cumulative registered stages; all contains the full 210 distinct policy configurations",
            "cases": [{"id": c.id, "firstStage": stage, "configuration": asdict(c)} for c, stage in cases]}
    if args.resume:
        if read_json(root / "plan.json") != plan:
            raise ValueError("Resume identity/plan changed; preserve this run and use a new output directory")
        registry = read_json(root / "registry.json")
        # Recover only a completed immutable outcome after interruption between its write and registry update.
        for case_id, row in registry["cases"].items():
            if row["status"] == "RUNNING":
                tier = row["runningTier"]
                outcome_file = root / "attempts" / case_id / f"tier{tier}" / "outcome.json"
                if not outcome_file.is_file():
                    raise ValueError(f"Unresolved attempt {case_id}/tier{tier}; verify JVM termination and preserve evidence before recovery")
                row["attempts"].append(read_json(outcome_file))
                row["status"] = row["attempts"][-1]["status"]
                row.pop("runningTier")
            for outcome in row["attempts"]:
                directory = (root / outcome["directory"]).resolve()
                if not directory.is_relative_to(root) or read_json(directory / "outcome.json") != outcome:
                    raise ValueError(f"Immutable attempt differs from the registry: {case_id}")
                if "summarySha256" in outcome:
                    result_directory = directory / "results"
                    summary_file = result_directory / "summary.json"
                    summary = read_json(summary_file)
                    row_in_summary = summary[outcome["caseInSummary"]]
                    decision_file = (result_directory / row_in_summary["compactDecisionsFile"]).resolve()
                    if not decision_file.is_relative_to(result_directory) or sha(summary_file) != outcome["summarySha256"] \
                            or sha(decision_file) != outcome["decisionsSha256"]:
                        raise ValueError(f"Previously recorded result has changed: {case_id}")
    else:
        if root.exists() and (not root.is_dir() or any(p.name != ".runner.lock" for p in root.iterdir())):
            raise ValueError("Output must be new/empty, or use --resume with the identical build and inputs")
        root.mkdir(parents=True, exist_ok=True)
        write_new(root / "plan.json", plan)
        registry = {"schemaVersion": "1.0", "createdAt": stamp(), "invocations": [],
                    "cases": {c.id: {"status": "NOT_REQUESTED", "firstStage": stage, "attempts": []} for c, stage in cases}}
    selected = [c for c, stage in cases if stage <= MODES[args.mode]]
    invocation = {"startedAt": stamp(), "mode": args.mode, "planOnly": args.plan_only,
                  "attemptTimeoutSeconds": args.attempt_timeout_seconds, "invocationTimeoutSeconds": args.invocation_timeout_seconds}
    registry["invocations"].append(invocation)
    for case in selected:
        row = registry["cases"][case.id]
        if row["status"] in ("NOT_REQUESTED", "NOT_RUN_INVOCATION_LIMIT", "NOT_RUN_AFTER_PROCESS_STOP"):
            row["status"] = "PLANNED"
            row.pop("blockedBy", None)
    save_registry(root, registry)
    if args.plan_only:
        invocation.update(endedAt=stamp(), outcome="PLAN_ONLY")
        save_registry(root, registry)
        print(json.dumps({"output": str(root), "plannedCases": len(selected), "fullGridCases": len(cases)}, indent=2))
        return 0

    started = time.monotonic()
    stop_reason = None
    for case in selected:
        row = registry["cases"][case.id]
        if row["status"] == "FINISHED":
            continue
        blockers = [earlier.id for earlier, _ in cases if earlier.chain == case.chain and earlier.contact_count < case.contact_count
                    and registry["cases"][earlier.id]["status"] == "SEARCH_INCOMPLETE"
                    and len(registry["cases"][earlier.id]["attempts"]) == len(TIERS)]
        if blockers:
            row.update(status="NOT_RUN_AFTER_BUDGET_LIMIT", blockedBy=blockers)
            save_registry(root, registry)
            continue
        attempts = row["attempts"]
        if attempts and (attempts[-1]["status"] != "SEARCH_INCOMPLETE" or len(attempts) >= len(TIERS)):
            # Terminal failed attempts remain explicit; no unregistered third tier or automatic process-failure retry.
            continue
        while len(attempts) < len(TIERS):
            remaining = args.invocation_timeout_seconds - (time.monotonic() - started)
            if remaining <= 0:
                stop_reason = "INVOCATION_TIME_LIMIT"
                break
            tier = len(attempts) + 1
            timeout_classification = "ATTEMPT_TIMEOUT" if args.attempt_timeout_seconds < remaining else "INVOCATION_TIME_LIMIT"
            row.update(status="RUNNING", runningTier=tier)
            save_registry(root, registry)
            print(f"Starting {case.id} tier {tier}", flush=True)
            outcome = attempt(root, case, tier, run_identity, min(args.attempt_timeout_seconds, remaining), timeout_classification)
            attempts.append(outcome)
            row.update(status=outcome["status"])
            row.pop("runningTier")
            save_registry(root, registry)
            print(f"Finished {case.id} tier {tier}: {outcome['status']} ({outcome['wallSeconds']:.2f} s)", flush=True)
            if outcome["status"] in ("PROCESS_FAILED", "INVALID_OUTPUT", "ATTEMPT_TIMEOUT", "INVOCATION_TIME_LIMIT", "INTERRUPTED"):
                stop_reason = outcome["status"]
                break
            if outcome["status"] != "SEARCH_INCOMPLETE":
                break
        if stop_reason:
            break
    for case in selected:
        row = registry["cases"][case.id]
        if row["status"] == "PLANNED":
            row.update(status="NOT_RUN_INVOCATION_LIMIT" if stop_reason == "INVOCATION_TIME_LIMIT" else "NOT_RUN_AFTER_PROCESS_STOP",
                       blockedBy=stop_reason)
    counts = {}
    for case in selected:
        status = registry["cases"][case.id]["status"]
        counts[status] = counts.get(status, 0) + 1
    invocation.update(endedAt=stamp(), wallSeconds=time.monotonic() - started, stopReason=stop_reason, requestedStatusCounts=counts)
    save_registry(root, registry)
    print(json.dumps({"output": str(root), "requestedCases": len(selected), "statusCounts": counts}, indent=2))
    return 0 if counts.get("FINISHED", 0) == len(selected) else 2


def main():
    args = parse_args()
    root = args.output
    if args.resume:
        if not root.is_dir():
            raise ValueError("--resume requires an existing run directory")
    elif root.exists() and (not root.is_dir() or any(root.iterdir())):
        raise ValueError("Output must be new/empty, or use --resume with the identical build and inputs")
    root.mkdir(parents=True, exist_ok=True)
    lock = root / ".runner.lock"
    try:
        handle = lock.open("x", encoding="utf-8")
    except FileExistsError:
        raise ValueError("Run directory is locked; another runner may be active. Do not remove a stale lock until its JVM has stopped") from None
    try:
        with handle:
            json.dump({"pid": os.getpid(), "startedAt": stamp()}, handle)
        return run(args)
    finally:
        lock.unlink()


if __name__ == "__main__":
    sys.exit(main())
