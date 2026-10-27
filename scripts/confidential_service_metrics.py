#!/usr/bin/env python3
"""Retrospective service under the existing recording/key-disclosure model.

This module reads factual journals only. It is not imported by a policy, solver,
or simulator and never supplies a sixth optimization criterion.
"""
import audit_security_evaluation as audit


def measure(scenario, events, run_status, observation_end):
    contacts = {c['window']['id']: c for c in scenario['contacts']}
    assert len(contacts) == len(scenario['contacts'])
    end = audit.instant(observation_end)
    assert end <= audit.instant(scenario['end'])
    if run_status == 'FINISHED': assert end == audit.instant(scenario['end'])
    # Deliberately collect disclosures before classifying any earlier frame.
    disclosed = {key for e in events if e['type'] == 'KEY_DISCLOSED'
                 for key in (e.get('keyIds') or {}).values() if key is not None}
    counts = {cid: dict(uplinkBytes=0, downlinkBytes=0,
        confidentialUplinkBytes=0, confidentialDownlinkBytes=0) for cid in contacts}
    frame_rows, seen = [], set()
    last = audit.instant(scenario['start'])
    for i, event in enumerate(events):
        at = audit.instant(event['at'])
        assert event['sequence'] == i and last <= at <= end, 'Invalid observed journal order/end'
        last = at
        if event['type'] not in {'PAYLOAD_TRANSFERRED', 'PAYLOAD_ABORTED'}: continue
        frame = audit.read_frame(event, contacts)
        identity = frame['contactId'], frame['from'], frame['direction']
        assert identity not in seen, 'Duplicate factual frame'
        seen.add(identity)
        confidential = frame['delivered'] and frame['keyId'] not in disclosed
        if frame['delivered']:
            p = counts[frame['contactId']]
            direction = frame['direction'].lower()
            p[direction + 'Bytes'] += frame['frameBytes']
            if confidential: p['confidential' + direction.title() + 'Bytes'] += frame['frameBytes']
        frame_rows.append(dict(sequence=i, contactId=frame['contactId'], direction=frame['direction'],
            keyId=frame['keyId'], delivered=frame['delivered'], confidential=confidential,
            frameBytes=frame['frameBytes'], disclosedByObservationEnd=frame['keyId'] in disclosed))
    per_contact = []
    for cid, c in contacts.items():
        p, n = c['payload'], counts[cid]
        assert n['uplinkBytes'] <= p['offeredUplinkBytes'] and n['downlinkBytes'] <= p['offeredDownlinkBytes']
        served = n['uplinkBytes'] >= p['minimumUplinkBytes'] and n['downlinkBytes'] >= p['minimumDownlinkBytes']
        secret = n['confidentialUplinkBytes'] >= p['minimumUplinkBytes'] and n['confidentialDownlinkBytes'] >= p['minimumDownlinkBytes']
        assert not secret or served
        if not disclosed: assert served == secret
        per_contact.append(dict(contactId=cid, minimumUplinkBytes=p['minimumUplinkBytes'],
            minimumDownlinkBytes=p['minimumDownlinkBytes'], **n, served=served, confidentialServed=secret))
    totals = dict(servedContacts=sum(c['served'] for c in per_contact),
        confidentialServedContacts=sum(c['confidentialServed'] for c in per_contact),
        confidentialDeliveredBytes=sum(c['confidentialUplinkBytes']+c['confidentialDownlinkBytes'] for c in per_contact))
    return dict(status='FINAL' if run_status == 'FINISHED' else 'UNAVAILABLE',
        final=totals if run_status == 'FINISHED' else None,
        observedPrefix=dict(observationEnd=observation_end, totals=totals, contacts=per_contact,
            meaning='Retrospective only through observed disclosures; later disclosure can reduce confidentiality'),
        disclosedDirectionalKeys=sorted(disclosed), frames=frame_rows)
