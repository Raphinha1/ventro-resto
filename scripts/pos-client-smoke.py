#!/usr/bin/env python3
"""POS client smoke test in disposable local Chromium; no app or database required."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading


ROOT = Path(__file__).resolve().parents[1]
template = (ROOT / "src/main/resources/templates/pos/index.html").read_text()
script = template.split("<script>", 1)[1].split("</script>", 1)[0]
contexts_script = (ROOT / "src/main/resources/static/js/pos-cart-context.js").read_text()
chromium = shutil.which("chromium") or shutil.which("google-chrome")
if chromium is None:
    raise SystemExit("Chromium is required for this optional POS browser smoke test")

fixture = r"""
<!doctype html><html><body>
<div id="cart-items"></div><div id="total"></div><div id="subtotal"></div>
<button id="btn-vender-final"></button><button id="btn-dividir"></button>
<form id="checkout-form"><input name="idempotencyKey" value="00000000-0000-4000-8000-000000000001">
<div id="hidden-inputs"></div><input id="field-metodoPago" name="metodoPago">
<input id="field-montoEfectivo" name="montoEfectivo"><input id="field-montoTarjeta" name="montoTarjeta">
<input id="field-clienteId" name="clienteId"></form>
<select id="select-cliente"><option value=""></option><option value="7">Cliente de prueba</option></select>
<div id="modalPago"></div><div id="modalDividir"></div>
<div id="modal-total-display"></div><div id="tarjeta-total-display"></div>
<div id="quick-cash-btns"></div><input id="inp-efectivo-total"><div id="cambio-display"></div>
<input id="inp-mixto-tarjeta"><div id="mixto-efectivo-display"></div><div id="mixto-alerta"></div>
<button id="btn-confirmar-pago"></button>
<div id="tab-efectivo"></div><div id="tab-tarjeta"></div><div id="tab-mixto"></div>
<div id="panel-efectivo"></div><div id="panel-tarjeta"></div><div id="panel-mixto"></div>
<div id="split-items-container"></div><div id="split-total-display"></div>
<button id="retry-split-payment" class="d-none"></button>
<div id="pos-legacy-warning" class="d-none"><span id="pos-legacy-message"></span>
<button id="pos-legacy-adopt"></button><button id="pos-legacy-discard"></button></div>
<script>window.bootstrap = { Modal: class { show() {} hide() {} static getInstance() { return { hide() {} }; } } };</script>
<script>__CONTEXT_SCRIPT__</script>
<script>__POS_SCRIPT__</script>
<script>
document.addEventListener('DOMContentLoaded', () => {
  try {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    if (sessionStorage.getItem('smokeReload')) {
      check(PosCartContexts.read('mesa:202').cart[0].id === 202 &&
        PosCartContexts.read('sin-mesa').cart[0].cantidad === 1 &&
        PosCartContexts.read('mesa:101').cart[0].id === 303,
        'contexts changed after tab reload');
      sessionStorage.removeItem('smokeReload');
      document.body.dataset.smoke = 'PASS';
      return;
    }
    const form = document.getElementById('checkout-form');
    let submitted = [];
    let alerts = [];
    window.alert = message => alerts.push(message);
    form.submit = () => submitted.push(new FormData(form));
    const item = (id, cantidad, nota = '') => ({id, name:'Platillo '+id, price:20,
      puntosPrecio:0, cantidad, canjeado:false, nota});
    const result = (kind, key) => {
      document.querySelectorAll('#pos-sale-success,#pos-sale-error').forEach(node => node.remove());
      const node = document.createElement('div');
      node.id = kind === 'success' ? 'pos-sale-success' : 'pos-sale-error';
      node.dataset.saleKey = key;
      document.body.appendChild(node);
      document.dispatchEvent(new Event('DOMContentLoaded'));
    };

    cart = [item(1, 1), item(2, 1)];
    renderCart();
    const note = document.querySelector('#cart-items input[placeholder]');
    const special = '" & <img src=x onerror="window.injected=1"> á';
    note.value = special;
    note.dispatchEvent(new Event('input'));
    check(PosCartContexts.read('sin-mesa').cart[0].nota === special,
      'note was not saved as typed');
    check(document.querySelectorAll('#cart-items img').length === 0 && !window.injected,
      'note was interpreted as HTML');
    check([...document.querySelectorAll('#hidden-inputs [name=notas]')].map(n => n.value)
      .join('|') === special + '|', 'empty and special notes lost their positions');

    cart = [item(1, 1)];
    renderCart();
    selectPaymentTab('tarjeta');
    confirmarPago();
    const simpleKey = pendingSale.key;
    check(submitted.length === 1 && submitted[0].get('notas') === '',
      'single empty note not submitted');
    check(PosCartContexts.read('sin-mesa').cart.length === 1,
      'cart removed before server result');
    cart[0].nota = 'edited while result unknown';
    renderCart();
    confirmarPago();
    check(submitted.length === 1 && alerts.length === 1,
      'changed payload was sent while prior result is unknown');
    cart[0].nota = '';
    renderCart();
    result('error', simpleKey);
    check(cart.length === 1 && pendingSale.failed, 'failed simple sale lost the cart');
    selectPaymentTab('tarjeta');
    confirmarPago();
    check(pendingSale.key === simpleKey, 'same payload retry changed idempotency key');
    result('success', simpleKey);
    check(cart.length === 0 && PosCartContexts.read('sin-mesa') === null,
      'accepted simple sale was not committed');

    cart = [item(1, 2, special), item(2, 1)];
    renderCart();
    abrirModalDividir();
    changeSplitQty(0, 1);
    procesarSubcuenta();
    document.getElementById('select-cliente').value = '7';
    selectPaymentTab('tarjeta');
    confirmarPago();
    const splitKey = pendingSale.key;
    check(cart[0].cantidad === 2 && PosCartContexts.read('sin-mesa').cart[0].cantidad === 2,
      'split reduced cart before acceptance');
    check(submitted.at(-1).get('notas') === special, 'split note changed on submit');
    result('error', splitKey);
    check(cart[0].cantidad === 2 && splitCartToPay[0].cantidad === 1 &&
      splitSelection[0].qtySelected === 1, 'failed split lost exact subaccount');
    check(document.getElementById('select-cliente').value === '7', 'failed split lost client');
    abrirModalPago(true);
    check(currentPaymentTab === 'tarjeta', 'failed split lost payment method');
    confirmarPago();
    check(pendingSale.key === splitKey, 'split retry changed idempotency key');
    result('success', splitKey);
    check(cart[0].cantidad === 1 && cart[1].cantidad === 1 &&
      PosCartContexts.read('sin-mesa').pendingSale === null,
      'accepted split removed wrong items');
    const noTable = PosCartContexts.read('sin-mesa');
    PosCartContexts.write('mesa:101', {cart:[item(101, 2, 'Mesa A')], clienteId:'7',
      splitSelection:[{qtySelected:1}], pendingSale:{key:'retry-a'}});
    PosCartContexts.write('mesa:202', {cart:[item(202, 1, 'Mesa B')], clienteId:''});
    check(PosCartContexts.forMesa('101') === 'mesa:101' &&
      PosCartContexts.forMesa('') === 'sin-mesa', 'wrong cart context');
    check(PosCartContexts.read('mesa:101').cart[0].nota === 'Mesa A' &&
      PosCartContexts.read('mesa:202').cart[0].id === 202 &&
      PosCartContexts.read('sin-mesa').cart.length === noTable.cart.length,
      'table and no-table carts mixed');
    check(PosCartContexts.read('mesa:101').clienteId === '7' &&
      PosCartContexts.read('mesa:202').clienteId === '' &&
      PosCartContexts.read('mesa:101').pendingSale.key === 'retry-a',
      'client or retry state crossed contexts');
    PosCartContexts.clear('mesa:101');
    check(PosCartContexts.read('mesa:101') === null &&
      PosCartContexts.read('mesa:202').cart[0].id === 202 &&
      PosCartContexts.read('sin-mesa').cart.length === noTable.cart.length,
      'clearing one table removed another context');
    sessionStorage.setItem('pos_cart', JSON.stringify([item(303, 1, 'Anterior')]));
    sessionStorage.setItem('pos_pending_sale', JSON.stringify({key:'legacy-key',
      signature:JSON.stringify({mesaId:'101'})}));
    check(!PosCartContexts.legacyStatus('mesa:202').canAdopt &&
      !PosCartContexts.resolveLegacy('mesa:202', true),
      'legacy pending cart was assigned to a different table');
    check(PosCartContexts.resolveLegacy('mesa:101', true) &&
      PosCartContexts.read('mesa:101').cart[0].id === 303 &&
      sessionStorage.getItem('pos_cart') === null,
      'legacy cart was not migrated to its confirmed table');
    sessionStorage.setItem('smokeReload', '1');
    location.reload();
  } catch (error) {
    document.body.dataset.smoke = 'FAIL: ' + error.message;
  }
}, { once: true });
</script></body></html>
""".replace("__POS_SCRIPT__", script).replace("__CONTEXT_SCRIPT__", contexts_script)

with tempfile.TemporaryDirectory(prefix="ventro-pos-browser-") as directory:
    Path(directory, "index.html").write_text(fixture)
    class QuietHandler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=directory, **kwargs)

        def log_message(self, *args):
            pass

    handler = QuietHandler
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        completed = subprocess.run([
            chromium, "--headless", "--no-sandbox", "--disable-gpu",
            "--disable-dev-shm-usage", f"--user-data-dir={directory}/profile",
            "--virtual-time-budget=5000", "--dump-dom",
            f"http://127.0.0.1:{server.server_port}/index.html"
        ], text=True, capture_output=True, timeout=30)
        if completed.returncode != 0 or 'data-smoke="PASS"' not in completed.stdout:
            marker = completed.stdout.split('data-smoke="')[-1].split('"')[0]
            raise SystemExit(f"POS browser smoke failed: {marker or completed.stderr[-800:]}")
        print("POS browser smoke PASS: notes, failure/retry, split, idempotency, isolated contexts")

        map_template = (ROOT / "src/main/resources/templates/mesas/mapa.html").read_text()
        map_script = map_template.split("<script>", 1)[1].split("</script>", 1)[0]
        map_fixture = r"""<!doctype html><html><body>
