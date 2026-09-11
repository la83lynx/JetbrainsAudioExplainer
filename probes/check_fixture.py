# -*- coding: utf-8 -*-
"""Every relation endpoint verified against the file it points at, and the per-step link count under BOTH
the round-7 pair key and the round-8 one — which is the direct evidence for item 1."""
import io
import json
import os
import sys

ROOT = 'fixtures/TreeSpike'
MAX_SPAN = 40
CAP = 5
bad = 0


def src(path):
    return io.open(os.path.join(ROOT, path), encoding='utf-8-sig', errors='replace').read().split('\n')


def check_endpoint(plan_name, file, end, where):
    global bad
    lines = src(file)
    ln = end.get('line')
    if ln is None or ln < 1 or ln > len(lines):
        print('  BAD %s %s: line %s outside 1..%d' % (plan_name, where, ln, len(lines)))
        bad += 1
        return
    tok = end.get('token')
    if not tok:
        return
    occ = end.get('occurrence', 1)
    text = lines[ln - 1]
    n, i = 0, -1
    while True:
        i = text.find(tok, i + 1)
        if i < 0:
            break
        n += 1
        if n == occ:
            return
    print('  BAD %s %s: %r occurrence %d not on %s:%d  (%d found)'
          % (plan_name, where, tok, occ, os.path.basename(file), ln, n))
    bad += 1


def scan(path):
    name = os.path.basename(path)
    plan = json.load(io.open(path, encoding='utf-8'))
    frames = {f['id']: f for f in plan['frames']}
    print('\n%s' % name)
    for f in plan['frames']:
        for i, r in enumerate(f.get('relations', [])):
            for side in ('from', 'to'):
                e = r[side]
                check_endpoint(name, e.get('file') or f['file'], e, 'frame %d rel %d %s' % (f['id'], i, side))
            for a in f.get('keep', []):
                pass
    # keep coverage: every endpoint that names this frame's own file must land on a kept line
    for f in plan['frames']:
        kept = set()
        for k in f.get('keep', []):
            kept.update(range(k['start'], k['end'] + 1))
        for i, r in enumerate(f.get('relations', [])):
            for side in ('from', 'to'):
                e = r[side]
                if (e.get('file') or f['file']) != f['file']:
                    continue
                if e['line'] not in kept:
                    print('  BAD %s frame %d rel %d %s: line %d is not kept' % (name, f['id'], i, side, e['line']))
                    globals().__setitem__('bad', globals()['bad'] + 1)

    for si, step in enumerate(plan['steps']):
        fid, rngs = step.get('frame'), step.get('ranges') or []
        if fid is None or not rngs:
            continue
        got = []
        # ONLY the step's own frame declares links for it: every other node is painted inactive, so its
        # resolveRelations sees no active range and produces nothing. The sandbox log is the authority for
        # that — step 1 of link-fan reported 4 links, which is its own five deduped, not five plus the
        # portals other frames aim at it.
        for f in [frames[fid]]:
            for r in f.get('relations', []):
                for near, far in ((r['from'], r['to']), (r['to'], r['from'])):
                    nf = near.get('file') or f['file']
                    if nf != frames[fid]['file']:
                        continue
                    if not any(a['start'] <= near['line'] <= a['end'] for a in rngs):
                        continue
                    ff = far.get('file') or f['file']
                    old = tuple(sorted([(nf, near['line']), (ff, far['line'])]))
                    new = tuple(sorted([
                        (nf, near['line'], near.get('token') or '', near.get('occurrence', 1)),
                        (ff, far['line'], far.get('token') or '', far.get('occurrence', 1))]))
                    join = 'LINE' if (ff == nf and abs(far['line'] - near['line']) <= MAX_SPAN) else 'PORTAL'
                    got.append((old, new, join))
                    break
        n7 = len({g[0] for g in got})
        seen, uniq = set(), []
        for g in got:
            if g[1] in seen:
                continue
            seen.add(g[1])
            uniq.append(g)
        n8 = len(uniq)
        layout = ''.join(g[2][0] for g in sorted(uniq[:CAP], key=lambda g: g[2] == 'PORTAL'))
        flag = '   <-- item 1: round 7 dropped %d' % (n8 - n7) if n8 != n7 else ''
        print('  step %d  round7 %d link(s)   round8 %d link(s)   lanes %s%s'
              % (si + 1, min(n7, CAP), min(n8, CAP), layout, flag))


for p in sys.argv[1:]:
    scan(p)
print('\nbad endpoints: %d' % bad)
sys.exit(1 if bad else 0)
