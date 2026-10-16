#!/usr/bin/env node
// Ejecuta el cliente real: almacenamiento/retry sin servidor; PostgreSQL y rutas se prueban aparte.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/js/receipt-reversal-client.js'), 'utf8');
const storage = new Map();
const requests = [];
function page({actor = '1', receipt = '10', request = 'attempt-1', reversal = '', respond} = {}) {
    const elements = {};
    for (const id of ['reversal-app','reversal-form','reversal-fields','reversal-reason','reversal-message',
        'confirm-reversal','retry-reversal','reversal-detail','reversal-request']) {
        elements[id] = {value: '', hidden: true, disabled: false, textContent: '', listeners: {},
            focus() {}, addEventListener(name, callback) { this.listeners[name] = callback; },
            removeAttribute() {}, setAttribute() {}, reportValidity() { return true; }};
    }
    elements['reversal-app'].dataset = {restaurant: 'synthetic', actor, receipt, request, reversal};
    vm.runInNewContext(source, {document: {getElementById: id => elements[id], querySelector: () => ({content: 'csrf'})},
        sessionStorage: {getItem: key => storage.get(key) || null, setItem: (key,value) => storage.set(key,value)},
        fetch: async (url, options) => { requests.push({url, body: options.body}); return respond(options.body); }});
    return {elements, key: 'ventro.receipt-reversal.v1:synthetic:' + actor + ':' + receipt,
        async submit(reason) { elements['reversal-reason'].value = reason; await elements['reversal-form'].onsubmit({preventDefault() {}}); },
        async retry() { await elements['retry-reversal'].onclick(); }};
}
function accepted(body) {
    const payload = JSON.parse(body);
    return {ok: true, redirected: false, headers: {get: () => 'application/json'}, json: async () => ({
        estado: 'REVERTIDA', receiptId: 10, reversalId: 7, requestId: payload.requestId,
        detailUrl: '/inventario/recepciones/10/reversos/7'})};
}
(async () => {
    let first = true;
    let p = page({respond: body => { if (first) { first = false; throw new Error('Respuesta perdida tras commit'); } return accepted(body); }});
    await p.submit('Motivo <script> & "texto"');
    assert.equal(JSON.parse(storage.get(p.key)).status, 'RESULTADO_DESCONOCIDO');
    assert.equal(p.elements['reversal-fields'].disabled, true);
    const exactBody = requests.at(-1).body;
    // Recarga después del commit: el servidor ya conoce el reverso, pero conserva retry del intento guardado.
    p = page({reversal: '7', request: 'another-render', respond: accepted});
    await p.retry();
    assert.equal(requests.at(-1).body, exactBody);
    assert.equal(JSON.parse(storage.get(p.key)).requestId, 'attempt-1');
    assert.equal(JSON.parse(storage.get(p.key)).status, 'REVERTIDA');
    assert.equal(p.elements['reversal-detail'].href, '/inventario/recepciones/10/reversos/7');
    // Otro usuario no hereda el intento ni puede iniciar un segundo reverso ya confirmado.
    p = page({actor: '2', reversal: '7'});
    assert.equal(p.elements['confirm-reversal'].disabled, true);
    const count = requests.length;
    await p.submit('Segundo reverso');
    assert.equal(requests.length, count);
    // Otro contexto conserva su propio motivo y UUID, y un rechazo permite retry sin generar clave.
    p = page({receipt: '11', request: 'attempt-11', respond: () => ({ok: false, redirected: false,
        headers: {get: () => 'application/json'}, json: async () => ({codigo: 'SALDO_INSUFICIENTE', mensaje: 'Saldo insuficiente', sinEfectos: true})})});
    await p.submit('Error de captura');
    assert.equal(JSON.parse(storage.get(p.key)).requestId, 'attempt-11');
    assert.equal(JSON.parse(storage.get(p.key)).status, 'RECHAZO_DEFINITIVO');
    assert.match(p.elements['reversal-message'].textContent, /SALDO_INSUFICIENTE/);
    assert.equal(JSON.parse(storage.get('ventro.receipt-reversal.v1:synthetic:1:10')).motivo, 'Motivo <script> & "texto"');
    // Redirigir a login o devolver HTML/200 jamás equivale a confirmación.
    p = page({receipt: '12', request: 'attempt-12', respond: () => ({ok: true, redirected: true, headers: {get: () => 'text/html'}})});
    await p.submit('Motivo');
    assert.equal(JSON.parse(storage.get(p.key)).status, 'RESULTADO_DESCONOCIDO');
    console.log('OK: payload exacto, respuesta perdida/recarga, aislamiento, rechazo y sesión no confirmada');
})().catch(error => { console.error(error); process.exitCode = 1; });
