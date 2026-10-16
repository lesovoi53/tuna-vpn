"""Persistent writable cache outside the one-file executable extraction folder."""
import os
import sys
from pathlib import Path


def geo_cache_dir():
    if getattr(sys, 'frozen', False):
        base = Path(os.environ.get('LOCALAPPDATA', str(Path.home() / 'AppData' / 'Local')))
        return base / 'TunaRoutingBuilder' / 'geo-cache'
    return Path(__file__).resolve().parent / 'geo-cache'
