#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 samples/课表-王梓俊.json 打成 demo-web/data.js 供离线单文件演示使用。"""
import io, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
src = os.path.join(ROOT, 'samples', '课表-王梓俊.json')
out = os.path.join(ROOT, 'demo-web', 'data.js')
os.makedirs(os.path.dirname(out), exist_ok=True)
with io.open(src, encoding='utf-8') as f:
    doc = json.load(f)
# 终端中文文件名差异处理：直接读 samples 目录下的 json
if not os.path.exists(src):
    cands = [p for p in os.listdir(os.path.join(ROOT, 'samples')) if p.endswith('.json')]
    if cands:
        src = os.path.join(ROOT, 'samples', cands[0])
        with io.open(src, encoding='utf-8') as f:
            doc = json.load(f)
with io.open(out, 'w', encoding='utf-8') as f:
    f.write('window.SCHEDULE_JSON = ')
    json.dump(doc, f, ensure_ascii=False, separators=(',', ':'))
    f.write(';\n')
print('demo data written:', out, os.path.getsize(out), 'bytes')
