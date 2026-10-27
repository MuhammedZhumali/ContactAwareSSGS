"""Pure LaTeX projection of the final, independently audited 108-case matrix.

The caller owns current protocol/table/audit hash checks and file writing. This
module performs no I/O and deliberately has no command-line entry point.
Requires the supplement's existing booktabs, longtable and array packages.
"""

from collections import Counter
from decimal import Decimal, InvalidOperation
import re


_FIVE = ("servedContacts", "usefulSecureTime", "controlCpu", "managementBytes", "newKeys")
_STATUS_CODES = {
    "FINISHED": "F",
    "PROCESS_TIMEOUT": "PT",
    "PROCESS_ERROR": "PE",
    "EXPORT_INCOMPLETE": "EI",
    "MISSING_RESULT": "MR",
    "SEARCH_INCOMPLETE": "SI",
    "NO_FEASIBLE_PLAN": "NF",
    "EXECUTION_STALLED": "ES",
    "CONTROLLER_TIME_LIMIT": "CT",
    "CANCELLED": "CA",
    "INTERRUPTED": "IN",
}
_ESCAPES = {
    "\\": r"\textbackslash{}", "&": r"\&", "%": r"\%", "$": r"\$",
    "#": r"\#", "_": r"\_\allowbreak{}", "{": r"\{", "}": r"\}",
    "~": r"\textasciitilde{}", "^": r"\textasciicircum{}",
}


def _tex(value):
    return "".join(_ESCAPES.get(char, char) for char in str(value))


def _number(value, integer=False):
    """Missing stays NA; positive sub-resolution values never become zero."""
    if value is None:
        return "NA"
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"Non-numeric reported metric: {value!r}") from exc
    if not number.is_finite() or number < 0:
        raise ValueError(f"Invalid nonnegative reported metric: {value!r}")
    if integer:
        if number != number.to_integral_value():
            raise ValueError(f"Nonintegral count/byte metric: {value!r}")
        return str(int(number))
    if 0 < number < Decimal("0.005"):
        return r"$<0.005$"
    return format(number, ".2f").rstrip("0").rstrip(".")


def _validate(rows, validation):
    if validation.get("status") != "PASS":
        raise ValueError("Final supplement requires a PASS audit")
    if len(rows) != 108 or validation.get("registered") != 108 or validation.get("attempted") != 108:
        raise ValueError("Final supplement requires all 108 registered attempts")
    full_ids, numbers = set(), set()
    for row in rows:
        identity = row.get("id")
        match = re.fullmatch(r"([0-9]{3})-.+", identity or "")
        if not match or identity in full_ids:
            raise ValueError("Missing, malformed or duplicate full case ID")
        full_ids.add(identity)
        number = int(match.group(1))
        if number in numbers:
            raise ValueError(f"Duplicate numeric case prefix: {number:03d}")
        numbers.add(number)
        for field in ("block", "scenario", "load", "policy", "rule", "model", "status"):
            if not isinstance(row.get(field), str) or not row[field]:
                raise ValueError(f"Missing case identity/status field: {identity}/{field}")
        if not isinstance(row.get("measured"), bool):
            raise ValueError(f"Missing measured-mode identity: {identity}")
        if row["status"] == "NOT_RUN":
            raise ValueError("Final supplement cannot include NOT_RUN cases")
        if row["status"] == "FINISHED":
            for field in _FIVE:
                if row.get(field) is None:
                    raise ValueError(f"Missing final objective component: {identity}/{field}")
    if numbers != set(range(1, 109)):
        raise ValueError("Numeric case prefixes must cover exactly 001 through 108")
    counts = Counter(row["status"] for row in rows)
    if dict(counts) != validation.get("statuses"):
        raise ValueError("Row execution statuses disagree with the validated matrix")


