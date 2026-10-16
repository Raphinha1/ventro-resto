/* Conserva el intento HTTP; la compensación y los permisos pertenecen al servicio. */
(() => {
    'use strict';
    const app = document.getElementById('reversal-app');
    if (!app) return;
    const form = document.getElementById('reversal-form');
    const fields = document.getElementById('reversal-fields');
    const reason = document.getElementById('reversal-reason');
    const message = document.getElementById('reversal-message');
    const confirm = document.getElementById('confirm-reversal');
    const retry = document.getElementById('retry-reversal');
    const detail = document.getElementById('reversal-detail');
    const url = '/inventario/recepciones/' + app.dataset.receipt;
    const alreadyReversed = !!app.dataset.reversal;
    const key = 'ventro.receipt-reversal.v1:' + app.dataset.restaurant + ':' + app.dataset.actor + ':' + app.dataset.receipt;
    let storageAvailable = true;
    let state;
    function notify(text, error = false) {
        message.textContent = text;
        message.className = 'alert ' + (error ? 'alert-danger' : 'alert-info');
        message.focus();
    }
    try {
        const stored = sessionStorage.getItem(key);
        state = stored ? JSON.parse(stored) : {version: 1, requestId: app.dataset.request || '', motivo: '', status: alreadyReversed ? 'YA_REVERTIDA' : 'PREPARANDO', sent: null};
        if (!state || state.version !== 1 || typeof state.requestId !== 'string' || typeof state.motivo !== 'string' ||
            !['PREPARANDO','ENVIANDO','RESULTADO_DESCONOCIDO','RECHAZO_DEFINITIVO','CONFLICTO_REQUEST_ID','YA_REVERTIDA','REVERTIDA'].includes(state.status) ||
            (['ENVIANDO','RESULTADO_DESCONOCIDO'].includes(state.status) && typeof state.sent !== 'string')) {
            throw new Error('Intento guardado inválido');
        }
    } catch (_) {
        // No reemplazar una clave ambigua que podría corresponder a un commit ya aceptado.
        storageAvailable = false;
        state = {requestId: '', motivo: '', status: 'RESULTADO_DESCONOCIDO', sent: null};
        notify('No se puede recuperar el intento. Consulta el estado de la recepción antes de continuar.', true);
    }
    if (state.status === 'ENVIANDO') state.status = 'RESULTADO_DESCONOCIDO';
    if (alreadyReversed && !['RESULTADO_DESCONOCIDO','REVERTIDA'].includes(state.status)) state.status = 'YA_REVERTIDA';
    function save() {
        if (!storageAvailable) return false;
        try { sessionStorage.setItem(key, JSON.stringify(state)); return true; }
        catch (_) {
            storageAvailable = false;
            notify('No se puede conservar el intento en esta pestaña. Habilita almacenamiento y consulta el estado antes de continuar.', true);
            return false;
        }
    }
    function frozen() { return ['ENVIANDO','RESULTADO_DESCONOCIDO','CONFLICTO_REQUEST_ID','YA_REVERTIDA','REVERTIDA'].includes(state.status); }
    function controls() {
        fields.disabled = frozen() || !storageAvailable || alreadyReversed;
        confirm.disabled = frozen() || !storageAvailable || alreadyReversed;
        retry.hidden = state.status !== 'RESULTADO_DESCONOCIDO';
        retry.disabled = !storageAvailable || !state.sent;
        document.getElementById('reversal-request').textContent = state.requestId;
        if (state.status === 'REVERTIDA' && Number.isSafeInteger(state.reversalId) && state.reversalId > 0) {
            detail.href = url + '/reversos/' + state.reversalId;
            detail.hidden = false;
        }
    }
    reason.value = state.motivo;
    reason.addEventListener('input', () => {
        if (frozen()) return;
        state.motivo = reason.value;
        reason.removeAttribute('aria-invalid');
        save(); controls();
    });
    async function send(payload) {
        state.sent = payload; // Cadena exacta: un retry no vuelve a leer el formulario.
        state.status = 'ENVIANDO';
        if (!save()) { controls(); return; }
        controls(); notify('Confirmando reverso completo…');
        try {
            const response = await fetch(url + '/reversos', {method: 'POST', headers: {
                'Content-Type': 'application/json', Accept: 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            }, body: payload});
            if (response.redirected || !response.headers.get('content-type')?.includes('application/json')) {
                throw new Error('Respuesta sin confirmación. Revisa tu sesión y reintenta exactamente el mismo envío.');
            }
            const data = await response.json();
            if (response.ok && data.estado === 'REVERTIDA' && String(data.receiptId) === app.dataset.receipt &&
                data.requestId === state.requestId && Number.isSafeInteger(data.reversalId) && data.reversalId > 0 &&
                data.detailUrl === url + '/reversos/' + data.reversalId) {
                state.status = 'REVERTIDA'; state.reversalId = data.reversalId;
                notify('Reverso #' + data.reversalId + ' confirmado. La recepción está REVERTIDA.');
            } else if (!response.ok && data.sinEfectos === true && typeof data.mensaje === 'string' && typeof data.codigo === 'string') {
                state.status = ['CONFLICTO_REQUEST_ID','YA_REVERTIDA'].includes(data.codigo) ? data.codigo : 'RECHAZO_DEFINITIVO';
                notify(data.codigo + ': ' + data.mensaje, true);
                if (data.codigo === 'VALIDACION') reason.setAttribute('aria-invalid', 'true');
            } else throw new Error('Resultado no confirmado. Conserva el intento y reintenta exactamente el mismo envío.');
        } catch (error) {
            state.status = 'RESULTADO_DESCONOCIDO';
            notify(error.message || 'Resultado desconocido: reintenta exactamente el mismo envío.', true);
        }
        save(); controls();
    }
    form.onsubmit = event => {
        event.preventDefault();
        if (frozen() || alreadyReversed || !storageAvailable || !form.reportValidity()) return;
        state.motivo = reason.value;
        if (!state.motivo.trim()) { notify('El motivo es obligatorio.', true); reason.focus(); return; }
        return send(JSON.stringify({requestId: state.requestId, motivo: state.motivo}));
    };
    retry.onclick = () => {
        if (state.status === 'RESULTADO_DESCONOCIDO' && storageAvailable && state.sent) return send(state.sent);
    };
    save(); controls();
    if (storageAvailable && state.status === 'RESULTADO_DESCONOCIDO') notify('Hay un envío sin resultado confirmado. Reintenta exactamente el mismo payload; no cambies motivo ni clave.', true);
    if (state.status === 'CONFLICTO_REQUEST_ID') notify('CONFLICTO_REQUEST_ID: la clave corresponde a otro comando. Consulta el estado; no se genera otra clave automáticamente.', true);
    if (state.status === 'YA_REVERTIDA') notify('YA_REVERTIDA: consulta el reverso auditado desde la recepción.', true);
    if (state.status === 'REVERTIDA') notify('Reverso confirmado. Consulta su detalle auditado.');
})();
