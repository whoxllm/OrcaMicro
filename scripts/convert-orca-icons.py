#!/usr/bin/env python3
"""Convert retained upstream SVG action artwork into Android VectorDrawables."""
from pathlib import Path
import re
import xml.etree.ElementTree as E

root = Path(__file__).resolve().parents[1]
ANDROID = 'http://schemas.android.com/apk/res/android'
E.register_namespace('android', ANDROID)
def attr(name): return '{' + ANDROID + '}' + name

def convert(element, output):
    tag = element.tag.split('}')[-1]
    values = element.attrib
    if tag in ('defs', 'title', 'metadata'): return
    if tag == 'g':
        for child in element: convert(child, output)
        return
    if tag == 'path':
        path = values['d']
    elif tag in ('polygon', 'polyline'):
        points = re.findall(r'-?[\d.]+', values['points'])
        path = 'M' + ' '.join(points[:2]) + ' ' + ' '.join('L' + ' '.join(points[i:i+2]) for i in range(2,len(points),2)) + (' Z' if tag=='polygon' else '')
    elif tag == 'line':
        path = f'M{values["x1"]},{values["y1"]} L{values["x2"]},{values["y2"]}'
    elif tag == 'rect':
        x,y,w,h = (float(values.get(k,0)) for k in ('x','y','width','height'))
        radius = min(float(values.get('rx',0)), w/2,h/2)
        transform = values.get('transform','')
        # The retained files rotate rectangles by 180 degrees about their center.
        # Such a rotation preserves their complete geometry, including rounded corners.
        if transform:
            m=re.fullmatch(r'translate\(([\d.]+) ([\d.]+)\) rotate\(-180\)',transform)
            assert m and abs(float(m[1])-(2*x+w))<.001 and abs(float(m[2])-(2*y+h))<.001, transform
        if radius:
            v=radius
            path=f'M{x+v},{y} h{w-2*v} a{v},{v} 0 0,1 {v},{v} v{h-2*v} a{v},{v} 0 0,1 {-v},{v} h{-w+2*v} a{v},{v} 0 0,1 {-v},{-v} v{-h+2*v} a{v},{v} 0 0,1 {v},{-v} Z'
        else: path=f'M{x},{y} h{w} v{h} h{-w} Z'
    elif tag == 'circle':
        x,y,v=(float(values[k]) for k in ('cx','cy','r'))
        path=f'M{x-v},{y} a{v},{v} 0 1,0 {2*v},0 a{v},{v} 0 1,0 {-2*v},0'
    else: raise ValueError(f'Unsupported SVG shape: {tag}')
    styles = {k.strip():v.strip() for k,v in (pair.split(':',1) for pair in values.get('style','').split(';') if ':' in pair)}
    styles = {**values, **styles}
    def color(value): return {'none':'#00000000','gray':'#808080'}.get(value,value)
    attributes={attr('pathData'):path,attr('fillColor'):color(styles.get('fill','#000000'))}
    for source,destination in [('stroke','strokeColor'),('stroke-width','strokeWidth'),('stroke-linecap','strokeLineCap'),('stroke-linejoin','strokeLineJoin')]:
        if source in styles: attributes[attr(destination)] = color(styles[source])
    if 'stroke' in styles: attributes.setdefault(attr('strokeWidth'),'1')
    opacity=float(styles.get('opacity',1))
    attributes[attr('fillAlpha')]=str(opacity*float(styles.get('fill-opacity',1)))
    attributes[attr('strokeAlpha')]=str(opacity*float(styles.get('stroke-opacity',1)))
    E.SubElement(output,'path',attributes)

for source in sorted((root/'third_party/orca-icons').glob('*.svg')):
    svg=E.parse(source).getroot()
    viewport=svg.get('viewBox','0 0 16 16').split()
    output=E.Element('vector',{attr('width'):'24dp',attr('height'):'24dp',attr('viewportWidth'):viewport[2],attr('viewportHeight'):viewport[3]})
    target=output
    # Equal visible bounds for the phone's four plate tools, irrespective of the
    # desktop SVG canvas (16 px menu art versus 40 px toolbar art).
    artwork=source.stem.removesuffix('_dark')
    bounds={'toolbar_open':(3.5,3.5,32),'toolbar_move':(3.5,3.5,32),'toolbar_arrange':(3.5,3.5,32),'delete':(0,0,15)}.get(artwork)
    if bounds:
        x,y,span=bounds
        output.set(attr('viewportWidth'),'24');output.set(attr('viewportHeight'),'24')
        factor=24/span
        target=E.SubElement(output,'group',{attr('scaleX'):str(factor),attr('scaleY'):str(factor),attr('translateX'):str(-x*factor),attr('translateY'):str(-y*factor)})
    for child in svg: convert(child,target)
    folder=root/'app/src/main/res'/('drawable-night' if source.stem.endswith('_dark') else 'drawable')
    folder.mkdir(exist_ok=True)
    (folder/f'orca_{artwork}.xml').write_text(E.tostring(output,encoding='unicode')+'\n')
