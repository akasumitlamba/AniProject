"""Map AniBrave's pinned Brave integration symbols onto a newer official engine.

R8 renames most Chromium classes and members on every release. AniBrave reaches
a handful of them (tab model, toolbar strip, DevTools socket prefix). This tool
starts from stable, unobfuscated anchors, follows the same field/method path in
both engines and matches each member by normalized shape, declaration position
and bytecode similarity. Any ambiguity fails closed; nothing is guessed.

    python3 anibrave/tools/map_engine.py --old <decoded-old> --new <decoded-new> \
        --symbols anibrave/engine-symbols.json --tag v1.99.57 --write

The decoded trees come from `apktool d` of the two pinned official APKs.
"""
import argparse
import difflib
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from smali_index import Index, normal, obfuscated, class_of, params  # noqa: E402

TAB = 'org/chromium/chrome/browser/tab/Tab'
TAB_MODEL = 'org/chromium/chrome/browser/tabmodel/TabModel'
LOAD_PARAMS = 'org/chromium/content_public/browser/LoadUrlParams'
TABBED = 'org/chromium/chrome/browser/ChromeTabbedActivity'
CONTROL = 'org/chromium/chrome/browser/toolbar/top/ToolbarControlContainer'
DEVTOOLS = 'org/chromium/chrome/browser/DevToolsServer'


class MappingError(RuntimeError):
    pass


def body_shape(body, index):
    """Bytecode without line numbers/registers/obfuscated names, for similarity."""
    lines = []
    for raw in body.splitlines():
        line = raw.strip()
        if not line or line.startswith(('.line', '.local', '.end local', '.restart', '.param', '.end param')):
            continue
        line = re.sub(r'\b[vp]\d+\b', 'r', line)
        line = re.sub(r'L([^;\s]+);', lambda m: 'L?;' if obfuscated('L' + m[1] + ';') else m[0], line)
        line = re.sub(r'->\w+([:(])', r'->?\1', line)
        line = re.sub(r':\w+_\w+', ':lbl', line)
        lines.append(line)
    return lines


def similarity(a, b):
    if not a and not b:
        return 1.0
    return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio()