def _longtable(caption, label, columns, headers, body):
    """Fixed-width columns fit within the supplement's 160-mm text width."""
    if sum(width for _, width in columns) + (len(columns) - 1) * 1.406 > 150:
        raise ValueError("Supplement table exceeds its width budget")
    alignment = {"l": r"\raggedright", "r": r"\raggedleft", "c": r"\centering"}
    spec = "@{}" + "".join(
        ">{" + alignment[align] + r"\arraybackslash}p{" + str(width) + "mm}"
        for align, width in columns
    ) + "@{}"
    header = " & ".join(headers) + r" \\"
    return "\n".join([
        r"\begingroup\footnotesize",
        r"\setlength{\tabcolsep}{2pt}\renewcommand{\arraystretch}{1.12}",
        r"\begin{longtable}{" + spec + "}",
        r"\caption{" + caption + r"}\label{" + label + r"}\\",
        r"\toprule", header, r"\midrule\endfirsthead",
        r"\multicolumn{" + str(len(columns)) + r"}{l}{\textit{Continued: " + caption + r"}}\\",
        r"\toprule", header, r"\midrule\endhead",
        r"\midrule\multicolumn{" + str(len(columns)) + r"}{r}{\textit{Continued on next page}}\\\endfoot",
        r"\bottomrule\endlastfoot", *body,
        r"\end{longtable}\endgroup", "",
    ])


def _row(cells):
    return " & ".join(cells) + r" \\"


def _case(row):
    return row["id"].split("-", 1)[0]


def _policy(row):
    names = {"FixedPeriod": "Fixed", "PerContact": "Contact"}
    name = names.get(row["policy"], row["policy"])
    if row["policy"] in ("Next", "Multi"):
        name += " H" + str(row["horizon"])
    return _tex(name)


def _milestone(row, name):
    if row["status"] != "FINISHED":
        return "I"
    status, value = row.get(name + "Status"), row.get(name + "Seconds")
    if status in (None, "unavailable"):
        return "U"
    if status == "censored":
        return "C"
    if status == "not-applicable":
        return "N/A"
    if status not in ("event", "already-ready-at-origin"):
        raise ValueError(f"Unrecognized recovery status: {status!r}")
    if value is None:
        raise ValueError(f"Observed recovery milestone has no time: {row['id']}/{name}")
    return ("AR:" if status == "already-ready-at-origin" else "E:") + _number(value)


def _pair(row, first, second):
    if row["status"] != "FINISHED":
        return "I"
    return _number(row.get(first), integer=True) + r"/\allowbreak{}" + _number(row.get(second), integer=True)


