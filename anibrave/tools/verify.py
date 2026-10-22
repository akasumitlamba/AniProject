"""Verify a signed full-engine build against its pinned official input and feature sources."""
from pathlib import Path
import argparse,subprocess,zipfile,hashlib,json,struct,zlib,re
ROOT=Path(__file__).resolve().parents[2];BASE=ROOT/'anibrave'
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  while block:=stream.read(1024*1024):h.update(block)
 return h.hexdigest()
def digest(z,name):
 h=hashlib.sha256()
 with z.open(name) as stream:
  while block:=stream.read(1024*1024):h.update(block)
 return h.hexdigest()
def main():
 pin=json.loads((BASE/'upstream.json').read_text());version=json.loads((BASE/'version.json').read_text());p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--input',type=Path,required=True);p.add_argument('--upstream',type=Path,required=True);p.add_argument('--feature-dex',type=Path);p.add_argument('--previous',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--build-tools',type=Path,default=ROOT/'.tools/sdk/build-tools/37.0.0');args=p.parse_args()
 assert sha(args.upstream)==pin['sha256'],'Upstream input is not pinned APK'
 with zipfile.ZipFile(args.input) as app,zipfile.ZipFile(args.upstream) as upstream:
  native=[n for n in upstream.namelist() if n.startswith('lib/') and n.endswith('.so')]
  assert native and all(digest(app,n)==digest(upstream,n) for n in native),'Native engine mismatch'
  dex=[n for n in app.namelist() if re.fullmatch(r'classes\d*\.dex',n)];assert len(dex)==8
  for name in dex:
   data=app.read(name);assert data[:4]==b'dex\n' and data[12:32]==hashlib.sha1(data[32:]).digest() and struct.unpack_from('<I',data,8)[0]==zlib.adler32(data[12:])&0xffffffff,name
  if args.feature_dex:assert app.read('classes8.dex')==args.feature_dex.read_bytes()
  for js in ['playback.js','player.js','tracking.js','continuation.js']:assert app.read('assets/anibrave/'+js)==(BASE/'src'/js).read_bytes()
  assert app.getinfo('resources.arsc').compress_type==0
  assert app.testzip() is None
 badging=subprocess.check_output([str(args.build_tools/'aapt2'),'dump','badging',str(args.input)],text=True)
 assert "name='app.anibrave.main'" in badging and "versionCode='"+str(version['code'])+"'" in badging and "versionName='"+version['name']+'-brave-'+pin['tag'][1:]+"'" in badging
 assert "native-code: 'arm64-v8a'" in badging
 def cert(apk):
  result=subprocess.check_output([str(args.build_tools/'apksigner'),'verify','--print-certs',str(apk)],text=True)
  return re.search(r'certificate SHA-256 digest: (\w+)',result).group(1)
 certificate=cert(args.input)
 if args.previous:assert certificate==cert(args.previous),'Signing identity changed'
 subprocess.run([str(args.build_tools/'zipalign'),'-c','-P','16','4',str(args.input)],check=True)
 report={'package':'app.anibrave.main','version':version['name'],'versionCode':version['code'],'brave':pin['tag'][1:],'upstreamSha256':pin['sha256'],'architecture':'arm64-v8a','minimumAndroid':10,'bytes':args.input.stat().st_size,'sha256':sha(args.input),'certificateSha256':certificate,'unchangedNativeLibraries':len(native),'verifiedDexFiles':len(dex),'validationScope':'Package integrity and original signing identity; runtime acceptance is documented separately.'}
 args.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
