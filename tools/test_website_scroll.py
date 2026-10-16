#!/usr/bin/env python3
"""Exercise the production pull-to-refresh DOM probe in Chromium.

Requires Python Playwright. Run: python tools/test_website_scroll.py [--browser PATH]
"""
import argparse
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
PROBE = (ROOT / "app/src/main/assets/website_scroll_top.js").read_text()
BASE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body {margin:0;height:100%;overflow:hidden}
header {position:fixed;top:0;height:56px;left:0;right:0;background:#ddd;z-index:2}
#pane {position:fixed;top:56px;bottom:0;left:0;right:0;overflow:auto}
.content {height:10000px;background:linear-gradient(white,lightblue)}
</style><header>Fixed header</header>
<div id="pane"><div class="content">Scrollable content</div></div>"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", help="Optional Chromium executable path")
    args = parser.parse_args()
    checks = 0
    with sync_playwright() as p:
        options = {"headless": True}
        if args.browser:
            options["executable_path"] = args.browser
        browser = p.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 390, "height": 844},
                                      is_mobile=True, has_touch=True, device_scale_factor=2)
        page = context.new_page()

        def load(html):
            page.set_content(html)
            page.wait_for_load_state("networkidle")

        def check(expected, description, fractions=(0.5, 0.5)):
            nonlocal checks
            actual = page.evaluate(f"args => ({PROBE})(...args)", list(fractions))
            assert actual == expected, f"{description}: expected {expected}, got {actual}"
            checks += 1
            print(f"PASS: {description}")

        load(BASE)
        check(True, "fixed header with scroll area at top")
        page.locator("#pane").evaluate("e => e.scrollTop = 700")
        assert page.evaluate("document.scrollingElement.scrollTop") == 0
        check(False, "inner area midway while WebView document stays at zero")
        page.screenshot(path="/tmp/twig-nested-scroll.png")
        page.locator("#pane").evaluate("e => e.scrollTop = 0")
        check(True, "inner area scrolled back to top")

        # An unrelated sibling scroller must not veto a pull in the touched column.
        load(BASE.replace('id="pane"', 'id="pane" style="right:50%"') +
             '<div id="other" style="position:fixed;top:56px;bottom:0;left:50%;right:0;overflow:auto">'
             '<div class="content">Second column</div></div>')
        page.locator("#other").evaluate("e => e.scrollTop = 700")
        check(True, "touched column at top with unrelated column scrolled", (0.25, 0.5))
        check(False, "touched scrolled column", (0.75, 0.5))

        load(BASE.replace('<div class="content">Scrollable content</div>',
             '<div style="margin-top:100px;height:300px;overflow:auto" id="inner">'
             '<div class="content">Inner</div></div><div class="content"></div>'))
        page.locator("#pane").evaluate("e => e.scrollTop = 60")
        check(False, "scrolled ancestor blocks refresh even with inner area at top", (0.5, 0.2))
        page.locator("#pane").evaluate("e => e.scrollTop = 0")
        page.locator("#inner").evaluate("e => e.scrollTop = 300")
        check(False, "deeply nested scrolling child blocks refresh", (0.5, 0.25))

        load('<meta name="viewport" content="width=device-width"><div style="height:10000px">Document</div>')
        check(True, "ordinary document at top")
        page.evaluate("scrollTo(0, 500)")
        check(False, "ordinary document midway")

        load(BASE.replace('<div class="content">Scrollable content</div>', '<div id="shadow"></div>'))
        page.locator("#shadow").evaluate("""host => {
            host.attachShadow({mode:'open'}).innerHTML =
                '<div id="scroll" style="height:700px;overflow:auto"><div style="height:10000px">Shadow content</div></div>';
        }""")
        check(True, "open shadow-root scroller at top")
        page.locator("#shadow #scroll").evaluate("e => e.scrollTop = 500")
        check(False, "open shadow-root scroller midway")

        load(BASE.replace('<div class="content">Scrollable content</div>',
                          '<iframe id="frame" style="width:95%;height:700px"></iframe>'))
        page.locator("#frame").evaluate("""frame => frame.srcdoc =
            '<div style="height:10000px">Same-origin frame</div>'""")
        page.wait_for_function("document.querySelector('#frame').contentDocument?.body?.textContent.includes('Same-origin')")
        check(True, "same-origin frame at top")
        page.locator("#frame").evaluate("e => e.contentWindow.scrollTo(0, 400)")
        check(False, "same-origin frame midway")
        page.locator("#frame").evaluate("e => { e.removeAttribute('srcdoc'); e.src = 'data:text/html,<div>Unknown frame</div>'; }")
        page.wait_for_function("document.querySelector('#frame').contentDocument === null")
        check(False, "cross-origin frame is unknown rather than assumed at top")

        load(BASE.replace("width=device-width,initial-scale=1", "width=1280"))
        check(True, "desktop viewport at top")
        page.locator("#pane").evaluate("e => e.scrollTop = 700")
        check(False, "desktop viewport midway")

        load(BASE)
        context.new_cdp_session(page).send("Emulation.setPageScaleFactor", {"pageScaleFactor": 2})
        check(True, "pinch-scaled viewport at top")
        page.locator("#pane").evaluate("e => e.scrollTop = 700")
        check(False, "pinch-scaled viewport midway")
        context.close()
        browser.close()
    print(f"{checks} browser checks passed")


if __name__ == "__main__":
    main()
