import unittest
from run_private_export_process import fixture_id, target, validate_receipt, process_absent_result, require_missing_receipt, state_ready, observer_xml_path, confirmation_target, PACKAGE
ID='ad6f8170-8239-4e3f-bd82-fc2bb481c378'
class Guards(unittest.TestCase):
    def test_assigned_exact_lanes(self):
        self.assertEqual('35',target('127.0.0.1:5563')[1]);self.assertEqual('30',target('emulator-5554')[1])
    def test_alias_not_adopted(self):
        for serial in ('emulator-5562','device','',None):
            with self.subTest(serial=serial),self.assertRaises(RuntimeError):target(serial)
    def test_canonical_id(self):self.assertEqual(ID,fixture_id(ID))
    def test_noncanonical_id(self):
        for value in (ID.upper(),'{'+ID+'}',ID.replace('-',''),'../fixture','',None):
            with self.subTest(value=value),self.assertRaises((RuntimeError,ValueError,AttributeError)):fixture_id(value)
    def test_exact_receipt(self):
        value={'fixture':ID,'state':'Ready'};self.assertIs(value,validate_receipt(value,ID))
    def test_foreign_receipt(self):
        for value in ({'fixture':'other'}, {},[],None):
            with self.subTest(value=value),self.assertRaises(RuntimeError):validate_receipt(value,ID)
    def test_live_pid_not_death(self):self.assertFalse(process_absent_result('UGALLERY_PROC_STATUS:0\n',0))
    def test_confirmed_absent(self):self.assertTrue(process_absent_result('UGALLERY_PROC_STATUS:1\n',1))
    def test_empty_timeout_denied_not_death(self):
        for stdout,code in (('',1),('',0),('Permission denied',1),('UGALLERY_PROC_STATUS:1',124),('UGALLERY_PROC_STATUS:0',1)):
            with self.subTest(stdout=stdout,code=code),self.assertRaises(RuntimeError):process_absent_result(stdout,code)
    def test_missing_receipt_is_explicit(self):
        require_missing_receipt(1,'cat: files/fixture.json: No such file or directory','files/fixture.json')
    def test_denied_or_wrong_missing_receipt_is_not_absent(self):
        for code,text in ((1,'Permission denied'),(124,'files/fixture.json: No such file or directory'),(1,'elsewhere: No such file or directory')):
            with self.subTest(code=code,text=text),self.assertRaises(RuntimeError):require_missing_receipt(code,text,'files/fixture.json')
    def test_ack_waits_for_closed_inventory(self):
        for closed in (None,False,1,"true"):
            with self.subTest(closed=closed):self.assertFalse(state_ready({'state':'Acked','databaseClosed':closed},'Acked'))
        self.assertTrue(state_ready({'state':'Acked','databaseClosed':True},'Acked'))
    def test_other_states_require_exact_name(self):
        self.assertTrue(state_ready({'state':'Ready'},'Ready'))
        self.assertFalse(state_ready({'state':'Completed'},'Ready'))
    def test_real_observer_path_contract(self):
        import re
        value=observer_xml_path(ID,21)
        self.assertRegex(value,r"^/(?:sdcard|data/local/tmp)/creation-process-[a-z0-9-]+\.xml$")
        self.assertIn(ID,value)
    def test_invalid_observer_counter(self):
        for value in (0,-1,True,"1"):
            with self.subTest(value=value),self.assertRaises(RuntimeError):observer_xml_path(ID,value)
    def dialog(self):
        import xml.etree.ElementTree as E
        texts=("Forget recovery record","fixture.png","Close this recovery record without deleting any exported file? An unencrypted copy may still exist outside the private album. Exporting again may create duplicates.","Confirm","Cancel")
        return [E.Element('node',{'package':PACKAGE,'text':t,'enabled':'true'}) for t in texts]
    def test_exact_english_owned_confirmation(self):
        nodes=self.dialog();self.assertIs(nodes[3],confirmation_target(nodes,'fixture.png'))
    def test_wrong_file_dialog_rejected(self):
        with self.assertRaises(RuntimeError):confirmation_target(self.dialog(),'other.png')
    def test_foreign_disabled_duplicate_confirmation_rejected(self):
        for change in ('package','enabled','duplicate'):
            nodes=self.dialog()
            if change=='duplicate':nodes.append(nodes[3])
            else:nodes[3].set(change,'other' if change=='package' else 'false')
            with self.subTest(change=change),self.assertRaises(RuntimeError):confirmation_target(nodes,'fixture.png')
if __name__=='__main__':unittest.main()

