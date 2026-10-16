/* Solo captura/retry: la autoridad numérica, permisos e inventario viven en ReceiptService. */
(() => {
    'use strict';
    const app = document.getElementById('receipt-app');
    if (!app) return;
    const form = document.getElementById('receipt-form');
    const fields = document.getElementById('receipt-fields');
    const container = document.getElementById('receipt-lines');
    const message = document.getElementById('receipt-message');
    const confirm = document.getElementById('confirm-receipt');
    const retry = document.getElementById('retry-receipt');
    const fresh = document.getElementById('new-receipt');
    const detail = document.getElementById('receipt-detail');
    const key = 'ventro.receipt.v1:' + app.dataset.restaurant + ':' + app.dataset.actor;
    let state;
    let storageAvailable = true;
    try { state = JSON.parse(sessionStorage.getItem(key) || 'null'); }
    catch (_) { storageAvailable = false; }
    if (!state || state.version !== 1) state = initial();
    // Un envío interrumpido por recarga tiene resultado desconocido.
    if (state.status === 'ENVIANDO') state.status = 'RESULTADO_DESCONOCIDO';
    function initial() {
        return {version: 1, requestId: crypto.randomUUID(), currency: app.dataset.currency, supplierId: '',
            externalDocument: '', nextLine: 1, lines: [], status: 'PREPARANDO', sent: null};
    }
    function save() {
        try { sessionStorage.setItem(key, JSON.stringify(state)); }
        catch (_) {
            storageAvailable = false;
            notify('No se puede conservar el intento en esta pestaña. Habilita almacenamiento antes de confirmar.', true);
        }
    }
    function notify(text, error = false) {
        message.textContent = text;
        message.className = 'alert ' + (error ? 'alert-danger' : 'alert-info');
        if (error) message.focus();
    }
    function frozen() { return ['ENVIANDO','RESULTADO_DESCONOCIDO','CONFLICTO_REQUEST_ID','CONFIRMADA'].includes(state.status); }
    function controls() {
        fields.disabled = frozen();
        confirm.disabled = frozen() || !state.currency || !storageAvailable;
        retry.hidden = state.status !== 'RESULTADO_DESCONOCIDO';
        fresh.hidden = state.status !== 'CONFIRMADA';
        document.getElementById('receipt-request').textContent = state.requestId;
        document.getElementById('receipt-currency').value = state.currency;
    }
    function decimal(text) {
        if (!/^(?:\d+)(?:\.\d+)?$/.test(text) || text.length > 80) throw new Error('Cantidad decimal inválida');
        const [whole, fraction = ''] = text.split('.');
        return {n: BigInt(whole + fraction), scale: fraction.length};
    }
    function multiply(a, b) {
        const x = decimal(a), y = decimal(b);
        const scale = x.scale + y.scale;
        let raw = (x.n * y.n).toString().padStart(scale + 1, '0');
        if (scale) raw = raw.slice(0, -scale) + '.' + raw.slice(-scale);
        return raw.includes('.') ? raw.replace(/0+$/, '').replace(/\.$/, '') : raw;
    }
    function draw(line) {
        const node = document.getElementById('receipt-line-template').content.firstElementChild.cloneNode(true);
        node.dataset.lineNo = line.lineNo;
        node.querySelector('legend').textContent = 'Línea ' + line.lineNo;
        const inputs = {};
        node.querySelectorAll('[data-field]').forEach(input => {
            const field = input.dataset.field;
            input.id = 'line-' + line.lineNo + '-' + field;
            input.setAttribute('aria-describedby', 'receipt-help receipt-message');
            node.querySelector('[data-label="' + field + '"]').htmlFor = input.id;
            input.value = line[field] || '';
            inputs[field] = input;
            input.addEventListener('input', () => {
                line[field] = input.value;
                input.removeAttribute('aria-invalid');
                preview(); save();
            });
        });
        function preview() {
            const variable = line.mode === 'VARIABLE';
            node.querySelector('[data-base]').hidden = !variable;
            inputs.receivedBase.required = variable;
            inputs.receivedBase.disabled = !variable;
            let text = '';
            if (line.mode === 'FIJA') {
                try { text = 'Base calculada: ' + multiply(line.purchased, line.factor) + ' ' + line.base + ' (informativa)'; }
                catch (_) { text = 'Factor: ' + line.factor + ' ' + line.base + '; captura cantidad para calcular base.'; }
            } else if (variable) text = 'Captura el total real medido en ' + line.base + '; no se aplica factor.';
            node.querySelector('[data-preview]').textContent = text;
        }
        async function load(selected) {
            const owner = line.ingredient;
            const select = inputs.presentation;
            select.replaceChildren(new Option(owner ? 'Cargando presentaciones…' : 'Selecciona ingrediente primero', ''));
            const manage = node.querySelector('[data-manage]');
            if (manage) { manage.hidden = !owner; manage.href = '/insumos/' + encodeURIComponent(owner) + '/presentaciones'; }
            if (!owner) return;
            try {
                const response = await fetch('/insumos/' + encodeURIComponent(owner) + '/presentaciones?captura=true', {headers: {Accept: 'application/json'}});
                if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) throw new Error('No se pudieron consultar presentaciones. Revisa tu sesión y permiso.');
                const rows = await response.json();
                if (line.ingredient !== owner || !node.isConnected) return;
                select.replaceChildren(new Option(rows.length ? 'Selecciona presentación' : 'No hay presentaciones activas', ''));
                rows.forEach(p => select.add(new Option(p.nombre + ' · ' + p.modo + ' · ' + p.unidadCompra, String(p.id))));
                if (selected && !rows.some(p => String(p.id) === selected)) {
                    select.add(new Option('Presentación anterior no disponible: ' + selected, selected));
                }
                select.value = selected || '';
                select.onchange = () => {
                    line.presentation = select.value;
                    const p = rows.find(p => String(p.id) === select.value);
                    line.mode = p?.modo || ''; line.factor = p?.factor || ''; line.base = p?.unidadBase || '';
                    line.receivedBase = ''; inputs.receivedBase.value = '';
                    preview(); save();
                };
                // No reinterpretar un intento guardado según catálogo actual.
                preview();
            } catch (error) {
                if (line.ingredient !== owner || !node.isConnected) return;
                select.replaceChildren(new Option(selected ? 'Presentación guardada: ' + selected : 'Consulta no disponible', selected || ''));
                notify(error.message, true);
            }
        }
        inputs.ingredient.addEventListener('change', () => {
            line.ingredient = inputs.ingredient.value;
            line.presentation = ''; line.mode = ''; line.factor = ''; line.base = ''; line.receivedBase = '';
            inputs.receivedBase.value = ''; preview(); save(); load('');
        });
        node.querySelector('[data-remove]').setAttribute('aria-label', 'Quitar línea ' + line.lineNo);
        node.querySelector('[data-remove]').onclick = () => {
            state.lines = state.lines.filter(l => l.lineNo !== line.lineNo);
            node.remove(); save();
        };
        container.append(node);
        preview(); load(line.presentation);
    }
    function addLine() {
        const line = {lineNo: state.nextLine++, ingredient: '', presentation: '', purchased: '', receivedBase: '', cost: '', mode: '', factor: '', base: ''};
        state.lines.push(line); draw(line); save();
    }
    document.getElementById('supplier').value = state.supplierId;
    // Un proveedor desactivado no altera un intento histórico guardado.
    if (state.supplierId && !document.getElementById('supplier').value) {
        document.getElementById('supplier').add(new Option('Proveedor guardado: ' + state.supplierId, state.supplierId));
        document.getElementById('supplier').value = state.supplierId;
    }
    document.getElementById('document').value = state.externalDocument;
    document.getElementById('supplier').onchange = e => { state.supplierId = e.target.value; save(); };
    document.getElementById('document').oninput = e => { state.externalDocument = e.target.value; save(); };
    state.lines.forEach(draw);
    if (!state.lines.length && !frozen()) addLine();
    document.getElementById('add-line').onclick = addLine;
    function command() {
        return {requestId: state.requestId, currency: state.currency, supplierId: state.supplierId || null,
            externalDocument: state.externalDocument || null,
            lines: state.lines.map(l => ({lineNo: l.lineNo, insumoId: l.ingredient, presentationId: l.presentation,
                purchased: l.purchased, receivedBase: l.mode === 'VARIABLE' ? l.receivedBase : null, cost: l.cost}))};
    }
    async function send(payload) {
        state.sent = payload;
        state.status = 'ENVIANDO';
        save(); controls();
        if (!storageAvailable) { state.status = 'PREPARANDO'; controls(); return; }
        notify('Confirmando recepción…');
        retry.disabled = true;
        try {
            const response = await fetch('/inventario/recepciones', {method: 'POST', headers: {
                'Content-Type': 'application/json', Accept: 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            }, body: JSON.stringify(payload)});
            if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('Respuesta sin confirmación. Revisa tu sesión y reintenta el mismo envío.');
            const data = await response.json();
            if (response.ok && data.estado === 'CONFIRMADA' && data.requestId === state.requestId &&
                Number.isSafeInteger(data.receiptId) && data.detailUrl === '/inventario/recepciones/' + data.receiptId) {
                state.status = 'CONFIRMADA';
                detail.href = data.detailUrl; detail.hidden = false;
                sessionStorage.removeItem(key);
                notify('Recepción #' + data.receiptId + ' confirmada. Otra recepción debe iniciarse con Nueva recepción.');
                message.focus();
            } else if (!response.ok && data.sinEfectos === true && typeof data.mensaje === 'string') {
                state.status = data.codigo === 'CONFLICTO_REQUEST_ID' ? 'CONFLICTO_REQUEST_ID' : 'RECHAZO_DEFINITIVO';
                notify(data.mensaje, true);
                if (state.status === 'RECHAZO_DEFINITIVO') {
                    container.querySelectorAll('input,select').forEach(i => i.setAttribute('aria-invalid', 'true'));
                }
                save();
            } else throw new Error('Resultado no confirmado. Conserva este intento y reintenta el mismo envío.');
        } catch (error) {
            state.status = 'RESULTADO_DESCONOCIDO';
            save(); notify(error.message || 'Resultado desconocido: reintenta el mismo envío.', true);
        } finally { controls(); retry.disabled = false; }
    }
    form.onsubmit = e => {
        e.preventDefault();
        if (frozen() || !storageAvailable) return;
        if (!state.lines.length) { notify('Añade al menos una línea.', true); return; }
        if (form.reportValidity()) send(command());
    };
    retry.onclick = () => { if (state.status === 'RESULTADO_DESCONOCIDO' && state.sent) send(state.sent); };
    fresh.onclick = () => { if (state.status === 'CONFIRMADA') { sessionStorage.removeItem(key); location.reload(); } };
    window.addEventListener('beforeunload', e => {
        if (state.status !== 'CONFIRMADA' && state.lines.some(l => l.ingredient || l.purchased || l.cost)) {
            e.preventDefault(); e.returnValue = '';
        }
    });
    save(); controls();
    if (state.status === 'RESULTADO_DESCONOCIDO') notify('Hay un envío sin resultado confirmado. Reintenta exactamente el mismo envío; no edites ni generes otra clave.', true);
    if (state.status === 'CONFLICTO_REQUEST_ID') notify('Este intento corresponde a otra recepción. Consulta el historial; no cambies la clave automáticamente.', true);
})();
