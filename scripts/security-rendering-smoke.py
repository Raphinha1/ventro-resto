#!/usr/bin/env python3
"""Run the real KDS/calendar renderers in disposable Chromium with inert HTML fixtures."""
import argparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--only', choices=['all','kds','calendar'], default='all')
args = parser.parse_args()
root = args.root
kds = (root / 'src/main/resources/templates/kds/index.html').read_text()
kds = kds[kds.index('    function renderGrid('):kds.index('    async function cambiarEstado(')]
calendar = (root / 'src/main/resources/templates/reservas/index.html').read_text()
match = re.search(r'eventContent:\s*function\(arg\)\s*\{([\s\S]*?)return \{ domNodes: \[el\] \};\s*\}', calendar)
assert match, 'FullCalendar renderer not found'
renderer = 'function renderCalendar(arg) {' + match.group(1) + 'return { domNodes: [el] };}'
fixture = '''<!doctype html><html><body><div id="kds-grid-container"></div><div id="calendar"></div>
<script>
const actions = []; let timerCalls = 0;
function updateTimersOnly() { timerCalls++; }
function cambiarEstado(id, state) { actions.push([id, state]); }
function marcarNotificacionLeida(id) { actions.push(['notice', id]); }
__KDS__
__CALENDAR__
const check = (ok, message) => { if (!ok) throw Error(message); };
try {
    const markers = ['<b data-xss="fixture">Synthetic & name</b>',
        '<svg data-xss="fixture"><title>Synthetic</title></svg>', '&lt;b&gt;Synthetic&lt;/b&gt;'];
    for (const [i, marker] of markers.entries()) {
        if (__TEST_KDS__) {
        const order = {id:i+1, ventaId:10, fecha:'2026-10-15T12:00:00',
            cantidad:2, productoNombre:marker, notas:marker, estado:'PENDIENTE', usuario:marker};
        renderGrid([order], [{id:100+i, fecha:'2026-10-15T12:00:00', insumo:marker}]);
        const card = document.getElementById('order-'+order.id);
        check(card.querySelector('.item-name').textContent === marker, 'product name changed');
        check(card.querySelector('.item-notes').textContent.includes(marker), 'notes changed');
        check(card.querySelector('.text-muted').textContent === 'Por: '+marker, 'username changed');
        check(document.getElementById('notif-'+(100+i)).textContent.includes(marker), 'notification changed');
        check(document.querySelectorAll('[data-xss]').length === 0, 'KDS interpreted inert HTML');
        card.querySelector('button').click();
        document.getElementById('notif-'+(100+i)).querySelector('button').click();
        check(actions.at(-2)[0] === order.id && actions.at(-2)[1] === 'PREPARANDO', 'KDS action changed');
        check(actions.at(-1)[1] === 100+i, 'notification action changed');
        }
        if (__TEST_CALENDAR__) {
        const result = renderCalendar({timeText:marker, event:{title:marker+' (2 pers)'}});
        document.getElementById('calendar').replaceChildren(...result.domNodes);
        check(document.getElementById('calendar').textContent === marker+' '+marker+' (2 pers)', 'calendar text changed');
        check(document.querySelectorAll('[data-xss]').length === 0, 'calendar interpreted inert HTML');
        check(document.querySelector('#calendar b').textContent === marker, 'time emphasis lost');
        }
    }
    if (__TEST_KDS__) {
    renderGrid([{id:40, ventaId:20,fecha:'2026-10-15T12:00:00',cantidad:1,
        productoNombre:'Synthetic meal',notas:'',estado:'PREPARANDO',usuario:'Synthetic cook'}], []);
    check(!document.querySelector('#order-40 .item-notes'), 'empty notes displayed');
    document.querySelector('#order-40 button').click();
    check(actions.at(-1)[1] === 'LISTO', 'ready transition changed');
    check(timerCalls === 4, 'timers not updated');
    renderGrid([], []);
    check(document.querySelector('.empty-state'), 'empty state lost');
    }
    document.body.dataset.securitySmoke = 'PASS';
} catch (error) { document.body.dataset.securitySmoke = 'FAIL: '+error.message; }
</script></body></html>'''.replace('__KDS__', kds).replace('__CALENDAR__', renderer).replace('__TEST_KDS__', str(args.only != 'calendar').lower()).replace('__TEST_CALENDAR__', str(args.only != 'kds').lower())
chromium = shutil.which('chromium') or shutil.which('google-chrome')
assert chromium, 'Chromium is required'
with tempfile.TemporaryDirectory(prefix='ventro-rendering-') as directory:
    Path(directory, 'index.html').write_text(fixture)
    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw): super().__init__(*a, directory=directory, **kw)
        def log_message(self, *a): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = subprocess.run([chromium, '--headless', '--no-sandbox', '--disable-gpu',
            '--disable-dev-shm-usage', '--disable-background-networking', '--no-first-run',
            f'--user-data-dir={directory}/profile', '--virtual-time-budget=2000', '--dump-dom',
            f'http://127.0.0.1:{server.server_port}/index.html'], capture_output=True, text=True, timeout=30)
        marker = re.search(r'data-security-smoke="([^"]+)"', result.stdout)
        if result.returncode or not marker or marker.group(1) != 'PASS':
            raise SystemExit('FAIL: '+(marker.group(1) if marker else 'browser did not complete'))
        print('PASS: real Chromium; 3 inert HTML representations rendered literally across KDS notes, names, notifications and calendar; normal actions, timers and empty state preserved.')
    finally:
        server.shutdown()
        thread.join()