class Mapper:
    def __init__(self, old, new):
        self.old, self.new = Index(old), Index(new)
        self.notes = []

    # -- members ---------------------------------------------------------
    def _owner_with(self, index, cls, kind, name):
        for owner in index.chain(cls):
            members = index.fields(owner) if kind == 'field' else index.methods(owner)
            if any(m[0] == name for m in members):
                return owner
        raise MappingError(f'{kind} {name} not found on {cls}')

    def field(self, old_cls, new_cls, name):
        """Return (new_name, old_type, new_type)."""
        old_owner = self._owner_with(self.old, old_cls, 'field', name)
        depth = self.old.chain(old_cls).index(old_owner)
        new_owner = self.new.chain(new_cls)[depth]
        fields = self.old.fields(old_owner)
        old = next(f for f in fields if f[0] == name)
        same_old = [f for f in fields if normal(f[1]) == normal(old[1]) and f[2] == old[2]]
        same_new = [f for f in self.new.fields(new_owner) if normal(f[1]) == normal(old[1]) and f[2] == old[2]]
        if not same_new:
            raise MappingError(f'No field shaped {normal(old[1])} on {new_owner} for {old_owner}.{name}')
        if len(same_old) == len(same_new):
            pick = same_new[same_old.index(old)]
        elif len(same_new) == 1:
            pick = same_new[0]
        else:
            pick = self._by_type_similarity(old, same_new, f'{old_owner}.{name}')
        return pick[0], old[1], pick[1]

    def _by_type_similarity(self, old, candidates, label):
        old_cls = class_of(old[1].lstrip('['))
        if not old_cls or old_cls not in self.old.paths:
            raise MappingError(f'Ambiguous field {label}: {[c[0] for c in candidates]}')
        scores = sorted(((self.class_similarity(old_cls, class_of(c[1].lstrip('['))), c) for c in candidates),
                        key=lambda s: -s[0])
        if len(scores) > 1 and scores[0][0] - scores[1][0] < 0.15:
            raise MappingError(f'Ambiguous field {label}: {[(round(s, 2), c[0]) for s, c in scores]}')
        self.notes.append(f'{label}: chose {scores[0][1][0]} by type similarity {scores[0][0]:.2f}')
        return scores[0][1]

    def class_similarity(self, old_cls, new_cls):
        if new_cls not in self.new.paths:
            return 0.0
        a = sorted(normal(t) for _, t, _ in self.old.fields(old_cls)) + sorted(
            '(' + ''.join(map(normal, params(p))) + ')' + normal(r) for _, p, r, _, _ in self.old.methods(old_cls))
        b = sorted(normal(t) for _, t, _ in self.new.fields(new_cls)) + sorted(
            '(' + ''.join(map(normal, params(p))) + ')' + normal(r) for _, p, r, _, _ in self.new.methods(new_cls))
        return similarity(a, b)

    def method(self, old_cls, new_cls, name, descriptor=None):
        """Return (new_name, new_params, new_return) for old_cls.name(descriptor)."""
        old_owner = self._owner_with(self.old, old_cls, 'method', name)
        depth = self.old.chain(old_cls).index(old_owner)
        new_owner = self.new.chain(new_cls)[depth]
        olds = [m for m in self.old.methods(old_owner) if m[0] == name and (descriptor is None or m[1] == descriptor)]
        if len(olds) != 1:
            raise MappingError(f'Expected one {old_owner}.{name}({descriptor}), got {len(olds)}')
        old = olds[0]
        shape = lambda m: (tuple(map(normal, params(m[1]))), normal(m[2]), 'static' in m[3], 'abstract' in m[3])
        same_old = [m for m in self.old.methods(old_owner) if shape(m) == shape(old)]
        same_new = [m for m in self.new.methods(new_owner) if shape(m) == shape(old)]
        if not same_new:
            raise MappingError(f'No method shaped like {old_owner}.{name}({old[1]}){old[2]} on {new_owner}')
        if len(same_new) == 1:
            pick = same_new[0]
        elif 'abstract' in old[3]:
            pick = self._abstract(old_owner, new_owner, old, same_new)
        else:
            ref = body_shape(old[4], self.old)
            scores = sorted(((similarity(ref, body_shape(m[4], self.new)), m) for m in same_new), key=lambda s: -s[0])
            if scores[0][0] < 0.6 or scores[0][0] - scores[1][0] < 0.1:
                raise MappingError(f'Ambiguous {old_owner}.{name}: {[(round(s, 2), m[0]) for s, m in scores[:4]]}')
            pick = scores[0][1]
        return pick[0], pick[1], pick[2]


    def _abstract(self, old_owner, new_owner, old, same_new):
        """Compare implementations in stable-named classes present in both engines."""
        old_impls = {c for c in implementors(self.old, old_owner) if not obfuscated('L' + c + ';')}
        new_impls = {c for c in implementors(self.new, new_owner) if not obfuscated('L' + c + ';')}
        totals = {m[0]: 0.0 for m in same_new}
        used = 0
        for cls in sorted(old_impls & new_impls):
            ref = next((b for n, p, r, mods, b in self.old.methods(cls) if n == old[0] and p == old[1]), None)
            if ref is None:
                continue
            ref = body_shape(ref, self.old)
            bodies = {n: body_shape(b, self.new) for n, p, r, mods, b in self.new.methods(cls)
                      if any(n == x[0] for x in same_new)}
            if len(bodies) != len(same_new):
                continue
            used += 1
            for key, body in bodies.items():
                totals[key] += similarity(ref, body)
        if not used:
            raise MappingError(f'No shared implementation to disambiguate {old_owner}.{old[0]}')
        scores = sorted(((v / used, k) for k, v in totals.items()), reverse=True)
        ret = class_of(old[2])
        if len(scores) > 1 and scores[0][0] - scores[1][0] < 0.1 and ret and obfuscated(old[2]):
            # Identical getter bodies: tell them apart by what they return.
            best = scores[0][0]
            tied = [k for s, k in scores if best - s < 0.1]
            by_type = {k: self.class_similarity(ret, class_of(next(m[2] for m in same_new if m[0] == k))) for k in tied}
            scores = sorted(((v, k) for k, v in by_type.items()), reverse=True)
        if scores[0][0] < 0.6 or len(scores) > 1 and scores[0][0] - scores[1][0] < 0.1:
            raise MappingError(f'Ambiguous abstract {old_owner}.{old[0]}: {[(round(s, 2), k) for s, k in scores]}')
        self.notes.append(f'{old_owner}.{old[0]} -> {scores[0][1]} via {used} implementation(s), {scores[0][0]:.2f}')
        return next(m for m in same_new if m[0] == scores[0][1])


