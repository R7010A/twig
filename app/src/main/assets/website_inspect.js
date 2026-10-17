// Only the main document is inspected. Frames and shadow trees are represented by their host.
((key, action, fractionX, fractionY, color) => {
    try {
        const previous = window[key];
        let node = action === 'parent' && previous ? previous.element.parentElement : null;
        if (previous && typeof previous.dispose === 'function') previous.dispose();
        if (action === 'clear') return null;
        if (action === 'pick') {
            const viewport = window.visualViewport;
            const x = (viewport ? viewport.offsetLeft : 0) + fractionX * (viewport ? viewport.width : innerWidth);
            const y = (viewport ? viewport.offsetTop : 0) + fractionY * (viewport ? viewport.height : innerHeight);
            node = document.elementFromPoint(x, y);
        }
        if (!node || node.nodeType !== 1 || !node.isConnected) return null;

        function selectorFor(element) {
            const parts = [];
            for (let current = element, depth = 0; current && depth < 64; current = current.parentElement, depth++) {
                if (current.id && current.id.length < 256) {
                    const id = '#' + CSS.escape(current.id);
                    if (document.querySelectorAll(id).length === 1) {
                        parts.unshift(id);
                        break;
                    }
                }
                let part = CSS.escape(current.localName);
                for (const name of Array.from(current.classList).filter(value => value.length < 128).slice(0, 2)) {
                    part += '.' + CSS.escape(name);
                }
                if (current.parentElement) {
                    const siblings = Array.from(current.parentElement.children).filter(sibling =>
                        sibling.localName === current.localName && sibling.namespaceURI === current.namespaceURI);
                    if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
                }
                parts.unshift(part);
                const path = parts.join(' > ');
                const found = document.querySelectorAll(path);
                if (found.length === 1 && found[0] === element) break;
            }
            return parts.join(' > ');
        }
        const selector = selectorFor(node);
        // Snapshot before adding the tool overlay, including when inspecting <html> itself.
        const html = node.outerHTML;
        const result = {
            tag: node.localName,
            id: node.id.slice(0, 256),
            classes: Array.from(node.classList).slice(0, 20).map(value => value.slice(0, 128)).join(' '),
            selector: selector,
            matches: document.querySelectorAll(selector).length,
            html: html.slice(0, 32768),
            truncated: html.length > 32768,
            hasParent: !!node.parentElement
        };
        const overlay = document.createElement('div');
        overlay.setAttribute('aria-hidden', 'true');
        overlay.setAttribute('data-twig-inspector', key);
        const style = overlay.style;
        const set = (name, value) => style.setProperty(name, value, 'important');
        set('all', 'initial');
        set('position', 'fixed');
        set('display', 'block');
        set('pointer-events', 'none');
        set('box-sizing', 'border-box');
        set('margin', '0');
        set('padding', '0');
        set('right', 'auto');
        set('bottom', 'auto');
        set('transform', 'none');
        set('z-index', '2147483647');
        set('border', '2px solid ' + color);
        set('background', 'transparent');
        const state = {
            element: node,
            frame: 0,
            dispose() {
                cancelAnimationFrame(this.frame);
                overlay.remove();
                if (window[key] === this) delete window[key];
            }
        };
        window[key] = state;
        document.documentElement.appendChild(overlay);
        let last = '';
        const update = () => {
            if (!node.isConnected || window[key] !== state) { state.dispose(); return; }
            const rect = node.getBoundingClientRect();
            const value = [rect.left, rect.top, rect.width, rect.height].join(',');
            if (value !== last) {
                set('left', rect.left + 'px');
                set('top', rect.top + 'px');
                set('width', rect.width + 'px');
                set('height', rect.height + 'px');
                last = value;
            }
            state.frame = requestAnimationFrame(update);
        };
        update();
        return result;
    } catch (_) {
        return null;
    }
})