<form class="mesa-release-form" data-mesa-id="101"><input name="decisionLiberacion"></form>
<form class="mesa-release-form" data-mesa-id="202"><input name="decisionLiberacion"></form>
<form class="mesa-release-form" data-mesa-id="303"><input name="decisionLiberacion"></form>
<a class="mesa-resume-pos d-none" data-mesa-id="101"></a>
<div id="modalLiberarMesa"></div><div id="mesa-release-message"></div>
<button id="mesa-release-discard"></button><a id="mesa-release-pos"></a>
<script>window.bootstrap={Modal:class{show(){document.body.dataset.modal='shown'}}};</script>
<script>__CONTEXT_SCRIPT__</script>
<script>
PosCartContexts.write('mesa:101',{cart:[{id:1,nota:'A'}]});
PosCartContexts.write('mesa:202',{cart:[{id:2,nota:'B'}]});
</script>
<script>__MAP_SCRIPT__</script>
<script>document.addEventListener('DOMContentLoaded',()=>{
  try {
    const check=(ok,msg)=>{if(!ok)throw Error(msg)};
    const forms=[...document.querySelectorAll('.mesa-release-form')];
    forms.forEach(form=>{form.submit=()=>{form.dataset.sent=form.elements.decisionLiberacion.value};
      form.addEventListener('submit',e=>e.preventDefault())});
    forms[0].dispatchEvent(new Event('submit',{cancelable:true}));
    check(document.body.dataset.modal==='shown' && !forms[0].dataset.sent &&
      PosCartContexts.hasPending('mesa:101'),'pending table was released silently');
    check(!document.querySelector('.mesa-resume-pos').classList.contains('d-none'),
      'dirty table did not offer to resume POS');
    forms[2].dispatchEvent(new Event('submit',{cancelable:true}));
    check(forms[2].elements.decisionLiberacion.value==='SIN_CARRITO',
      'empty table did not release normally');
    forms[1].dispatchEvent(new Event('submit',{cancelable:true}));
    document.getElementById('mesa-release-discard').click();
    check(forms[1].dataset.sent==='DESCARTAR' && PosCartContexts.hasPending('mesa:202'),
      'discard removed cart before server confirmation');
    const confirmed=document.createElement('span'); confirmed.id='mesa-release-confirmed';
    confirmed.dataset.mesaId='202'; document.body.appendChild(confirmed);
    document.dispatchEvent(new Event('DOMContentLoaded'));
    check(!PosCartContexts.hasPending('mesa:202') && PosCartContexts.hasPending('mesa:101'),
      'server-confirmed discard cleared wrong context');
    document.body.dataset.smoke='PASS';
  }catch(error){document.body.dataset.smoke='FAIL: '+error.message}
},{once:true});</script></body></html>""".replace("__CONTEXT_SCRIPT__", contexts_script).replace("__MAP_SCRIPT__", map_script)
        Path(directory, "map.html").write_text(map_fixture)
        checked = subprocess.run([
            chromium, "--headless", "--no-sandbox", "--disable-gpu",
            "--disable-dev-shm-usage", f"--user-data-dir={directory}/map-profile",
            "--virtual-time-budget=3000", "--dump-dom",
            f"http://127.0.0.1:{server.server_port}/map.html"
        ], text=True, capture_output=True, timeout=30)
        if checked.returncode != 0 or 'data-smoke="PASS"' not in checked.stdout:
            marker = checked.stdout.split('data-smoke="')[-1].split('"')[0]
            raise SystemExit(f"Mesa browser smoke failed: {marker or checked.stderr[-800:]}")
        print("Mesa browser smoke PASS: release guard, explicit discard, context isolation")

        table_fixture = fixture.split("<script>\ndocument.addEventListener('DOMContentLoaded', () => {", 1)[0]
        bootstrap_table = """<script>
