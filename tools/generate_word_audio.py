"""Regenerate the selected Heart pack locally; requires Kokoro ONNX v1.0 and ffmpeg.

Install kokoro-onnx==0.6.1, then supply the two model-file paths on the command line.
The phone only receives the completed MP3s, never the model or an API key.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

os.environ.setdefault('OMP_NUM_THREADS', '2')
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT/'app/src/main/assets/pronunciation'
engine = None

def initialize(model, voices):
    global engine
    import onnxruntime as rt
    from kokoro_onnx import Kokoro
    opts = rt.SessionOptions()
    opts.intra_op_num_threads = 2
    opts.inter_op_num_threads = 1
    session = rt.InferenceSession(model, sess_options=opts, providers=['CPUExecutionProvider'])
    engine = Kokoro.from_session(session, voices)

def synthesize(item):
    import numpy as np
    import wave
    index,word = item
    audio, sr = engine.create(word, voice='af_heart', speed=0.95, lang='en-us')
    assert len(audio) and np.isfinite(audio).all(), word
    audio = np.pad(audio, (int(sr*0.1), int(sr*0.1)))
    name = f'{index+1:04d}.mp3'
    path = DEST/name
    with tempfile.TemporaryDirectory(prefix='heart-word-') as folder:
        wav=Path(folder)/'word.wav'
        with wave.open(str(wav),'wb') as f:
            f.setnchannels(1)
            f.setsampwidth(2)
            f.setframerate(sr)
            f.writeframes((np.clip(audio,-1,1)*32767).astype('<i2').tobytes())
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(wav),
            '-af','loudnorm=I=-18:TP=-1.5:LRA=7','-ar','24000','-ac','1',
            '-codec:a','libmp3lame','-b:a','96k',str(path)], check=True)
    return {'word':word,'file':name,'durationMs':round(len(audio)*1000/sr),
            'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def main():
    words=[w['word'] for w in json.loads((ROOT/'app/src/main/assets/vocabulary.json').read_text())['words']]
    assert len(words)==1640 and len(set(words))==1640
    DEST.mkdir(parents=True,exist_ok=True)
    start=time.monotonic()
    with concurrent.futures.ProcessPoolExecutor(max_workers=4,initializer=initialize,initargs=(sys.argv[1],sys.argv[2])) as pool:
        entries=[]
        for i,entry in enumerate(pool.map(synthesize, enumerate(words)),1):
            entries.append(entry)
            if i%100==0 or i==len(words):print(f'{i}/{len(words)} words, {time.monotonic()-start:.1f}s',flush=True)
    manifest={'format':'context-english-pronunciation','version':2,'locale':'en-US','voice':'Heart',
        'voiceId':'af_heart','source':'Kokoro-82M v1.0','kind':'AI synthesized speech; not human recordings',
        'speed':0.95,'wordCount':len(entries),'entries':entries}
    (DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    (DEST/'NOTICE.txt').write_text('Heart American English offline pronunciation pack\n\n'
        'All 1,640 clips synthesized locally using hexgrad/Kokoro-82M v1.0, voice af_heart, speed 0.95.\n'
        'These are AI-generated pronunciations, not human dictionary recordings.\n'
        'The application plays completed audio offline without an API or model on the phone.\n\n'
        'Model: https://huggingface.co/hexgrad/Kokoro-82M (Apache License, Version 2.0)\n'
        'Runtime: https://github.com/thewh1teagle/kokoro-onnx (MIT)\n\n'
        'Existing vocabulary, phonetics, meanings and example attributions remain unchanged.\n')
    print('COMPLETE',sum((DEST/e['file']).stat().st_size for e in entries),'bytes',flush=True)

if __name__=='__main__': main()
