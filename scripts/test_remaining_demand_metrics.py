"""Focused boundary checks of new journal-derived quantities; no JVM/solver."""
import unittest
from analyze_security_evaluation_remaining_demand import demand_metrics


def at(seconds): return '2026-10-12T00:00:'+f'{seconds:02d}'+'Z'


class DemandMetricsTest(unittest.TestCase):
    def setUp(self):
        self.cid=dict(satelliteId='S',groundStationId='A')
        self.scenario=dict(contacts=[dict(window=dict(id='C1',**self.cid,start=at(0),end=at(50)),
            payload=dict(frameBytes=1000,bytesPerSecond=100,offeredUplinkBytes=39000,offeredDownlinkBytes=39000))],disclosures=[{}])
        self.recovered=dict(milestones=dict(independent_frame=dict(at=at(35),status='event'),
            independent_pair=dict(at=at(24),status='event'),independent_sa=dict(at=at(25),status='event')))

    def frame(self,sequence,start,end,direction,key,delivered):
        return dict(sequence=sequence,at=at(end),type='PAYLOAD_TRANSFERRED' if delivered else 'PAYLOAD_ABORTED',
            contactId='C1',contextId=self.cid,keyIds={direction.lower():key},usefulTime=10 if delivered else 0,
            uplinkFrames=int(delivered and direction=='UPLINK'),downlinkFrames=int(delivered and direction=='DOWNLINK'),
            uplinkPayloadBytes=1000*int(delivered and direction=='UPLINK'),downlinkPayloadBytes=1000*int(delivered and direction=='DOWNLINK'),
            frameEvidence=dict(**{'from':at(start)},until=at(end),direction=direction,keyId=key,frameBytes=1000,
                emittedBytes=(end-start)*100,delivered=delivered))

    def test_full_delivery_boundary_direction_and_aborted_frame(self):
        events=[self.frame(0,0,10,'UPLINK','old-up',True),
            dict(sequence=1,type='KEY_DISCLOSED',keyIds=dict(uplink='old-up',downlink='old-down')),
            self.frame(2,10,20,'DOWNLINK','old-down',True),
            dict(sequence=3,at=at(20),type='REVOCATION_RECEIVED_GROUND'),
            self.frame(4,20,25,'UPLINK','new-up',False),self.frame(5,25,35,'DOWNLINK','new-down',True)]
        values,status,details=demand_metrics(self.scenario,events,self.recovered,'FINISHED')
        self.assertEqual(38000,values['remainingUplinkAtNotice'])
        self.assertEqual(38000,values['remainingDownlinkAtNotice'])
        self.assertEqual(1000,values['confidentialC1AfterNotice'])
        self.assertEqual([5],[f['sequence'] for f in details['frames']])
        self.assertEqual((26,25),(values['remainingC1AtPairSeconds'],values['remainingC1AtSaSeconds']))
        self.assertEqual('EVENT',status['independentFrameInC1'])

    def test_censored_is_not_missing_bytes_and_late_stage_is_signed(self):
        events=[dict(sequence=0,type='KEY_DISCLOSED',keyIds=dict(uplink='old-up',downlink='old-down')),
                dict(sequence=1,at=at(20),type='REVOCATION_RECEIVED_GROUND')]
        self.recovered['milestones']['independent_frame']=dict(at=None,status='censored')
        self.recovered['milestones']['independent_pair']['at']=at(55)
        self.recovered['milestones']['independent_sa']=dict(at=None,status='censored')
        values,status,_=demand_metrics(self.scenario,events,self.recovered,'FINISHED')
        self.assertEqual(0,values['confidentialC1AfterNotice'])
        self.assertFalse(values['independentFrameInC1'])
        self.assertEqual('CENSORED',status['independentFrameInC1'])
        self.assertEqual(-5,values['remainingC1AtPairSeconds'])
        self.assertIsNone(values['remainingC1AtSaSeconds'])

    def test_unfinished_and_no_attack_do_not_impute_zero(self):
        values,status,_=demand_metrics(self.scenario,[],{},'SEARCH_INCOMPLETE')
        self.assertTrue(all(v is None for v in values.values()))
        self.assertEqual({'UNAVAILABLE'},set(status.values()))
        self.scenario['disclosures']=[]
        values,status,_=demand_metrics(self.scenario,[],{},'FINISHED')
        self.assertTrue(all(v is None for v in values.values()))
        self.assertEqual({'NOT-APPLICABLE'},set(status.values()))


if __name__=='__main__': unittest.main()
