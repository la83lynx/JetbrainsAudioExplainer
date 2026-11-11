# -*- coding: utf-8 -*-
import io
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

fails = 0


def note(ok, label, detail=''):
    global fails
    if not ok:
        fails += 1
    print(('PASS  ' if ok else 'FAIL  ') + label + (('   [' + detail + ']') if detail else ''))


# 1. serializer gate ---------------------------------------------------------
hits = []
for root, _, files in os.walk('build/classes/kotlin/main'):
    for f in files:
        if f.endswith('.class'):
            b = io.open(os.path.join(root, f), 'rb').read()
            if b'noCompiledSerializer' in b:
                hits.append(os.path.join(root, f))
note(not hits, 'serializer gate: no noCompiledSerializer call sites', str(len(hits)) + ' hits')

# 2. plugin.xml parses, and every class reference resolves -------------------
px = 'src/main/resources/META-INF/plugin.xml'
tree = ET.parse(px)
refs = set()
for el in tree.iter():
    for a in ('implementation', 'class', 'instance', 'serviceImplementation', 'interface',
              'implementationClass', 'topic', 'factoryClass'):
        v = el.get(a)
        if v and '.' in v and not v.startswith('com.intellij'):
            refs.add(v)
missing = [r for r in sorted(refs)
           if not os.path.exists('build/classes/kotlin/main/' + r.replace('.', '/') + '.class')]
note(not missing, 'plugin.xml: every class reference resolves',
     '%d/%d resolve' % (len(refs) - len(missing), len(refs))
     + ('  MISSING ' + ','.join(missing) if missing else ''))

# 3. every resource XML parses ----------------------------------------------
bad = []
n = 0
for root, _, files in os.walk('src/main/resources'):
    for f in files:
        if f.endswith('.xml'):
            n += 1
            try:
                ET.parse(os.path.join(root, f))
            except Exception as e:
                bad.append(f + ': ' + str(e))
note(not bad, 'every resource XML parses', '%d files' % n + ('  ' + '; '.join(bad) if bad else ''))

# 4. no mixed line endings in tracked text ----------------------------------
mixed = []
for root, dirs, files in os.walk('.'):
    dirs[:] = [d for d in dirs
               if d not in ('.git', 'build', '.gradle', '.intellijPlatform', 'scratchpad', '.idea')]
    for f in files:
        if os.path.splitext(f)[1] in ('.kt', '.java', '.json', '.md', '.xml', '.py', '.ps1', '.txt'):
            p = os.path.join(root, f)
            try:
                b = io.open(p, 'rb').read()
            except Exception:
                continue
            crlf = b.count(b'\r\n')
            lf = b.count(b'\n') - crlf
            if crlf and lf:
                mixed.append(p)
note(not mixed, 'no mixed line endings', str(len(mixed)) + ' files'
     + ('  ' + ', '.join(mixed[:5]) if mixed else ''))

# 5. the skill manifest lists every file it ships ---------------------------
skill = 'src/main/resources/claude-skill'
sep = chr(92)
listed = set()
mf = os.path.join(skill, 'manifest.txt')
if os.path.exists(mf):
    for line in io.open(mf, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#'):
            listed.add(line.split()[0])
on_disk = set()
for root, _, files in os.walk(skill):
    for f in files:
        rel = os.path.relpath(os.path.join(root, f), skill).replace(sep, '/')
        if rel.lower() != 'manifest.txt':
            on_disk.add(rel)
note(not listed or listed == on_disk, 'skill manifest matches disk',
     '%d listed / %d on disk' % (len(listed), len(on_disk))
     + ('  only-disk=' + ','.join(sorted(on_disk - listed)) if listed and on_disk - listed else '')
     + ('  only-manifest=' + ','.join(sorted(listed - on_disk)) if listed and listed - on_disk else ''))

# 6. VERSION -----------------------------------------------------------------
# Not a pinned literal: the rule is *if the skill changed, VERSION changed*. Pinning the expected
# number makes this gate fail on every legitimate bump, which is how a gate teaches people to ignore it.
v = io.open(os.path.join(skill, 'VERSION'), encoding='utf-8').read().strip()
ok_semver = len(v.split('.')) == 3 and all(part.isdigit() for part in v.split('.'))
was = subprocess.run(['git', 'show', 'HEAD:src/main/resources/claude-skill/VERSION'],
                     capture_output=True, text=True)
prev = was.stdout.strip() if was.returncode == 0 else None
changed = subprocess.run(['git', 'diff', '--name-only', 'HEAD', '--', skill],
                         capture_output=True, text=True).stdout.split()
touched = [f for f in changed if not f.endswith('/VERSION')]
if prev is None:
    note(ok_semver, 'skill VERSION is semver', v)
elif touched:
    note(ok_semver and tuple(int(x) for x in v.split('.')) > tuple(int(x) for x in prev.split('.')),
         'skill VERSION bumped for the ' + str(len(touched)) + ' changed skill file(s)',
         prev + ' -> ' + v)
else:
    note(ok_semver and v == prev, 'skill VERSION unchanged, and so is the skill', v)

# 7. markdown fences balanced ------------------------------------------------
unbalanced = []
docs = ['README.md', 'probes/README.md', os.path.join(skill, 'docs/plan-schema.md')]
# Plan docs are checked while they are in the tree; they do not ship, so their absence is not a failure.
for f in docs:
    t = io.open(f, encoding='utf-8').read()
    if t.count('\n```') % 2:
        unbalanced.append(f)
note(not unbalanced, 'markdown fences balanced',
     '%d files' % len(docs) + ('  ' + ','.join(unbalanced) if unbalanced else ''))

# 8. one destination for a plan ----------------------------------------------
# A plan has exactly one destination, the tree. The deleted `view` field once picked between the tree and
# an in-place spotlight; a second destination would now come back as a second listener that also opens
# something. So: exactly one place constructs the tree controller, exactly one registers it, and no source
# names the dead field.
#
# The control is built in: the same sweep counts the *registration*, which must also be 1. A check that
# only counted constructions would pass on a controller built and never subscribed — a tree that no plan
# reaches, which is the same defect read from the other end.
kt = []
for base, dirs, names in os.walk(os.path.join('src', 'main', 'kotlin')):
    for n in names:
        if n.endswith('.kt'):
            kt.append(os.path.join(base, n))
built, registered, revived = [], [], []
for f in kt:
    t = io.open(f, encoding='utf-8').read()
    for i, line in enumerate(t.splitlines(), 1):
        # Comments are skipped for the revival check and only for it. Both surviving mentions of the dead
        # field are KDoc explaining that it was deleted and why — which is exactly the record this project
        # keeps on purpose, so a gate that failed on it would be teaching people to delete the reasoning.
        code = not line.lstrip().startswith(('*', '//', '/*'))
        if 'TreePlanController(' in line and 'class TreePlanController' not in line:
            built.append('%s:%d' % (f, i))
        if 'addListener(tree' in line:
            registered.append('%s:%d' % (f, i))
        if code and ('wantsTree' in line or 'Plan.view' in line):
            revived.append('%s:%d' % (f, i))
note(len(built) == 1 and len(registered) == 1 and not revived,
     'one destination: the tree controller is built once and registered once',
     '%d built / %d registered / %d refs to the dead view field' % (len(built), len(registered), len(revived)))

print()
print('gate failures: %d' % fails)
sys.exit(1 if fails else 0)
