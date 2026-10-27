"""Small journal-only tests: no executor, planner, Java or new experiment."""
import unittest
from confidential_service_metrics import measure


def time(t): return '2026-01-01T00:00:'+f'{t:06.3f}'+'Z'


class ConfidentialServiceTest(unittest.TestCase):
    def setUp(self):
        self.cid=dict(satelliteId='S',groundStationId='A')
        self.scenario=dict(start=time(0),end=time(50),contacts=[dict(
            window=dict(id='C',**self.cid,start=time(0),end=time(50)),
            payload=dict(frameBytes=100,bytesPerSecond=100,offeredUplinkBytes=1000,offeredDownlinkBytes=1000,
                         minimumUplinkBytes=200,minimumDownlinkBytes=100))])
        self.events=[]

    def frame(self,t,d,key='g1',delivered=True,elapsed=1):
        up=d=='UPLINK'
        self.events.append(dict(sequence=len(self.events),at=time(t+elapsed),
            type='PAYLOAD_TRANSFERRED' if delivered else 'PAYLOAD_ABORTED',contextId=self.cid,contactId='C',
            keyIds={d.lower():key},usefulTime=elapsed if delivered else 0,
            uplinkFrames=int(up and delivered),downlinkFrames=int(not up and delivered),
            uplinkPayloadBytes=100*int(up and delivered),downlinkPayloadBytes=100*int(not up and delivered),
            frameEvidence=dict(**{'from':time(t)},until=time(t+elapsed),direction=d,keyId=key,
                frameBytes=100,emittedBytes=100*elapsed,delivered=delivered)))

    def disclose(self,at,**keys):
        self.events.append(dict(sequence=len(self.events),at=time(at),type='KEY_DISCLOSED',keyIds=keys))

    def result(self,status='FINISHED',end=50): return measure(self.scenario,self.events,status,time(end))

    def test_direction_thresholds_cannot_be_pooled(self):
        for t in range(4): self.frame(t,'UPLINK','up')
        self.assertEqual(0,self.result()['final']['confidentialServedContacts'])
        self.frame(4,'DOWNLINK','down')
        self.assertEqual(1,self.result()['final']['confidentialServedContacts'])

    def test_later_disclosure_retroactively_removes_earlier_frames(self):
        self.frame(0,'UPLINK','up');self.frame(1,'UPLINK','up');self.frame(2,'DOWNLINK','down')
        self.disclose(10,uplink='up',downlink='down')
        self.assertEqual(dict(servedContacts=1,confidentialServedContacts=0,confidentialDeliveredBytes=0),self.result()['final'])

    def test_mixed_generations_keep_only_undisclosed_directional_material(self):
        self.frame(0,'UPLINK','old-up');self.frame(1,'UPLINK','new-up');self.frame(2,'DOWNLINK','old-down')
        self.disclose(10,uplink='old-up')
        self.assertEqual(0,self.result()['final']['confidentialServedContacts'])
        self.frame(11,'UPLINK','new-up')
        self.assertEqual(dict(servedContacts=1,confidentialServedContacts=1,confidentialDeliveredBytes=300),self.result()['final'])

    def test_aborted_even_full_emission_does_not_supply_service(self):
        self.frame(0,'UPLINK','up');self.frame(1,'UPLINK','up')
        self.frame(2,'DOWNLINK','down',delivered=False)
        self.frame(3,'DOWNLINK','down2',delivered=False,elapsed=0.5)
        self.assertEqual(dict(servedContacts=0,confidentialServedContacts=0,confidentialDeliveredBytes=200),self.result()['final'])

    def test_unfinished_has_only_explicit_observed_prefix(self):
        self.frame(0,'UPLINK','up');self.frame(1,'UPLINK','up');self.frame(2,'DOWNLINK','down')
        x=self.result('SEARCH_INCOMPLETE',end=5)
        self.assertEqual('UNAVAILABLE',x['status']);self.assertIsNone(x['final'])
        self.assertEqual(1,x['observedPrefix']['totals']['confidentialServedContacts'])

    def test_no_disclosure_equals_ordinary_and_zero_direction_threshold_is_allowed(self):
        self.scenario['contacts'][0]['payload']['minimumDownlinkBytes']=0
        self.frame(0,'UPLINK','up');self.frame(1,'UPLINK','up')
        self.assertEqual(1,self.result()['final']['servedContacts'])
        self.assertEqual(1,self.result()['final']['confidentialServedContacts'])

    def test_frames_on_disclosed_key_are_excluded_on_both_sides(self):
        self.frame(0,'UPLINK','up');self.disclose(2,uplink='up',downlink='down')
        self.frame(3,'UPLINK','up');self.frame(4,'DOWNLINK','down')
        self.assertEqual(0,self.result()['final']['confidentialDeliveredBytes'])
        self.assertEqual(1,self.result()['final']['servedContacts'])

    def test_disclosure_in_other_context_does_not_remove_unrelated_keys(self):
        self.frame(0,'UPLINK','up');self.frame(1,'UPLINK','up');self.frame(2,'DOWNLINK','down')
        self.disclose(10,uplink='other-up',downlink='other-down')
        self.assertEqual(1,self.result()['final']['confidentialServedContacts'])


if __name__=='__main__':unittest.main()
