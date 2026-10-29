#!/usr/bin/env python3
"""Compatibility entry for the historical lab; native preparation is now shared."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parents[2] / 'scripts' / 'prepare-airplay-native.py'), run_name='__main__')
