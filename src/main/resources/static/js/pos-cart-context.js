// Estado del POS limitado a esta pestaña. La clave incluye la mesa o el contexto sin mesa.
window.PosCartContexts = (() => {
    const prefix = 'ventro_pos_context:';
    const legacyCart = 'pos_cart';
    const legacyPending = 'pos_pending_sale';
    const migrated = prefix + 'legacy_resolved';

    function forMesa(id) {
        const value = String(id ?? '');
        return /^[1-9]\d*$/.test(value) ? 'mesa:' + value : 'sin-mesa';
    }

    function key(context) {
        if (context !== 'sin-mesa' && !/^mesa:[1-9]\d*$/.test(context)) {
            throw new Error('Contexto de POS inválido');
        }
        return prefix + context;
    }

    function read(context) {
        try {
            const saved = JSON.parse(sessionStorage.getItem(key(context)) || 'null');
            return saved && typeof saved === 'object' && Array.isArray(saved.cart) ? saved : null;
        } catch (_) {
            return null;
        }
    }

    function write(context, state) {
        sessionStorage.setItem(key(context), JSON.stringify(state));
    }

    function clear(context) {
        sessionStorage.removeItem(key(context));
    }

    function hasPending(context) {
        const state = read(context);
        return Boolean(state && (state.cart.length || state.pendingSale ||
            (Array.isArray(state.splitCartToPay) && state.splitCartToPay.length)));
    }

    function legacyStatus(context) {
        if (sessionStorage.getItem(migrated)) return null;
        const oldCart = sessionStorage.getItem(legacyCart);
        const oldPending = sessionStorage.getItem(legacyPending);
        if (oldCart === null && oldPending === null) return null;
        try {
            const cart = oldCart === null ? [] : JSON.parse(oldCart);
            const pendingSale = oldPending === null ? null : JSON.parse(oldPending);
            if (!Array.isArray(cart) || (pendingSale && (!pendingSale.key || !pendingSale.signature))) {
                return { canAdopt: false, reason: 'El estado antiguo no es válido.' };
            }
            let pendingContext = null;
            if (pendingSale) {
                const mesaId = JSON.parse(pendingSale.signature).mesaId;
                if (mesaId !== '' && !/^[1-9]\d*$/.test(String(mesaId))) {
                    return { canAdopt: false, reason: 'No se pudo identificar la mesa del cobro pendiente.' };
                }
                pendingContext = forMesa(mesaId);
            }
            const existing = read(context);
            const occupied = existing && (existing.cart.length || existing.pendingSale ||
                existing.clienteId || (existing.splitCartToPay && existing.splitCartToPay.length));
            const canAdopt = !occupied && (!pendingContext || pendingContext === context);
            return { cart, pendingSale, canAdopt,
                reason: pendingContext && pendingContext !== context
                    ? 'El cobro antiguo pertenece a ' + pendingContext + '.'
                    : occupied ? 'Este contexto ya tiene un carrito.' : '' };
        } catch (_) {
            return { canAdopt: false, reason: 'El estado antiguo no se puede leer.' };
        }
    }

    function resolveLegacy(context, adopt) {
        const legacy = legacyStatus(context);
        if (!legacy || (adopt && !legacy.canAdopt)) return false;
        if (adopt) write(context, { cart: legacy.cart, pendingSale: legacy.pendingSale });
        sessionStorage.removeItem(legacyCart);
        sessionStorage.removeItem(legacyPending);
        sessionStorage.setItem(migrated, '1');
        return true;
    }

    return { forMesa, read, write, clear, hasPending, legacyStatus, resolveLegacy };
})();
