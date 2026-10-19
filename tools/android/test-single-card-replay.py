"""Source-join guards; no game or model truth is synthesized."""
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location("singles",Path(__file__).with_name("enrich-single-card-replay.py"))
mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)

class SingleCardReplayTest(unittest.TestCase):
    def frame(self):
        return {"elapsedMs":1000,"sessionId":"s","ambiguous":0,"groups":{"OPPOSITE":["A:BLACK"]},
                "regionObserved":{"OPPOSITE":True},"glyphs":[["A:BLACK",.95,.47,.16,.49,.20]]}
    def flush(self):
        return {"elapsedMs":1000,"sessionId":"s","readings":[{"card":"A:BLACK","suit":"CLUB",
            "similarity":.86,"margin":.15,"reason":"shape_matched","placement":"TABLE"}]}
    def test_legacy_requires_explicit_opt_in_and_exact_timestamp(self):
        f,r=self.frame(),self.flush()
        self.assertEqual({},mod.select(f,[r],False)[0])
        self.assertEqual("CLUB",mod.select(f,[r],True)[0]["OPPOSITE"]["suit"])
        r["elapsedMs"]=1266
        self.assertEqual({},mod.select(f,[r],True)[0])
    def test_duplicate_symbol_or_same_rank_elsewhere_is_not_seat_evidence(self):
        f,r=self.frame(),self.flush()
        self.assertEqual({},mod.select(f,[r,r],True)[0])
        f["glyphs"].append(["A:BLACK",.96,.7,.25,.72,.3])
        self.assertEqual({},mod.select(f,[r],True)[0])
    def test_unassigned_second_card_also_prevents_a_legacy_join(self):
        f,r=self.frame(),self.flush(); f["ambiguous"]=1
        self.assertEqual({},mod.select(f,[r],True)[0])
    def test_masked_or_weak_symbols_stay_unknown(self):
        for change in ({"suit":None,"reason":"overlay_masks_suit"},{"similarity":.79},{"margin":.11},{"suit":"HEART"}):
            f,r=self.frame(),self.flush(); r["readings"][0].update(change)
            self.assertEqual({},mod.select(f,[r],True)[0])
    def test_explicit_reading_never_falls_back_to_legacy(self):
        f,r=self.frame(),self.flush(); f["singleCardIdentities"]={}
        self.assertEqual({},mod.select(f,[r],True)[0])
    def test_explicit_sources_are_fresh_current_and_seat_qualified(self):
        for source,expected in ((1000,True),(499,False),(1001,False)):
            f=self.frame(); r={**self.flush()["readings"][0],"sourceTimeMs":source}
            f["singleCardIdentities"]={"OPPOSITE":r}
            self.assertEqual(expected,bool(mod.select(f,[],False)[0]))

if __name__=="__main__": unittest.main()
