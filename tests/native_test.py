import importlib.util, io, json, os, pathlib, tempfile, unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('host',pathlib.Path(__file__).resolve().parents[1]/'companion/host.py');host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
class NativeTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();host.STATE=pathlib.Path(self.temp.name);host.JOBS=host.STATE/'jobs';host.CONFIG=host.STATE/'config.json';host.initialise()
 def tearDown(self):self.temp.cleanup()
 def test_urls(self):
  for url in ['file:///etc/passwd','javascript:alert(1)','https://user:pass@site.test','https://site.test/\nvalue',None]:
   with self.assertRaises(ValueError):host.valid_url(url)
  self.assertEqual(host.valid_url('https://cdn.test/a.m3u8?sig=abc'),'https://cdn.test/a.m3u8?sig=abc')
 def test_job_traversal(self):
  for value in ['../config','../../etc/passwd','a'*33,None]:
   with self.assertRaises(ValueError):host.job_path(value)
 def test_quality(self):
  self.assertEqual(host.quality_selector('720'),'bv*[height<=?720]+ba/b[height<=?720]')
  with self.assertRaises(ValueError):host.quality_selector('--exec malicious')
  self.assertEqual(host.quality_selector('height:144'),'bv*[height=144]+ba/b[height=144]/bv*[height=144]')
  self.assertNotIn('<=',host.quality_selector('height:1080'))
 def test_one_row_per_real_resolution(self):
  formats=[{'height':h,'vcodec':'h264','acodec':'aac','filesize':1} for h in [1080,720,480,240,144,1080,720,480,240]]
  rows=host.resolution_options(formats)
  self.assertEqual([r['height'] for r in rows],[1080,720,480,240,144])
  self.assertNotIn(360,[r['height'] for r in rows])
 def test_main_player_instead_of_first_preroll(self):
  entries=[{'id':'advert','duration':30},{'id':'main','duration':1042},{'id':'recommendation','duration':2000}]
  self.assertEqual(host.select_main_entry(entries,{'duration':1042.257})['id'],'main')
  self.assertEqual(host.select_main_entry(entries,video_id='main')['id'],'main')
 def test_video_id_mismatch_is_rejected(self):
  class Fake:
   def extract_info(self,*args,**kwargs):return {'id':'different','formats':[]}
   def process_ie_result(self,info,download=False):return info
  with self.assertRaises(ValueError):host.resolve_main(Fake(),'https://site.test/watch',video_id='wanted')
 def test_main_hls_manifest_matches_player(self):
  entries=[{'id':'advert','formats':[{'url':'https://cdn.test/ad.mp4'}]},
           {'id':'main','formats':[{'url':'https://cdn.test/360.m3u8','manifest_url':'https://cdn.test/master.m3u8'}]}]
  self.assertEqual(host.select_main_entry(entries,{'src':'https://cdn.test/master.m3u8'})['id'],'main')
 def test_private_job_history(self):
  job={'id':'a'*32,'url':'https://site.test/a?token=private','referer':'https://site.test','title':'Fixture','status':'queued','created':1}
  host.save(job);item=host.jobs()[0];self.assertNotIn('url',item);self.assertNotIn('referer',item);self.assertEqual(host.job_path(job['id']).stat().st_mode&0o777,0o600)
 def test_error_redaction(self):
  self.assertNotIn('secret',host.clean_error('failed https://site.test/video?token=secret after request'))
 def test_protocol_short_reads(self):
  class Tiny(io.BytesIO):
   def read(self,n=-1):return super().read(min(2,n))
  self.assertEqual(host.read_exact(Tiny(b'abcdef'),6),b'abcdef');self.assertIsNone(host.read_exact(Tiny(b'ab'),3))
 def test_clear_preserves_files_and_running_jobs(self):
  saved=host.STATE/'saved.mp4';saved.write_bytes(b'fixture')
  for index,status in enumerate(['complete','downloading']):host.save({'id':str(index)*32,'status':status,'created':index,'path':str(saved)})
  host.dispatch({'command':'clear'});self.assertTrue(saved.exists());self.assertEqual(len(host.jobs()),1)
 def test_batch_is_validated_before_any_download_is_queued(self):
  with patch.object(host,'ensure_worker') as worker:
   with self.assertRaises(ValueError):host.start_many({'items':[{'url':'https://site.test/1.mp4'},{'url':'file:///bad'}]})
   self.assertEqual(host.jobs(),[]);worker.assert_not_called()
 def test_batch_queues_only_unique_selected_videos_and_starts_worker_once(self):
  with patch.object(host,'ensure_worker') as worker:
   result=host.start_many({'quality':'720','items':[{'url':'https://site.test/1.mp4'},{'url':'https://site.test/2.mp4'},{'url':'https://site.test/1.mp4'}]})
   self.assertEqual(len(result),2);self.assertEqual(len(host.jobs()),2);worker.assert_called_once()
   self.assertTrue(all('url' not in j and j['status']=='queued' for j in result))
 def test_cancelling_images_only_removes_that_jobs_partials(self):
  output=host.STATE/'output';folder=output/'Images'/'Pictures';folder.mkdir(parents=True)
  host.CONFIG.write_text(json.dumps({'output':str(output)}))
  mine=folder/('one.jpg.'+'a'*32+'.part');other=folder/('two.jpg.'+'b'*32+'.part');saved=folder/'done.jpg'
  for p in [mine,other,saved]:p.write_bytes(b'fixture')
  host.remove_partials({'id':'a'*32,'kind':'images','folder':'Pictures'})
  self.assertFalse(mine.exists());self.assertTrue(other.exists());self.assertTrue(saved.exists())
 def test_old_history_cannot_hide_a_queued_download(self):
  host.save({'id':'a'*32,'status':'queued','created':0})
  for n in range(110):host.save({'id':f'{n:032x}','status':'complete','created':n+1})
  self.assertEqual(len([j for j in host.jobs() if j['status']=='queued']),1)
 def test_upright_quality_labels_use_the_short_side(self):
  rows=host.resolution_options([{'height':1920,'width':1080,'vcodec':'h264','acodec':'aac'}])
  self.assertEqual(rows[0]['label'],'1080p');self.assertEqual(rows[0]['quality'],'height:1920')
 def test_image_batches_cannot_overwrite_each_others_files(self):
  with patch.object(host,'ensure_worker'):
   a=host.start_images({'folder':'Pictures','items':[{'url':'https://site.test/a.jpg'}]})
   b=host.start_images({'folder':'Pictures','items':[{'url':'https://site.test/a.jpg'}]})
  self.assertNotEqual(a['folder'],b['folder'])
if __name__=='__main__':unittest.main()
