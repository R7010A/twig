#!/usr/bin/env python3
"""Run the element inspector's production WebView commands in a local Chromium page.

Run WebsiteDebugTest first to export app/build/reports/website-elements/scripts.json.
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser")
    args = parser.parse_args()
    scripts = json.loads((ROOT / "app/build/reports/website-elements/scripts.json").read_text())
    checks = 0

    def check(condition, name):
        nonlocal checks
        assert condition, name
        checks += 1
        print("PASS:", name)

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=args.browser)
        page = browser.new_page(viewport={"width": 800, "height": 600})
        page.route("**/*", lambda route: route.fulfill(content_type="text/html", headers={
            "Content-Security-Policy": "default-src 'none'; style-src 'none'; script-src 'none'; frame-src https:"
        }, body="<meta name='viewport' content='width=device-width,initial-scale=1'><html><body></body></html>"))
        page.goto("https://twig.test/page")

        def run(name):
            return page.evaluate(scripts[name])

        def setup(markup):
            run("clear")
            # Test fixtures use DOM styles rather than relaxing the page's strict CSP.
            page.evaluate("""markup => {
                document.body.innerHTML = markup;
                document.documentElement.style.cssText = 'margin:0;padding:0;width:100%;height:100%';
                document.body.style.cssText = 'margin:0;width:100%;height:100%;display:flex;align-items:center;justify-content:center';
                const target = document.querySelector('[data-target]');
                target.style.cssText = 'display:block;width:140px;height:90px;box-sizing:border-box';
                window.scrollTo(0,0);
            }""", markup)

        def picked_target(result):
            return page.evaluate("selector => document.querySelector(selector) === document.querySelector('[data-target]')", result["selector"])

        setup('<a data-target id="123:你好[foo]" class="link" href="https://other.test/">Link</a>')
        result = run("pick")
        check(result["tag"] == "a" and result["id"] == "123:你好[foo]", "inspector returns element metadata")
        check(picked_target(result) and result["matches"] == 1, "escaped Unicode and punctuation ID selector resolves to the selected node")
        check('href="https://other.test/"' in result["html"], "HTML is returned as text with attributes intact")
        check(page.locator('[data-twig-inspector]').count() == 1, "one highlight overlay is installed")
        check(page.evaluate("getComputedStyle(document.querySelector('[data-twig-inspector]')).pointerEvents") == "none", "highlight never intercepts page input")
        check(page.evaluate("getComputedStyle(document.querySelector('[data-twig-inspector]')).borderTopWidth") == "2px", "highlight remains visible under strict style CSP")
        check(page.evaluate("document.elementFromPoint(400,300) === document.querySelector('[data-target]')"), "hit testing ignores the tool overlay")
        run("pick")
        check(page.locator('[data-twig-inspector]').count() == 1, "reselecting replaces rather than stacks highlights")
        parent = run("parent")
        check(parent["tag"] == "body" and parent["hasParent"], "parent selection moves to the containing element")
        root = run("parent")
        check(root["tag"] == "html" and not root["hasParent"], "document root correctly has no element parent")
        check("data-twig-inspector" not in root["html"], "copied root HTML excludes the tool overlay")
        run("clear")
        check(page.locator('[data-twig-inspector]').count() == 0, "clear removes the highlight")

        setup('<section><div class="same">First</div><div data-target class="same">Target</div><div class="same">Last</div></section>')
        result = run("pick")
        check(picked_target(result) and result["matches"] == 1, "nth-of-type selector distinguishes repeated sibling classes")
        setup('<section><div id="duplicate">First</div><div data-target id="duplicate">Target</div></section>')
        result = run("pick")
        check(picked_target(result) and result["matches"] == 1, "duplicate IDs do not produce an ambiguous selector")

        setup('<div data-target id="moving">Move</div>')
        run("pick")
        page.evaluate("document.querySelector('[data-target]').style.transform='translate(50px,30px)'")
        page.wait_for_function("""() => {
            const a=document.querySelector('[data-target]').getBoundingClientRect();
            const b=document.querySelector('[data-twig-inspector]').getBoundingClientRect();
            return Math.abs(a.left-b.left)<0.5 && Math.abs(a.top-b.top)<0.5;
        }""")
        check(True, "highlight follows element movement")
        page.evaluate("document.body.style.height='1800px'; window.scrollTo(0,400)")
        page.wait_for_function("""() => {
            const a=document.querySelector('[data-target]').getBoundingClientRect();
            const b=document.querySelector('[data-twig-inspector]').getBoundingClientRect();
            return Math.abs(a.top-b.top)<0.5;
        }""")
        check(True, "highlight follows scrolling")
        page.evaluate("document.querySelector('[data-target]').remove()")
        page.wait_for_function("() => document.querySelector('[data-twig-inspector]') === null")
        check(True, "removing the selected element cleans up its highlight")

        setup('<div data-target id="large"></div>')
        page.evaluate("document.querySelector('[data-target]').textContent='x'.repeat(40000)")
        result = run("pick")
        check(result["truncated"] and len(result["html"]) == 32768, "large HTML snapshots are bounded and explicitly marked truncated")

        setup('<x-card data-target id="host"></x-card>')
        page.evaluate("document.querySelector('x-card').attachShadow({mode:'open'}).innerHTML='<span>Shadow child</span>'")
        result = run("pick")
        check(result["tag"] == "x-card" and picked_target(result), "shadow DOM selects its host with a usable document selector")
        setup('<iframe data-target id="frame" src="https://other.test/frame"></iframe>')
        result = run("pick")
        check(result["tag"] == "iframe" and picked_target(result), "frames select their outer element without crossing document origins")

        run("clear")
        runtime = page.context.new_cdp_session(page)
        runtime.send("Emulation.setDeviceMetricsOverride", {"width":800, "height":600, "deviceScaleFactor":1, "mobile":True})
        runtime.send("Emulation.setPageScaleFactor", {"pageScaleFactor":2})
        setup('<div data-target id="zoomed">Zoomed</div>')
        page.evaluate("""() => {
            const v=visualViewport, node=document.querySelector('[data-target]');
            node.style.position='fixed';
            node.style.left=(v.offsetLeft+v.width/2-70)+'px';
            node.style.top=(v.offsetTop+v.height/2-45)+'px';
        }""")
        check(page.evaluate("visualViewport.scale > 1.5"), "fixture actually applies page zoom")
        result = run("pick")
        check(result is not None and result["id"] == "zoomed", "normalized native taps account for the zoomed visual viewport")
        run("clear")
        check(page.locator('[data-twig-inspector]').count() == 0, "zoomed inspection also cleans up")
        browser.close()
    print(f"{checks} browser checks passed")


if __name__ == "__main__":
    main()
