#!/usr/bin/env python3
"""Flatten local Orca user presets for transfer to the phone. Output stays gitignored."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--config', type=Path, default=Path.home() / '.config/OrcaSlicer')
parser.add_argument('--resources', type=Path, required=True, help='Pinned OrcaSlicer resources directory')
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'dist/private-presets')
parser.add_argument('--include-network-address', action='store_true', help='Include printer host/web addresses, still excluding credentials')
args = parser.parse_args()
index = {}
users = sorted((args.config / 'user').iterdir(), key=lambda p: (p.name != 'default',p.name)) if (args.config / 'user').is_dir() else []
users = [p for p in users if p.is_dir()]
for folder in [args.resources / 'profiles', *users]:
    for path in sorted(folder.rglob('*.json')):
        kind = next((part for part in path.relative_to(folder).parts[:-1] if part in ('machine', 'filament', 'process')), None)
        if kind is None:
            continue
        data = json.loads(path.read_text(encoding='utf-8-sig'))
        index[kind, data.get('name', path.stem)] = data

# Network credentials are unnecessary for offline slicing and never leave the desktop.
private_keys = {'printhost_apikey', 'printhost_password', 'printhost_user', 'printhost_cafile',
                'print_host', 'print_host_webui', 'printhost_port', 'flashforge_serial_number'}
if args.include_network_address:
    private_keys -= {'print_host', 'print_host_webui', 'printhost_port'}
bookkeeping = {'inherits', 'instantiation', 'setting_id', 'base_id', 'is_custom_defined'}

def resolve(kind, name, seen=()):
    if (kind, name) in seen:
        raise ValueError('Preset inheritance cycle')
    data = index.get((kind, name))
    if data is None:
        raise ValueError(f'Unresolved {kind} parent: {name}')
    parent = data.get('inherits')
    base = resolve(kind, parent, seen + ((kind, name),)) if parent else {}
    return {**base, **data}

args.output.mkdir(parents=True, exist_ok=True)
bundle = []
for kind in ('machine', 'process', 'filament'):
    own = {}
    for user in users:
        for path in sorted((user / kind).rglob('*.json')):
            raw = json.loads(path.read_text(encoding='utf-8-sig'))
            own[raw.get('name', path.stem)] = raw
    for number, (name, raw) in enumerate(own.items(), 1):
        data = resolve(kind, name)
        for key in private_keys | bookkeeping:
            data.pop(key, None)
        data.update(type=kind, name=name, **{'from': 'User'})
        bundle.append(data)
        destination = args.output / f'{kind}-{number}.json'
        destination.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        print(f'{kind}: {data["name"]} -> {destination.name}')
(args.output / 'user-presets.json').write_text(json.dumps({'format': 'OrcaSlicer-Android-presets-1', 'profiles': bundle}, ensure_ascii=False, indent=2) + '\n')
print(f'Bundle: user-presets.json ({len(bundle)} presets)')
print('Private output:', args.output)
