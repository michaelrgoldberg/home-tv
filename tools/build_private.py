#!/usr/bin/env python3
"""Builds the encrypted private.json for the TV welcome screen.
Inputs: earnings history JSON, family calendar events JSON (list_events output),
and the AES key (base64, 32 bytes). Output is AES-GCM encrypted; only the TV (which holds the key) can read it.
usage: build_private.py <pn_history.json> <calendar.json> <keyfile> <out.json> [config.json] [mike_cal.json]"""
import json, sys, base64, os, re, datetime
from zoneinfo import ZoneInfo
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

TZ = ZoneInfo('America/New_York')
ROUTINE = re.compile(r'\bbus\b|buses|recycling collection|garbage', re.I)  # extra patterns come from the encrypted config
NOTABLE = [(r'flight|fly|airport', '✈️'), (r'trip|vacation|hotel|resort|cruise|getaway', '🧳'), (r'birthday', '🎂'),
           (r'no school|early dismissal|conference', '🏫'), (r'party|wedding|bar mitzvah(?! club)|bat mitzvah(?! club)|simcha|kiddush|celebrat', '🎉'),
           (r'chanukah|purim|pesach|sukkot|seder|yom tov', '🕎'), (r'doctor|dentist|appointment|dr\.', '🩺'),
           (r'game|match|soccer|basketball|baseball', '🏅'), (r'concert|music hall|show|movie', '🎶')]

def earnings(path, today):
    d = json.load(open(path))
    v = d.get('value', d.get('data', {}).get('value') if isinstance(d.get('data'), dict) else None)
    arr = json.loads(v) if isinstance(v, str) else (v or [])
    rev = {}
    for x in arr:
        try: rev[x['date']] = float(x.get('revenue') or 0)
        except Exception: pass
    def r(day): return rev.get(day.isoformat(), 0.0)
    y = today - datetime.timedelta(days=1)
    mon = today - datetime.timedelta(days=today.weekday())
    week = sum(r(mon + datetime.timedelta(days=i)) for i in range(7) if mon + datetime.timedelta(days=i) <= today)
    anchor = datetime.date(2026, 11, 2)
    ps = anchor + datetime.timedelta(days=((today - anchor).days // 14) * 14)
    pp = sum(r(ps + datetime.timedelta(days=i)) for i in range(14) if ps + datetime.timedelta(days=i) <= today)
    f = lambda dd: dd.strftime('%a') + ' ' + str(dd.month) + '/' + str(dd.day)
    lmon = mon - datetime.timedelta(days=7)
    last = sum(r(lmon + datetime.timedelta(days=i)) for i in range(7))
    return {'today': r(today), 'todayLabel': f(today), 'yesterday': r(y), 'yesterdayLabel': f(y),
            'weekDays': today.weekday() + 1, 'lastWeek': last, 'lastWeekRange': f(lmon) + '–' + f(lmon + datetime.timedelta(days=6)),
            'week': week, 'weekRange': f(mon) + '–' + f(mon + datetime.timedelta(days=6)),
            'pp': pp, 'ppRange': f(ps) + '–' + f(ps + datetime.timedelta(days=13))}

def events(path, now):
    t = open(path).read(); i = t.find('{'); j = json.loads(t[i:])
    out = []
    for e in j.get('events', []):
        if e.get('status') == 'cancelled': continue
        title = (e.get('summary') or '').strip()
        if not title or ROUTINE.search(title): continue
        s = e.get('start', {}); en = e.get('end', {})
        allday = 'date' in s
        start = s.get('date') or s.get('dateTime'); end = en.get('date') or en.get('dateTime')
        icon = next((ic for pat, ic in NOTABLE if re.search(pat, title, re.I)), '📅')
        notable = icon not in ('📅',) and icon != '🩺'
        out.append({'title': title, 'start': start, 'end': end, 'allDay': allday, 'icon': icon, 'notable': notable,
                    'where': (e.get('location') or '')[:60]})
    out.sort(key=lambda x: x['start'])
    return out

def whereabouts(path, today):
    """Map ET date -> 'home'/'office' from BMA-HOME / BMA-OFFICE events on the Mike Goldberg calendar."""
    out = {}
    try:
        t = open(path).read(); i = t.find('{'); j = json.loads(t[i:])
    except Exception:
        return out
    for e in j.get('events', []):
        if e.get('status') == 'cancelled': continue
        su = (e.get('summary') or '').upper()
        loc = 'home' if 'BMA-HOME' in su else ('office' if 'BMA-OFFICE' in su else None)
        if not loc: continue
        st = e.get('start', {}); dt = st.get('dateTime') or st.get('date')
        if not dt: continue
        if len(dt) == 10:
            d = dt
        else:
            try:
                x = datetime.datetime.fromisoformat(dt.replace('Z', '+00:00'))
                if x.tzinfo: x = x.astimezone(TZ)
                d = x.date().isoformat()
            except Exception:
                d = dt[:10]
        out[d] = loc
    return out

def main():
    global ROUTINE
    pn, cal, keyf, outp = sys.argv[1:5]
    key = base64.b64decode(open(keyf).read().strip())
    if len(sys.argv) > 5 and os.path.exists(sys.argv[5]):
        c = json.load(open(sys.argv[5]))
        cfg = json.loads(AESGCM(key).decrypt(base64.b64decode(c['iv']), base64.b64decode(c['ct']), None))
        if cfg.get('routine'): ROUTINE = re.compile(cfg['routine'], re.I)
    now = datetime.datetime.now(TZ)
    data = {'built': now.isoformat(timespec='minutes'), 'earnings': earnings(pn, now.date()), 'events': events(cal, now)}
    if len(sys.argv) > 6 and os.path.exists(sys.argv[6]):
        data['where'] = whereabouts(sys.argv[6], now.date())
    iv = os.urandom(12)
    ct = AESGCM(key).encrypt(iv, json.dumps(data, separators=(',', ':')).encode(), None)
    json.dump({'v': 1, 'iv': base64.b64encode(iv).decode(), 'ct': base64.b64encode(ct).decode()}, open(outp, 'w'))
    e = data['earnings']
    print('private.json written:', len(data['events']), 'events; today', e['today'], 'yday', e['yesterday'], 'week', e['week'], 'pp', e['pp'])

if __name__ == '__main__':
    main()
