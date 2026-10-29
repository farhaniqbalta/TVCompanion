#!/usr/bin/env python3
"""Host-side deterministic media checks. No TV, network, private data or benchmarks."""
from pathlib import Path
import os
import sys
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
module = root / 'android/integrations/uxplay-native'
codec = module / '.native/alac/codec'
if not codec.exists():
    raise SystemExit('Prepare native dependencies first with scripts/prepare-airplay-native.py')
endian_flag = '-DTARGET_RT_LITTLE_ENDIAN=' + ('1' if sys.byteorder == 'little' else '0')
sources = ['ALACEncoder.cpp', 'ALACDecoder.cpp', 'ALACBitUtilities.c', 'EndianPortable.c',
           'ag_dec.c', 'ag_enc.c', 'dp_dec.c', 'dp_enc.c', 'matrix_dec.c', 'matrix_enc.c']
with tempfile.TemporaryDirectory(prefix='tvcompanion-native-check-') as temporary:
    binary = Path(temporary) / 'media-path-check'
    objects = []
    for source in sources:
        if not source.endswith('.c'):
            continue
        output = Path(temporary) / (source + '.o')
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c11', '-Wno-multichar', endian_flag,
                        '-I' + str(codec), '-c', str(codec / source), '-o', str(output)], check=True)
        objects.append(str(output))
    command = [os.environ.get('CXX', 'c++'), '-std=c++17', '-Wno-multichar', endian_flag,
               '-I' + str(codec), '-I' + str(module / 'src/main/cpp'),
               str(module / 'src/test/cpp/media_path_test.cpp'),
               *[str(codec / s) for s in sources if s.endswith('.cpp')], *objects, '-o', str(binary)]
    subprocess.run(command, check=True)
    subprocess.run([str(binary)], check=True)
