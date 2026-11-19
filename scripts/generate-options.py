#!/usr/bin/env python3
"""Extract the FFF preset option catalog from the pinned OrcaSlicer sources.

Usage: generate-options.py <OrcaSlicer checkout>
Reads through git so a profiles-only sparse checkout works too. No network packages.
"""
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
checkout = Path(sys.argv[1])

def source(path):
    return subprocess.check_output(["git", "-C", str(checkout), "show", f"HEAD:{path}"], text=True)

config = source("src/libslic3r/PrintConfig.cpp")
preset = source("src/libslic3r/Preset.cpp")
po = source("localization/i18n/ru/OrcaSlicer_ru.po")
translations = {}
for entry in po.split("\n\n"):
    fields = {}; current = None
    if '#, fuzzy' in entry:
        continue
    for line in entry.splitlines():
        if line.startswith(('msgid ', 'msgstr ')):
            current, value = line.split(' ', 1)
            fields[current] = ast.literal_eval(value)
        elif line.startswith('"') and current:
            fields[current] += ast.literal_eval(line)
    if fields.get('msgid') and fields.get('msgstr'):
        translations[fields['msgid']] = fields['msgstr']

def uncomment(text):
    # Keep comment-like characters inside C++ strings intact.
    return re.sub(r'("(?:\\.|[^"\\])*")|/\*.*?\*/|//[^\n]*',
                  lambda m: m[1] or '', text, flags=re.S)

config = uncomment(config)
preset = uncomment(preset)

def strings(text):
    return [ast.literal_eval(s) for s in re.findall(r'"(?:\\.|[^"\\])*"', text)]

def vector(text, name):
    match = re.search(r'\b' + re.escape(name) + r'\s*(?:=\s*)?\{(.*?)\};', text, re.S)
    if not match:
        raise ValueError(f'Missing vector: {name}')
    return strings(match[1])

members = {
    'PROCESS': vector(preset, 's_Preset_print_options'),
    'FILAMENT': vector(preset, 's_Preset_filament_options'),
    'MACHINE': vector(preset, 's_Preset_printer_options') + vector(preset, 's_Preset_machine_limits_options') + vector(config, 'm_extruder_option_keys'),
}
enum_maps = {}
for m in re.finditer(r'\bs_keys_map_(\w+)\s*(?:=\s*)?\{(.*?)\};', config, re.S):
    enum_maps[m[1]] = re.findall(r'\{\s*"([^"\\]*)"', m[2])

def field(block, name):
    m = re.search(r'def->' + name + r'\s*=\s*(.*?);', block, re.S)
    return ''.join(strings(m[1])) if m else ''

starts = list(re.finditer(r'def\s*=\s*this->add(?:_nullable)?\(\s*"([^"\\]+)"\s*,\s*(co\w+)\s*\)', config))
definitions = {}
for i, m in enumerate(starts):
    block = config[m.end():starts[i+1].start() if i+1 < len(starts) else len(config)]
    label = field(block, 'full_label') or field(block, 'label') or m[1].replace('_', ' ')
    category = field(block, 'category') or ('G-code' if 'gcode' in m[1] else 'Other')
    choices = re.findall(r'def->enum_values\.push_back\("([^"\\]*)"\)', block)
    enum = re.search(r'def->enum_keys_map\s*=\s*&ConfigOptionEnum<([^>]+)>', block)
    if enum:
        choices = list(dict.fromkeys(choices + enum_maps.get(enum[1], [])))
    definitions[m[1]] = dict(key=m[1], label=label, labelRu=translations.get(label, label),
                            category=category, categoryRu=translations.get(category, category),
                            description=field(block, 'tooltip'),
                            descriptionRu=translations.get(field(block, 'tooltip'), field(block, 'tooltip')),
                            valueType=m[2], unit=field(block, 'sidetext'), choices=choices)

# Orca creates these nullable filament options in a loop rather than string literals.
for key in vector(config, 'filament_extruder_override_keys'):
    if key.removeprefix('filament_') in definitions:
        definitions[key] = dict(definitions[key.removeprefix('filament_')], key=key)

metadata = {'inherits'}
options = []
for kind, keys in members.items():
    for key in dict.fromkeys(keys):
        if key in metadata:
            continue
        option = definitions.get(key, dict(key=key, label=key, labelRu=key, category='Other', categoryRu='Прочее', valueType='coString'))
        options.append(dict(option, type=kind))

overrides = json.loads((ROOT / 'scripts/ru-overrides.json').read_text())
for option in options:
    option['labelRu'] = overrides['labels'].get(option['key'], option['labelRu'])
    option['descriptionRu'] = overrides['descriptions'].get(option['key'], option.get('descriptionRu', ''))
    option['categoryRu'] = {'Quality': 'Качество', 'Strength': 'Прочность', 'Support': 'Поддержки', 'Speed': 'Скорость', 'Other': 'Прочее', 'Advanced': 'Расширенные', 'Multimaterial': 'Мультиматериалы'}.get(option.get('category'), option.get('categoryRu', 'Прочее'))
    option['unit'] = {'Hz': 'Гц', 'g/cm³': 'г/см³', 'layer': 'слой', 'layers': 'слоёв', 'money/h': 'валюта/ч', 'money/kg': 'валюта/кг', 's': 'с'}.get(option.get('unit', ''), option.get('unit', '')).replace('mm', 'мм').replace('/s', '/с').replace('min', 'мин').replace(' or ', ' или ')
    option['choiceLabelsRu'] = {value: overrides['choices'].get(value, translations.get(value, value)) for value in option.get('choices', [])}
    if option['key'].startswith(('machine_max_acceleration_', 'machine_max_speed_', 'machine_max_jerk_')):
        option['valueType'] = 'coFloats'
        option['unit'] = 'мм/с²' if 'acceleration' in option['key'] else 'мм/с'
output = ROOT / 'core/src/main/resources/orca-options.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(options, ensure_ascii=False, indent=2) + '\n')
print(f'{len(options)} options; ' + ', '.join(f'{k}: {sum(x["type"]==k for x in options)}' for k in members))
print('Catalog SHA-256:', hashlib.sha256(output.read_bytes()).hexdigest())

(ROOT / 'core/src/main/resources/orca-messages-ru.json').write_text(json.dumps(translations, ensure_ascii=False) + '\n')
