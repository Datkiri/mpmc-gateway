# -*- coding: utf-8 -*-
"""Analyse a -Dmpmc.audit=true gateway log and produce a packet-coverage report.

Usage: python audit-report.py <gateway.log> [more.log ...]
"""
import re
import sys
from collections import Counter, defaultdict

LINE = re.compile(r'\[AUDIT\]\s+(\S+)\s+(.*)$')
FIELDS = re.compile(r'(\w+)=([^\s]+)')

ERROR_PATTERNS = [
    r'ERROR IN ', r'Exception', r'PacketMalformed', r'Packet violation',
    r'Failed to replay deferred packet', r'outside PLAY state',
    r'Received unknown packet', r'Unable to read type', r'REMAP OF ',
    r'UNCLASSIFIED', r'SEVERE', r'PacketViolationWarning',
]


def parse(path):
    recs = []
    errors = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for raw in f:
            m = LINE.search(raw)
            if m:
                rec = {'phase': m.group(1)}
                rec.update(dict(FIELDS.findall(m.group(2))))
                recs.append(rec)
            else:
                for p in ERROR_PATTERNS:
                    if re.search(p, raw):
                        errors.append(raw.strip())
                        break
    return recs, errors


def main(paths):
    all_recs, all_errors = [], []
    for p in paths:
        r, e = parse(p)
        all_recs.extend(r)
        all_errors.extend(e)
        print('log: %s  audit_lines=%d  error_lines=%d' % (p, len(r), len(e)))
    print()

    phases = Counter(x['phase'] for x in all_recs)
    print('PHASES:', dict(sorted(phases.items())))

    cb_pre = [x for x in all_recs if x['phase'] == 'PRE' and x.get('dir') == 'CLIENTBOUND']
    sb_pre = [x for x in all_recs if x['phase'] == 'PRE' and x.get('dir') == 'SERVERBOUND']
    print('clientbound PRE = %d, serverbound PRE = %d' % (len(cb_pre), len(sb_pre)))

    # Every PRE must have exactly one terminal phase (POST / CANCEL / DEFERRED).
    terminal = phases['POST'] + phases['CANCEL'] + phases['DEFERRED']
    print('terminal lines  = %d  (POST %d + CANCEL %d + DEFERRED %d)' % (
        terminal, phases['POST'], phases['CANCEL'], phases['DEFERRED']))
    print('PRE vs terminal = %s' % ('OK' if terminal == len(cb_pre) + len(sb_pre) else 'MISMATCH'))
    print()

    # ---- clientbound coverage ----
    cb_names = Counter(x.get('name', '?') for x in cb_pre)
    cb_unhandled = sorted({x.get('name', '?') for x in cb_pre if x.get('hasHandler') == 'false'})
    cb_unknown = sorted({x.get('name', '?') for x in cb_pre if x.get('name', '').startswith('UNKNOWN_BEDROCK_ID')})
    print('CLIENTBOUND distinct Bedrock packets observed = %d' % len(cb_names))
    for n, c in sorted(cb_names.items()):
        print('  %-45s %d' % (n, c))
    print('  hasHandler=false at PRE : %s' % (cb_unhandled or 'none'))
    print('  UNKNOWN_BEDROCK_ID at PRE: %s' % (cb_unknown or 'none'))
    print()

    reasons = Counter()
    for x in all_recs:
        if x['phase'] == 'CLASSIFIED' and 'reason' in x:
            reasons[(x.get('dir', '?'), x.get('name', '?'), x['reason'])] += 1
    print('CLASSIFIED dispositions = %d lines, %d distinct' % (sum(reasons.values()), len(reasons)))
    for (d, n, r), c in sorted(reasons.items()):
        print('  %-11s %-42s %-42s %d' % (d, n, r, c))
    print()

    # ---- serverbound (host side) ----
    sb_names = Counter(x.get('name', '?') for x in sb_pre)
    sb_post = [x for x in all_recs if x['phase'] == 'POST' and x.get('dir') == 'SERVERBOUND']
    sb_to_host = [x for x in sb_post if x.get('toHost') == 'true']
    sb_unmapped = [x for x in sb_post if x.get('unmapped') == 'true']
    print('SERVERBOUND distinct Java packets observed = %d' % len(sb_names))
    for n, c in sorted(sb_names.items()):
        print('  %-45s %d' % (n, c))
    print('  POST toHost=true : %d' % len(sb_to_host))
    print('  POST unmapped=true: %d  %s' % (len(sb_unmapped), 'OK' if not sb_unmapped else 'FAIL'))
    host_names = Counter(x.get('outName', '?') for x in sb_to_host)
    print('  distinct Bedrock packet names written to host = %d' % len(host_names))
    for n, c in sorted(host_names.items()):
        print('    %-45s %d' % (n, c))
    print()

    # ---- hard failures ----
    err_phase = [x for x in all_recs if x['phase'] == 'ERROR']
    err_lines = [x for x in all_recs if x.get('error') == 'true']
    print('ERROR phase lines        = %d' % len(err_phase))
    print('error=true lines         = %d' % len(err_lines))
    print('non-audit error lines    = %d' % len(all_errors))
    for e in all_errors[:20]:
        print('  ! ' + e)
    print()

    ok = (terminal == len(cb_pre) + len(sb_pre) and not cb_unhandled and not cb_unknown
          and not sb_unmapped and not err_phase and not err_lines and not all_errors)
    print('VERDICT: %s' % ('PASS - 100% accounted, zero errors' if ok else 'FAIL - review above'))
    return 0 if ok else 1


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
