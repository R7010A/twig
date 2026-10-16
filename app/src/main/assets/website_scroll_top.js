// Fractions are relative to the visible WebView, before any pinch or desktop-page scaling.
((fractionX, fractionY) => {
    const viewport = window.visualViewport;
    const x = (viewport ? viewport.offsetLeft : 0) + fractionX * (viewport ? viewport.width : innerWidth);
    const y = (viewport ? viewport.offsetTop : 0) + fractionY * (viewport ? viewport.height : innerHeight);

    function atTop(win, x, y, depth) {
        try {
            if (depth > 8) return false;
            const doc = win.document;
            if (doc.scrollingElement && Math.abs(doc.scrollingElement.scrollTop) > 0.5) return false;
            let node = doc.elementFromPoint(x, y);
            if (!node) return false;
            // An open shadow root can contain the actual scroller behind its host.
            while (node.shadowRoot && node.shadowRoot.elementFromPoint) {
                const inner = node.shadowRoot.elementFromPoint(x, y);
                if (!inner || inner === node) break;
                node = inner;
            }
            if (node.localName === 'iframe' || node.localName === 'frame') {
                // Cross-origin contents are unknown: leave their gestures to the frame.
                if (!node.contentDocument || !node.contentWindow) return false;
                const rect = node.getBoundingClientRect();
                if (!rect.width || !rect.height) return false;
                const childX = (x - rect.left) * node.offsetWidth / rect.width - node.clientLeft;
                const childY = (y - rect.top) * node.offsetHeight / rect.height - node.clientTop;
                if (!atTop(node.contentWindow, childX, childY, depth + 1)) return false;
            }
            for (let current = node; current;) {
                if (current.nodeType === 1 && current.scrollHeight > current.clientHeight + 1 &&
                        Math.abs(current.scrollTop) > 0.5) {
                    const overflow = win.getComputedStyle(current).overflowY;
                    if (overflow !== 'visible' && overflow !== 'clip') return false;
                }
                current = current.assignedSlot || current.parentElement ||
                    (current.getRootNode && current.getRootNode().host);
            }
            return true;
        } catch (_) {
            // A failed probe must never turn an unknown scroll position into a refresh.
            return false;
        }
    }
    return atTop(window, x, y, 0);
})