def implementors(index, interface):
    """Classes whose transitive supertypes include `interface`."""
    out = []
    for cls in index.paths:
        seen, todo = set(), [cls]
        while todo:
            cur = todo.pop()
            if cur in seen:
                continue
            seen.add(cur)
            if cur == interface and cls != interface:
                out.append(cls)
                break
            sup = index.super(cur)
            todo.extend(([sup] if sup else []) + index.interfaces(cur))
    return out


def resolve(old_symbols, old_dir, new_dir):
    m = Mapper(old_dir, new_dir)
    o, n = m.old, m.new
    s = old_symbols['symbols']
    out = {}

    # Activity accessors live on ChromeActivity (an obfuscated superclass).
    out['activity.currentTab'] = m.method(TABBED, TABBED, s['activity.currentTab'], '')[0]
    sel_old_name = s['activity.tabModelSelector']
    sel_new_name, _, sel_new_ret = m.method(TABBED, TABBED, sel_old_name, '')
    out['activity.tabModelSelector'] = sel_new_name
    sel_old = class_of(next(r for c in o.chain(TABBED) for name, _, r, _, _ in o.methods(c) if name == sel_old_name))
    sel_new = class_of(sel_new_ret)
    for key in ['selector.model', 'selector.currentModel', 'selector.selectModel', 'selector.createTab']:
        out[key] = m.method(sel_old, sel_new, s[key])[0]

    out['tabModel.setIndex'] = m.method(TAB_MODEL, TAB_MODEL, s['tabModel.setIndex'], 'II')[0]
    remover_name, _, remover_type = m.method(TAB_MODEL, TAB_MODEL, s['tabModel.closer'], '')
    out['tabModel.closer'] = remover_name
    closer_old, closer_new = s['class.tabCloser'], class_of(remover_type)
    out['class.tabCloser'] = closer_new
    # The closer interface's single-argument close(params) method names the params class.
    close_old = [x for x in o.methods(closer_old) if x[0] == s['tabCloser.close'] and len(params(x[1])) == 1]
    if len(close_old) != 1:
        raise MappingError('Tab closer method changed in the reference engine')
    def builds_single_tab(cls):
        return cls in n.paths and any('static' in x[3] and x[1] == f'L{TAB};' and 'singletonList' in x[4]
                                      for x in n.methods(cls))
    close_new = [x for x in n.methods(closer_new) if 'abstract' in x[3] and len(params(x[1])) == 1
                 and x[2] == 'V' and builds_single_tab(class_of(params(x[1])[0]))]
    params_new = {class_of(params(x[1])[0]) for x in close_new}
    if len(params_new) != 1:
        raise MappingError(f'Tab close parameters are ambiguous on {closer_new}: {params_new}')
    params_new = params_new.pop()
    out['tabCloser.close'] = m.method(closer_old, closer_new, s['tabCloser.close'], f"L{s['class.closeParams']};")[0]
    out['class.closeParams'] = params_new
    out['closeParams.forTab'] = m.method(s['class.closeParams'], params_new, s['closeParams.forTab'], f'L{TAB};')[0]
    builder_old = class_of(next(x[2] for x in o.methods(s['class.closeParams']) if x[0] == s['closeParams.forTab']))
    builder_new = class_of(next(x[2] for x in n.methods(params_new) if x[0] == out['closeParams.forTab']))
    out['closeBuilder.build'] = m.method(builder_old, builder_new, s['closeBuilder.build'], '')[0]

    loaders = [x for x in n.methods(TAB) if x[1] == f'L{LOAD_PARAMS};' and x[2] != 'V']
    if len(loaders) != 1:
        raise MappingError(f'Tab.loadUrl(LoadUrlParams) is ambiguous: {[x[0] for x in loaders]}')
    out['tab.loadUrl'] = loaders[0][0]

    # Socket prefix: the one class that initializes DevToolsServer with "chrome".
    prefix = [c for c in n.paths if n.text(c).count('const-string v4, "chrome"') == 1
              and 'DevToolsServer;->b:J' in n.text(c) and 'LJ/N;->JO(ILjava/lang/Object;)J' in n.text(c)]
    if len(prefix) != 1:
        raise MappingError(f'DevTools socket prefix factory is ambiguous: {prefix}')
    out['class.devtoolsPrefix'] = prefix[0]
    out['devtoolsPrefix.smaliFolder'] = n.folder(prefix[0])
    init = re.search(r'const/4 v3, (0x\w+)\s+(?:\.line \d+\s+)*\.line \d+\s+const-string v4, "chrome"', n.text(prefix[0]))
    if not init:
        init = re.findall(r'const/4 v3, (0x\w+)', n.text(prefix[0]).split('const-string v4, "chrome"')[0])
        init = init[-1] if init else None
    else:
        init = init[1]
    out['jni.devtoolsInitSelector'] = int(init, 16)
    stop = re.findall(r'invoke-static \{v3, v0, v1, v2, v4\}, LJ/N;->(\w+)\(IJZZ\)V', n.text(DEVTOOLS))
    if stop != [s['jni.devtoolsSetEnabled']]:
        raise MappingError(f'DevToolsServer enable call changed: {stop}')
    out['jni.devtoolsSetEnabled'] = stop[0]
    sel = re.search(r'const/4 v3, (0x\w+)', n.text(DEVTOOLS).split('LJ/N;->VJZZ')[0].rsplit('.method public final a()V', 1)[1])
    out['jni.devtoolsSetEnabledSelector'] = int(sel[1], 16)

    # Toolbar strip path: ToolbarControlContainer.z -> compositor -> transition -> controller.
    def follow(old_cls, new_cls, key):
        name, old_t, new_t = m.field(old_cls, new_cls, s[key])
        out[key] = name
        return class_of(old_t), class_of(new_t)

    comp = follow(CONTROL, CONTROL, 'control.compositor')
    trans = follow(*comp, 'compositor.stripTransition')
    manager = follow(*comp, 'compositor.controlsManager')
    out['controlsManager.show'] = m.method(*manager, s['controlsManager.show'], '')[0]
    out['stripTransition.setHidden'] = m.method(*trans, s['stripTransition.setHidden'], 'Z')[0]
    ctrl = follow(*trans, 'stripTransition.controller')
    for key in ['controller.height', 'controller.currentHeight', 'controller.threshold',
                'controller.hidden', 'controller.width', 'controller.extraHeight', 'controller.responsive']:
        out[key] = m.field(*ctrl, s[key])[0]
    out['controller.update'] = m.method(*ctrl, s['controller.update'], 'Z')[0]
    owner = follow(*ctrl, 'controller.owner')
    layer = follow(*owner, 'owner.layer')
    out['layer.flags'] = m.field(*layer, s['layer.flags'])[0]
    out['layer.setVisible'] = m.method(*layer, s['layer.setVisible'], 'IZ')[0]
    out['gurl.spec'] = m.field('org/chromium/url/GURL', 'org/chromium/url/GURL', s['gurl.spec'])[0]
    for key in ['res.layout.toolbar', 'res.layout.toolbarTablet']:
        out[key] = layout(Path(old_dir), Path(new_dir), s[key])
    out['res.string.appName'] = app_name(Path(new_dir))
    return out, m.notes