const params = new URLSearchParams(location.search);
const mesa = params.get('mesaId');
if (mesa) { const input = document.createElement('input'); input.name='mesaId'; input.value=mesa;
  document.getElementById('checkout-form').appendChild(input); }
if (params.get('accepted')) { const marker=document.createElement('div');
  marker.id='pos-sale-success'; marker.dataset.saleKey=params.get('accepted');
  document.body.appendChild(marker); }
</script>"""
        table_fixture = table_fixture.replace(f"<script>{script}</script>",
                                              bootstrap_table + f"<script>{script}</script>") + "</body></html>"
        Path(directory, "table.html").write_text(table_fixture)
        switch_fixture = r"""<!doctype html><html><body><iframe id="pos"></iframe><script>
document.addEventListener('DOMContentLoaded', async () => {
  try {
    const check=(ok,msg)=>{if(!ok)throw Error(msg)};
    const frame=document.getElementById('pos');
    const load=query=>new Promise(resolve=>{frame.onload=()=>resolve(frame.contentWindow);
      frame.src='/table.html'+query});
    let page=await load('?mesaId=101');
    page.eval(`cart=[{id:1,name:'Platillo A',price:20,puntosPrecio:0,cantidad:2,
      canjeado:false,nota:'Nota A'}]; renderCart();
      document.getElementById('select-cliente').value='7';
      isSplitActive=true; splitSelection=[{...cart[0],qtySelected:1}];
      pendingSale={key:'a-key',signature:JSON.stringify({mesaId:'101'}),split:false,
        attemptedCart:[{...cart[0]}],remainingCart:[],failed:true,clienteId:'7'}; saveContext();`);
    page=await load('?mesaId=202');
    check(page.eval('cart.length')===0,'A cart appeared in B');
    page.eval(`cart=[{id:2,name:'Platillo B',price:20,puntosPrecio:0,cantidad:1,
      canjeado:false,nota:'Nota B'}]; renderCart();`);
    page=await load('');
    check(page.eval('cart.length')===0,'table cart appeared without table');
    page.eval(`cart=[{id:3,name:'Sin mesa',price:20,puntosPrecio:0,cantidad:1,
      canjeado:false,nota:'Nota libre'}]; renderCart();`);
    page=await load('?mesaId=101');
    check(page.eval(`cart.length===1 && cart[0].nota==='Nota A' &&
      document.getElementById('select-cliente').value==='7' &&
      splitSelection[0].qtySelected===1 && pendingSale.key==='a-key'`),
      'A did not restore cart, note, client, split and retry');
    page=await load('?mesaId=101&accepted=a-key');
    check(page.eval('cart.length===0 && pendingSale===null'), 'accepted A sale not cleared');
    check(page.PosCartContexts.read('mesa:101')===null &&
      page.PosCartContexts.read('mesa:202').cart[0].nota==='Nota B' &&
      page.PosCartContexts.read('sin-mesa').cart[0].nota==='Nota libre',
      'accepted A sale cleared another context');
    document.body.dataset.smoke='PASS';
  }catch(error){document.body.dataset.smoke='FAIL: '+error.message}
});</script></body></html>"""
        Path(directory, "switch.html").write_text(switch_fixture)
        switched = subprocess.run([
            chromium, "--headless", "--no-sandbox", "--disable-gpu",
            "--disable-dev-shm-usage", f"--user-data-dir={directory}/switch-profile",
            "--virtual-time-budget=5000", "--dump-dom",
            f"http://127.0.0.1:{server.server_port}/switch.html"
        ], text=True, capture_output=True, timeout=30)
        if switched.returncode != 0 or 'data-smoke="PASS"' not in switched.stdout:
            marker = switched.stdout.split('data-smoke="')[-1].split('"')[0]
            raise SystemExit(f"POS context switching failed: {marker or switched.stderr[-800:]}")
        print("POS switching PASS: A/B/no-table restore, accepted sale clears only A")
    finally:
        server.shutdown()