def render_supplement_results(rows, validation):
    """Return a LaTeX fragment, without reading or writing any file.

    ``rows`` is the final analyzer's tables.json list and ``validation`` its
    validation.json object. The caller must first bind both to the current
    registered protocol, summary and audit using the final report's hash gate.
    The pure renderer additionally requires the complete 001..108 matrix,
    unique full IDs, PASS and matching execution-status counts. It does not
    infer final scores from prefixes or replace missing ledger fields by zero.
    """
    rows = list(rows)
    _validate(rows, validation)
    rows.sort(key=lambda item: int(_case(item)))
    statuses = dict(_STATUS_CODES)
    unknown = sorted({row["status"] for row in rows} - statuses.keys())
    statuses.update((name, "X" + str(index + 1)) for index, name in enumerate(unknown))
    observed_statuses = list(dict.fromkeys(row["status"] for row in rows))
    lines = [
        r"\subsection{Complete Registered Outcome Matrix}",
        "All 108 registered attempts appear below, including process failures and unfinished executions. "
        "Case numbers 001--108 are the exact numeric prefixes of the full case IDs in "
        r"\path{docs/final-research/runs.csv}; that table maps each number to its full ID, frozen model, "
        "rule, horizon and invocation identity. Group labels retain block, scenario, load, execution mode "
        "and tie rule. Fixed = FixedPeriod; Contact = PerContact. H denotes the registered horizon.",
        "Execution codes: " + "; ".join(
            _tex(statuses[status]) + " = " + _tex(status) for status in observed_statuses
        ) + ". Codes retain distinct process and controller outcomes.",
        "F means completed physical execution, not a global optimality certificate. "
        "I means unavailable final quality for an unfinished execution; NA means the indicated "
        "measurement was not exported/available. Neither is zero. Seconds are rounded to two decimal "
        "places; positive values below 0.005 s are shown as such. Counts and bytes are integers.",
        "The five objective components are served contacts, useful secure transmission seconds, "
        "modeled lifecycle-operation CPU seconds, management bytes, and new directional keys. "
        "Controller time is host wall time over the controller call, not the modeled operation CPU.",
    ]
    body, previous = [], None
    for row in rows:
        metadata = row.get("metadata") or {}
        group = (row["block"], row["scenario"], row["load"], row["measured"], row["rule"],
                 metadata.get("modelKey", row["model"]))
        if group != previous:
            text = " / ".join([group[0], group[1], group[2], "measured" if group[3] else "offline", group[4]])
            if row["block"] == "sensitivity":
                text += "; factor/model " + group[5]
            body.append(r"\addlinespace[2pt]\multicolumn{9}{@{}p{149mm}@{}}{\textbf{" + _tex(text) + r"}}\\*")
            previous = group
        five = [
            _number(row.get(field), integer=field in ("servedContacts", "managementBytes", "newKeys"))
            if row["status"] == "FINISHED" else "I" for field in _FIVE
        ]
        body.append(_row([_case(row), _policy(row), statuses[row["status"]], *five,
                          _number(row.get("controllerSeconds"))]))
    lines.append(_longtable(
        "All registered outcomes", "tab:supp-all-outcomes",
        [("r", 8), ("l", 20), ("c", 10), ("r", 10), ("r", 18), ("r", 16), ("r", 20), ("r", 10), ("r", 18)],
        ["Case", "Policy", "Exec.", "Served", r"Useful\newline s", r"Oper. CPU\newline s",
         r"Mgmt.\newline B", "Keys", r"Controller\newline s"], body,
    ))
    lines.extend([
        r"\subsection{Actual Computational Work}",
        "Calls, incomplete calls, nodes and search seconds come from the exported actual-search ledger, "
        "including complete and incomplete invocations. They are not the count of certified selections. "
        "A genuine empty ledger can report zero; an unexported ledger after process termination remains NA. "
        "Process wall time includes startup and export/termination. Search CPU covers the calling thread "
        "during search; it is separate from aggregate harness CPU/GC with its different sampling interval. "
        "Miss denotes decision deadline misses. These are single host observations, not onboard timing estimates.",
    ])
    body = []
    for row in rows:
        body.append(_row([
            _case(row), statuses[row["status"]], _number(row.get("processSeconds")),
            _number(row.get("searches"), True), _number(row.get("incompleteSearches"), True),
            _number(row.get("nodes"), True), _number(row.get("searchSeconds")),
            _number(row.get("searchThreadCpuSeconds")), _number(row.get("deadlineMisses"), True),
        ]))
    lines.append(_longtable(
        "Process and search-ledger diagnostics", "tab:supp-search",
        [("r", 8), ("c", 10), ("r", 16), ("r", 12), ("r", 12), ("r", 18), ("r", 18), ("r", 18), ("r", 12)],
        ["Case", "Exec.", r"Process\newline s", "Calls", r"Incomp.\newline calls", "Nodes",
         r"Search\newline s", r"Search CPU\newline s", "Miss"], body,
    ))
    measured = [row for row in rows if row["measured"]]
    if measured:
        lines.append(
            "Measured decision host seconds sum the actual decision intervals charged to the model. "
            "They exclude checkpoint I/O and actual executor work and are not interchangeable with "
            "controller wall time. Recorded diagnostics can describe a stopped execution; they do not "
            "supply its unavailable final service result."
        )
        lines.append(_longtable(
            "Measured decision-delay diagnostics", "tab:supp-delay",
            [("r", 12), ("c", 14), ("r", 22), ("r", 35), ("r", 22)],
            ["Case", "Exec.", "Decisions", r"Decision host\newline s", r"Deadline\newline misses"],
            [_row([_case(row), statuses[row["status"]], _number(row.get("decisions"), True),
                   _number(row.get("decisionHostSeconds")), _number(row.get("deadlineMisses"), True)])
             for row in measured],
        ))
    security = [row for row in rows if row["block"] in ("security", "management")]
    lines.extend([
        r"\subsection{Disclosure and Interruption Outcomes}",
        "The following tables retain every registered security and management-interruption case. "
        "UL/DL denote uplink/downlink. Delivered bytes and frame counts include complete delivered frames only. "
        "Confidential bytes are retrospective through experiment end. Decryptable emitted bytes include "
        "recorded traffic before disclosure and traffic emitted afterward, including aborted prefixes. "
        "Exposure and confidential delivery are therefore not a partition of delivered traffic. "
        "Only FINISHED executions receive final traffic totals.",
    ])
    traffic = []
    for row in security:
        traffic.append(_row([
            _case(row), statuses[row["status"]],
            _pair(row, "deliveredUplinkBytes", "deliveredDownlinkBytes"),
            _pair(row, "deliveredUplinkFrames", "deliveredDownlinkFrames"),
            _pair(row, "confidentialUplinkBytes", "confidentialDownlinkBytes"),
            _pair(row, "decryptableBeforeBytes", "decryptableAfterBytes"),
        ]))
    lines.append(_longtable(
        "Final delivered traffic and emitted exposure", "tab:supp-traffic",
        [("r", 8), ("c", 10), ("r", 30), ("r", 24), ("r", 30), ("r", 30)],
        ["Case", "Exec.", r"Delivered B\newline UL/DL", r"Frames\newline UL/DL",
         r"Confidential B\newline UL/DL", r"Decryptable B\newline before/after"], traffic,
    ))
    lines.append(
        "Milestones are seconds after GS notice: E = observed event, AR = already ready at the origin, "
        "C = right-censored in a completed execution, U = unavailable origin or missing evidence, "
        "N/A = not applicable (no attack, no target or undetected incident). I retains an unfinished "
        "execution without presenting its prefix as final recovery or final censoring. "
        "Incident state is reported separately. First readiness does not imply continuous availability."
    )
    milestones = ("spacecraft_notice", "independent_pair", "independent_sa", "independent_frame")
    lines.append(_longtable(
        "Four distinct recovery milestones", "tab:supp-recovery",
        [("r", 8), ("c", 10), ("l", 30), ("r", 22), ("r", 22), ("r", 22), ("r", 22)],
        ["Case", "Exec.", "Incident state", r"SC notice\newline s", r"Indep. keys\newline s",
         r"Open SA\newline s", r"Indep. frame\newline s"],
        [_row([_case(row), statuses[row["status"]],
               _tex(row.get("recoveryStatus") or "UNAVAILABLE") if row["status"] == "FINISHED" else "I",
               *[_milestone(row, milestone) for milestone in milestones]]) for row in security],
    ))
    lines.append(
        "Interruption diagnostics below describe actually recorded events and consumed partial operation "
        "resources, not unused budgets or additional costs to add to the final five scores. For an "
        "unfinished execution they remain prefix diagnostics; absent exports remain NA. The fixed "
        "outage is not shifted to target a different operation. Full action counts remain in runs.csv."
    )
    lines.append(_longtable(
        "Observed interruption diagnostics", "tab:supp-interruption",
        [("r", 8), ("c", 10), ("l", 53), ("r", 18), ("r", 23), ("r", 23)],
        ["Case", "Exec.", "Interrupted actions/counts", r"Aborted\newline frames",
         r"Partial oper.\newline CPU s", r"Partial mgmt.\newline B"],
        [_row([_case(row), statuses[row["status"]], _tex(row.get("interruptedOperations") or "NA"),
               _number(row.get("abortedFrames"), True), _number(row.get("partialOperationCpu")),
               _number(row.get("partialManagementBytes"))]) for row in security],
    ))
    lines.append(
        "The full IDs, model identities, factor-baseline comparisons, status values, source journal paths "
        "and unrounded measurements remain in the audited CSV/JSON supplement. No unfinished case enters "
        "a completed-quality comparison."
    )
    return "\n\n".join(lines) + "\n"
