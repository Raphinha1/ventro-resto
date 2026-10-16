(() => {
    'use strict';
    const app = document.getElementById('presentation-app');
    if (!app) return;
    const message = document.getElementById('presentation-message');
    const path = '/insumos/' + encodeURIComponent(app.dataset.insumo) + '/presentaciones';
    const mode = document.getElementById('presentation-mode');
    const factor = document.getElementById('presentation-factor');
    mode.onchange = () => {
        factor.disabled = mode.value === 'VARIABLE';
        factor.required = !factor.disabled;
        document.getElementById('factor-group').hidden = factor.disabled;
    };
    let sending = false;
    async function post(url, body) {
        if (sending) return;
        sending = true;
        app.querySelectorAll('button').forEach(b => b.disabled = true);
        try {
            const response = await fetch(url, {method: 'POST', headers: {
                'Content-Type': 'application/json', Accept: 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            }, body: JSON.stringify(body)});
            if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('Sin confirmación: revisa tu sesión y consulta el catálogo antes de repetir.');
            const result = await response.json();
            if (!response.ok) throw new Error(result.mensaje || 'Operación no confirmada. Consulta el catálogo antes de repetir.');
            if (!Number.isSafeInteger(result.id)) throw new Error('Respuesta no reconocida. Consulta el catálogo.');
            location.reload();
        } catch (error) {
            message.textContent = error.message;
            message.className = 'alert alert-danger'; message.focus();
        } finally { sending = false; app.querySelectorAll('button').forEach(b => b.disabled = false); }
    }
    document.getElementById('presentation-create').onsubmit = e => {
        e.preventDefault();
        if (!e.target.reportValidity()) return;
        const data = new FormData(e.target);
        post(path, {nombre: data.get('nombre'), unidadCompra: data.get('unidadCompra'), modo: data.get('modo'),
            factor: mode.value === 'FIJA' ? data.get('factor') : null});
    };
    document.querySelectorAll('[data-rename]').forEach(form => {
        form.onsubmit = e => { e.preventDefault(); if (form.reportValidity()) post(path + '/' + form.dataset.id + '/nombre', {nombre: new FormData(form).get('nombre')}); };
    });
    document.querySelectorAll('[data-state]').forEach(button => {
        button.onclick = () => post(path + '/' + button.dataset.id + '/estado', {estado: button.dataset.state});
    });
})();