def _res_norm(text):
    return re.sub(r'APKTOOL_RENAMED_0x[0-9a-f]+', 'R', text)


def layout(old_dir, new_dir, name):
    """Match an obfuscated layout by content among layouts hosting the tab switcher."""
    ref = _res_norm((old_dir / 'res/layout' / f'{name}.xml').read_text(encoding='utf-8'))
    scores = []
    for path in (new_dir / 'res/layout').glob('*.xml'):
        text = path.read_text(encoding='utf-8')
        if '@id/tab_switcher_button' in text:
            scores.append((difflib.SequenceMatcher(None, ref, _res_norm(text), autojunk=False).ratio(), path.stem))
    scores.sort(reverse=True)
    if not scores or scores[0][0] < 0.9 or len(scores) > 1 and scores[1][0] > 0.6:
        raise MappingError(f'Toolbar layout {name} is ambiguous: {scores[:3]}')
    return scores[0][1]


def app_name(new_dir):
    names = re.findall(r'<string name="([^"]+)">Brave</string>',
                       (new_dir / 'res/values/strings.xml').read_text(encoding='utf-8'))
    if len(names) != 1:
        raise MappingError(f'Expected one Brave app-name string: {names}')
    return names[0]


JAVA = Path(__file__).resolve().parents[1] / 'src' / 'EngineSymbols.java'


