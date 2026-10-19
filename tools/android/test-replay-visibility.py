import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location("visibility",Path(__file__).with_name("prepare-replay-visibility.py"))
visibility=importlib.util.module_from_spec(spec)
spec.loader.exec_module(visibility)


class VisibilitySourceTest(unittest.TestCase):
    def fixture(self,folder,frames,tape_line="100;TABLE;false;true,true,true,false;true;false;FOUR,RED,0.90,0.75,0.3,0.77,0.35;;false"):
        path=Path(folder); tape=path/"source.tape"; tape.write_text(tape_line+"\n",encoding="utf-8")
        archive=path/"raw.zip"
        with zipfile.ZipFile(archive,"w") as zipped:
            zipped.writestr("sessions/source/trace.jsonl","\n".join(json.dumps(x) for x in frames))
        entry={"session":"source","path":str(tape),"sha256":visibility.sha(tape),"frames":1}
        return archive,entry,tape_line

    def frame(self,**changes):
        return {"type":"frame","elapsedMs":100,"glyphs":[["FOUR:RED",.90,.75,.3,.77,.35]],"overlayMasks":[],**changes}

    def test_known_empty_masks_preserve_all_original_fields_and_hash_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            archive,entry,line=self.fixture(folder,[self.frame()])
            result=visibility.prepare(archive,[entry],Path(folder)/"copies")
            self.assertEqual(line+";false;;?;?;?\n",(Path(folder)/"copies/source.tape").read_text())
            self.assertEqual(1,result["sources"][0]["knownMaskFrames"])
            self.assertEqual(entry["sha256"],result["sources"][0]["originalTapeSha256"])

    def test_wrong_card_time_missing_masks_or_duplicate_frame_cannot_supply_proof(self):
        for frames in [[self.frame(glyphs=[["FIVE:RED",.9,.75,.3,.77,.35]])],
                       [self.frame(elapsedMs=101)],[self.frame(overlayMasks=None)],
                       [self.frame(),self.frame()],[self.frame(overlayMasks=[[.5,.2,.4,.3]])]]:
            with tempfile.TemporaryDirectory() as folder:
                archive,entry,line=self.fixture(folder,frames)
                result=visibility.prepare(archive,[entry],Path(folder)/"copies")
                self.assertEqual(line+";false;?;?;?;?\n",(Path(folder)/"copies/source.tape").read_text())
                self.assertEqual(0,result["sources"][0]["knownMaskFrames"])

    def test_changed_source_hash_is_rejected_before_annotating_any_tape(self):
        with tempfile.TemporaryDirectory() as folder:
            archive,entry,_=self.fixture(folder,[self.frame()]); entry["sha256"]="0"*64
            with self.assertRaises(ValueError): visibility.prepare(archive,[entry],Path(folder)/"copies")

    def test_logged_mask_edges_are_expanded_for_rounding_not_removed(self):
        encoded=visibility.encode_masks(self.frame(overlayMasks=[[.1,.2,.3,.4]]))
        self.assertEqual("0.09990,0.19990,0.30010,0.40010",encoded)

    def test_only_literal_reasons_with_matching_observed_flags_are_preserved(self):
        reasons={s:{"observed":s!="NEXT","reason":"foreground_or_animation" if s=="NEXT" else "table_surface_visible"}
                 for s in ("SELF","PREVIOUS","OPPOSITE","NEXT")}
        with tempfile.TemporaryDirectory() as folder:
            archive,entry,line=self.fixture(folder,[self.frame(emptyEvidence=reasons)])
            result=visibility.prepare(archive,[entry],Path(folder)/"copies")
            self.assertEqual(line+";false;;?;?;table_surface_visible,table_surface_visible,table_surface_visible,foreground_or_animation\n",
                (Path(folder)/"copies/source.tape").read_text())
            self.assertEqual(1,result["sources"][0]["knownEmptyReasonFrames"])
        for bad in [None,{},dict(reasons,NEXT={"observed":True,"reason":"foreground_or_animation"}),
                    dict(reasons,NEXT={"observed":False,"reason":"injected;field"})]:
            self.assertEqual("?",visibility.encode_empty_reasons(bad,"true,true,true,false"))

    def test_unmatched_source_cannot_supply_even_plausible_empty_reasons(self):
        reasons={s:{"observed":s!="NEXT","reason":"foreground_or_animation"} for s in ("SELF","PREVIOUS","OPPOSITE","NEXT")}
        with tempfile.TemporaryDirectory() as folder:
            archive,entry,line=self.fixture(folder,[self.frame(elapsedMs=101,emptyEvidence=reasons)])
            result=visibility.prepare(archive,[entry],Path(folder)/"copies")
            self.assertEqual(0,result["sources"][0]["knownEmptyReasonFrames"])
            self.assertEqual(line+";false;?;?;?;?\n",(Path(folder)/"copies/source.tape").read_text())

    def test_invalid_or_duplicate_source_names_cannot_select_other_output_paths(self):
        for name in ["../escape","other/source","different"]:
            with tempfile.TemporaryDirectory() as folder:
                archive,entry,_=self.fixture(folder,[self.frame()]); entry["session"]=name
                with self.assertRaises(ValueError): visibility.prepare(archive,[entry],Path(folder)/"copies")
        with tempfile.TemporaryDirectory() as folder:
            archive,entry,_=self.fixture(folder,[self.frame()])
            with self.assertRaises(ValueError): visibility.prepare(archive,[entry,entry],Path(folder)/"copies")


if __name__=="__main__": unittest.main()
