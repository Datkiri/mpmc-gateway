# -*- coding: utf-8 -*-
"""Static packet-coverage audit of the patched ViaBedrock fork.

Comments are stripped before matching: ViaBedrock contains commented-out
cancelClientbound() lines that must NOT be counted as real registrations.
"""
import re
from pathlib import Path

ROOT = (Path(__file__).resolve().parent / 'gateway-build' / 'ViaBedrock' / 'src'
        / 'main' / 'java' / 'net' / 'raphimc' / 'viabedrock' / 'protocol')


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return '\n'.join(re.sub(r'//.*$', '', line) for line in text.splitlines())


def enum_names(filename):
    text = strip_comments((ROOT / filename).read_text(encoding='utf-8'))
    return re.findall(r'^\s{4}([A-Z][A-Z0-9_]*)\(MinecraftPacketIds\.', text, re.M)


cb = enum_names('ClientboundBedrockPackets.java')
sb = enum_names('ServerboundBedrockPackets.java')

mapped_cb = {}
cancel_cb = {}
mapped_sb = {}
fallback_hits = []

for path in ROOT.rglob('*.java'):
    text = strip_comments(path.read_text(encoding='utf-8'))
    for m in re.finditer(r'registerClientbound(?:Transition)?\(ClientboundBedrockPackets\.([A-Z0-9_]+)', text):
        mapped_cb.setdefault(m.group(1), []).append(path.name + ('(transition)' if 'Transition' in m.group(0) else ''))
    for m in re.finditer(r'cancelClientbound\(ClientboundBedrockPackets\.([A-Z0-9_]+)', text):
        cancel_cb.setdefault(m.group(1), []).append(path.name)
    for m in re.finditer(r'registerServerbound\(ServerboundPackets26_3\.([A-Z0-9_]+)', text):
        mapped_sb.setdefault(m.group(1), []).append(path.name)
    if 'cancelClientbound(packet)' in text or 'classifyClientbound(packet' in text:
        fallback_hits.append(path.name)

# Packets listed in CLIENTBOUND_DISPOSITIONS get an explicit, audited handler
# (classifyClientbound) rather than a silent generic fallback.
bp_text = strip_comments((ROOT / 'BedrockProtocol.java').read_text(encoding='utf-8'))
disp_start = bp_text.find('buildDispositions()')
disp_end = bp_text.find('return Collections.unmodifiableMap(m)', disp_start)
disp_body = bp_text[disp_start:disp_end] if disp_start >= 0 and disp_end > disp_start else ''
classified_cb = set(re.findall(r'ClientboundBedrockPackets\.([A-Z0-9_]+)', disp_body))

handled = set(mapped_cb) | set(cancel_cb) | classified_cb
fallback_cb = [n for n in cb if n not in handled]
dup_cb = {k: v for k, v in mapped_cb.items() if len(v) > 1}

# Java 26.3 serverbound enum, extracted from viaversion-common with javap.
java_sb_file = Path(__file__).resolve().parent / 'java-serverbound-26_3.txt'
java_sb = java_sb_file.read_text(encoding='utf-8').split() if java_sb_file.exists() else []
unmapped_java_sb = [n for n in java_sb if n not in mapped_sb]
classified_sb = any('classifyServerbound(packet' in strip_comments(
    (ROOT / p).read_text(encoding='utf-8')) for p in ['BedrockProtocol.java'])

print('CLIENTBOUND enum total = %d' % len(cb))
print('  translated handlers  = %d' % len(mapped_cb))
print('  explicit cancels     = %d' % len(cancel_cb))
print('  classified dispositions = %d' % len(classified_cb))
print('  implicit FALLBACK    = %d  (must be 0)' % len(fallback_cb))
print('  accounted total      = %d' % (len(mapped_cb) + len(cancel_cb) + len(classified_cb) + len(fallback_cb)))
print('  duplicate handlers   = %s' % (dup_cb or 'none'))
print('  classification source = %s' % fallback_hits)
print()
print('--- IMPLICIT FALLBACK LIST (must be empty) ---')
for n in fallback_cb:
    print('  %s' % n)
print()
print('--- CLASSIFIED DISPOSITION LIST ---')
for n in sorted(classified_cb):
    print('  %s' % n)
print()
print('--- EXPLICIT CANCEL LIST (clientbound) ---')
for n in sorted(cancel_cb):
    print('  %-45s %s' % (n, ','.join(cancel_cb[n])))
print()
print('BEDROCK serverbound enum (informational) = %d' % len(sb))
print()
print('JAVA 26.3 serverbound packet types = %d' % len(java_sb))
print('  translated to a Bedrock packet    = %d' % len(mapped_sb))
print('  explicitly classified (cancelled) = %d' % len(unmapped_java_sb))
print('  accounted total                   = %d' % (len(mapped_sb) + len(unmapped_java_sb)))
print('  classifyServerbound loop present  = %s' % classified_sb)
print()
print('--- Java serverbound packets explicitly classified ---')
for n in unmapped_java_sb:
    print('  %s' % n)
