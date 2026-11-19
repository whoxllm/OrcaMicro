#!/usr/bin/env python3
"""Local UI integration fixture, never connects to a physical printer.

Android emulator API/web URL: http://10.0.2.2:8765
Uploads and a request summary are written under gitignored dist/mock-moonraker.
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from email.parser import BytesParser
from email.policy import default
import json

OUTPUT = Path(__file__).resolve().parents[1] / 'dist/mock-moonraker'
OUTPUT.mkdir(parents=True, exist_ok=True)

class Handler(BaseHTTPRequestHandler):
    def reply(self, body, kind='application/json', status=200):
        self.send_response(status)
        self.send_header('Content-Type', kind + '; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/server/info':
            self.reply(b'{"result":{"klippy_state":"ready","moonraker_version":"test-fixture"}}')
        else:
            self.reply('''<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><title>Klipper test</title>
<style>body{font:16px sans-serif;background:#242424;color:#eee;padding:20px}h1{color:#35bcae}.card{background:#36363b;padding:16px;border-radius:6px}</style>
<h1>Klipper · тест</h1><div class="card"><h2 id="status">Подключение…</h2><p>Проверка встроенного веб-интерфейса</p><p>Физический принтер не подключён</p></div>
<script>fetch('/server/info').then(r=>r.json()).then(j=>{document.getElementById('status').innerText='Klipper готов';localStorage.setItem('webview-test','ok')})</script>'''.encode(), 'text/html')

    def do_POST(self):
        if self.path != '/server/files/upload':
            self.reply(b'{"error":"Unexpected endpoint"}', status=404)
            return
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            parts = []
            while True:
                size = int(self.rfile.readline().split(b';')[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                parts.append(self.rfile.read(size))
                self.rfile.read(2)
            body = b''.join(parts)
        else:
            body = self.rfile.read(int(self.headers['Content-Length']))
        message = BytesParser(policy=default).parsebytes(('Content-Type: '+self.headers['Content-Type']+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+body)
        fields = {}; filename = None; data = None
        for part in message.iter_parts():
            name = part.get_param('name', header='content-disposition')
            if name == 'file':
                filename = Path(part.get_filename()).name
                data = part.get_payload(decode=True)
            else:
                fields[name] = part.get_payload(decode=True).decode()
        if fields.get('root') != 'gcodes' or fields.get('print') != 'false' or data is None:
            self.reply(b'{"error":"Invalid upload contract"}', status=400)
            return
        (OUTPUT / filename).write_bytes(data)
        (OUTPUT / 'request.json').write_text(json.dumps(dict(path=self.path, fields=fields, filename=filename, size=len(data)), indent=2))
        self.reply(json.dumps({'result': {'item': {'path': filename}, 'print_started': False}}).encode())

    def log_message(self, *_):
        pass

print('Moonraker UI fixture at http://127.0.0.1:8765', flush=True)
ThreadingHTTPServer(('0.0.0.0', 8765), Handler).serve_forever()
