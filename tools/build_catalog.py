#!/usr/bin/env python3
"""Builds catalog.json for the TV "What to Watch" page.
Source: JustWatch public GraphQL (US). Only titles included with the family's services
(subscription / free / with-ads), with IMDb scores, cast, and Android-TV play links."""
import json, time, urllib.request, sys, datetime

API = 'https://apis.justwatch.com/graphql'
# JustWatch package shortName -> our service key (must match SERVICES keys in index.html)
PKG = {'nfx': 'netflix', 'nfa': 'netflix', 'amp': 'prime', 'ytt': 'yttv', 'yfr': 'youtube',
       'tbv': 'tubi', 'ppe': 'paramount', 'ppp': 'paramount'}
TECH = {'netflix': 'netflix', 'netflixbasicwithads': 'netflix', 'amazonprime': 'prime', 'youtubetv': 'yttv',
        'youtubefree': 'youtube', 'tubitv': 'tubi', 'paramountplusessential': 'paramount', 'paramountpluspremium': 'paramount'}
# JustWatch genre shortName -> TMDB genre ids (movie + tv ids, so either filter matches)
GEN = {'act': [28, 10759], 'ani': [16], 'cmy': [35], 'crm': [80], 'doc': [99], 'drm': [18], 'fml': [10751, 10762],
       'fnt': [14, 10765], 'hst': [36], 'hrr': [27], 'msc': [10402], 'rma': [10749], 'scf': [878, 10765],
       'trl': [53, 9648], 'war': [10752, 10768], 'wsn': [37], 'rly': [10764], 'spt': [], 'eur': []}
Q = """query Q($country: Country!, $language: Language!, $first: Int!, $after: String, $filter: TitleFilter, $sortBy: PopularTitlesSorting!) {
 popularTitles(country: $country, first: $first, after: $after, filter: $filter, sortBy: $sortBy) {
  pageInfo { hasNextPage endCursor }
  edges { node { objectType
   content(country: $country, language: $language) { title originalReleaseYear originalReleaseDate shortDescription runtime posterUrl ageCertification
     genres { shortName } externalIds { imdbId tmdbId } scoring { imdbScore imdbVotes tmdbScore } credits(first: 6, role: ACTOR) { name } }
   offers(country: $country, platform: WEB, filter: {monetizationTypes: [FLATRATE, FREE, ADS]}) { deeplinkURL(platform: ANDROID_TV) standardWebURL package { technicalName } }
  } } } }"""

def post(body, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(API, data=json.dumps(body).encode(), headers={'content-type': 'application/json', 'user-agent': 'HomeTV/1.0'})
            return json.load(urllib.request.urlopen(req, timeout=40))
        except Exception as e:
            print('retry', i, e, file=sys.stderr); time.sleep(3 + i * 5)
    raise RuntimeError('JustWatch unreachable')

def fetch(obj, limit):
    out, after = [], None
    while len(out) < limit:
        v = {'country': 'US', 'language': 'en', 'first': 100, 'after': after, 'sortBy': 'POPULAR',
             'filter': {'packages': list(PKG), 'monetizationTypes': ['FLATRATE', 'FREE', 'ADS'], 'objectTypes': [obj]}}
        j = post({'query': Q, 'variables': v})
        pt = j['data']['popularTitles']
        out += [e['node'] for e in pt['edges']]
        if not pt['pageInfo']['hasNextPage']: break
        after = pt['pageInfo']['endCursor']; time.sleep(0.4)
    return out

def build(movies=3000, shows=2000):
    titles, seen = [], set()
    for obj, n in (('MOVIE', movies), ('SHOW', shows)):
        for node in fetch(obj, n):
            c = node.get('content') or {}
            tm = (c.get('externalIds') or {}).get('tmdbId')
            if not tm: continue
            kind = 'movie' if obj == 'MOVIE' else 'tv'
            k = kind + ':' + str(tm)
            if k in seen: continue
            links, svc = {}, []
            for o in node.get('offers') or []:
                s = TECH.get((o.get('package') or {}).get('technicalName'))
                if not s: continue
                if s not in svc: svc.append(s)
                if o.get('deeplinkURL') and s not in links: links[s] = o['deeplinkURL']
            if not svc: continue
            sc = c.get('scoring') or {}
            g = []
            for x in c.get('genres') or []:
                for gid in GEN.get(x['shortName'], []):
                    if gid not in g: g.append(gid)
            poster = c.get('posterUrl')
            t = {'k': k, 'kind': kind, 'id': int(tm), 'title': c.get('title'),
                 'date': (c.get('originalReleaseDate') or str(c.get('originalReleaseYear') or '')),
                 'pimg': ('https://images.justwatch.com' + poster.replace('{profile}', 's332').replace('{format}', 'jpg')) if poster else None,
                 'overview': c.get('shortDescription') or '', 'genres': g, 'svc': svc, 'links': links,
                 'ir': sc.get('imdbScore'), 'iv': sc.get('imdbVotes') or 0, 'tmdb': sc.get('tmdbScore') or 0,
                 'cast': [p['name'] for p in (c.get('credits') or [])][:6],
                 'rt': (str(c['runtime']) + ' min') if c.get('runtime') else None, 'rated': c.get('ageCertification')}
            seen.add(k); titles.append(t)
    return {'built': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='minutes'), 'count': len(titles), 'titles': titles}

if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else 'catalog.json'
    cat = build()
    json.dump(cat, open(out, 'w'), separators=(',', ':'))
    print('wrote', out, cat['count'], 'titles')