def java_source(result):
    """Constants used by runtime reflection; generated so engine names live in one place."""
    lines = ['package app.anibrave;', '',
             '/** Generated by anibrave/tools/map_engine.py from engine-symbols.json. Do not edit. */',
             'final class EngineSymbols {',
             f'    static final String ENGINE = "{result["engine"]}";']
    for key, value in result['symbols'].items():
        if key.startswith('res.') or key.endswith('smaliFolder'):
            continue
        const = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', key.replace('.', '_')).upper()
        if isinstance(value, int):
            lines.append(f'    static final int {const} = {value};')
        else:
            lines.append(f'    static final String {const} = "{value}";')
    lines += ['    private EngineSymbols() {}', '}', '']
    return '\n'.join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--old', required=True, help='apktool output of the engine named in --symbols')
    p.add_argument('--new', required=True, help='apktool output of the candidate engine')
    p.add_argument('--symbols', type=Path, default=Path(__file__).resolve().parents[1] / 'engine-symbols.json')
    p.add_argument('--tag', required=True, help='candidate Brave tag, e.g. v1.99.57')
    p.add_argument('--write', action='store_true', help='replace --symbols with the mapped result')
    p.add_argument('--check-java', action='store_true', help='only verify EngineSymbols.java matches --symbols')
    if '--check-java' in sys.argv:
        current = json.loads(p.get_default('symbols').read_text())
        if JAVA.read_text() != java_source(current):
            sys.exit('EngineSymbols.java is stale; run map_engine.py --write or --sync-java')
        print('EngineSymbols.java matches', current['engine'])
        return
    if '--sync-java' in sys.argv:
        JAVA.write_text(java_source(json.loads(p.get_default('symbols').read_text())))
        print('Wrote', JAVA)
        return
    args = p.parse_args()
    old = json.loads(args.symbols.read_text())
    try:
        mapped, notes = resolve(old, args.old, args.new)
    except MappingError as error:
        sys.exit(f'Engine mapping failed closed: {error}')
    unknown = set(old['symbols']) - set(mapped)
    if unknown:
        sys.exit(f'Unmapped symbols: {sorted(unknown)}')
    for note in notes:
        print('note:', note)
    for key in sorted(mapped):
        flag = '' if mapped[key] == old['symbols'][key] else f'   (was {old["symbols"][key]})'
        print(f'{key:32} {mapped[key]}{flag}')
    result = {'schema': 1, 'engine': args.tag, 'symbols': dict(sorted(mapped.items()))}
    if args.write:
        args.symbols.write_text(json.dumps(result, indent=2) + '\n')
        JAVA.write_text(java_source(result))
        print('Wrote', args.symbols, 'and', JAVA)


if __name__ == '__main__':
    main()
