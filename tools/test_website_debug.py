#!/usr/bin/env python3
"""Exercise production debug snippets in a local Chromium page.

Run WebsiteDebugTest first to export the exact JavaScript submitted to WebView.
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
    snippets = json.loads((ROOT / "app/build/reports/website-debug/scripts.json").read_text())
    checks = 0

    def check(condition, name):
        nonlocal checks
        assert condition, name
        checks += 1
        print("PASS:", name)

    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=args.browser)
        page = browser.new_page()
        page.route("**/*", lambda route: route.fulfill(content_type="text/html", headers={
            "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'none'"
        }, body="<html><body><div id='label'>original</div></body></html>"))
        page.goto("https://twig.test/page")
        runtime = page.context.new_cdp_session(page)

        def run(source):
            # Raw source evaluation matches WebView.evaluateJavascript (no extra eval/function wrapper).
            return runtime.send("Runtime.evaluate", {"expression": source, "returnByValue": True})

        result = run(snippets["manual"])
        check(result["result"]["value"] == {"count": 1}, "manual script reports its object result")
        check(page.locator("#label").inner_text() == "changed", "manual script modifies the actual DOM")
        check(run(snippets["manual"])["result"]["value"] == {"count": 2}, "manual runs execute each time without the saved-script once guard")
        run("const debugGlobal = 42")
        check(run("debugGlobal")["result"]["value"] == 42, "console declarations remain available to later commands")
        check("exceptionDetails" in run("throw new Error('debug-runtime-error')"), "runtime failures are reported")
        check("exceptionDetails" in run("let = ;"), "syntax failures are reported")
        run(snippets["preview"])
        check(run("getComputedStyle(document.body).color")["result"]["value"] == "rgb(12, 34, 56)", "temporary CSS changes page appearance")
        run(snippets["restore"])
        check(run("getComputedStyle(document.body).color")["result"]["value"] == "rgb(0, 0, 0)", "restoring removes temporary CSS")
        check(page.locator('style[id^="__twig_user_style_"]').count() == 0, "restore does not leave a debug stylesheet behind")
        run(snippets["preview"])
        page.reload()
        check(page.locator('style[id^="__twig_user_style_"]').count() == 0, "temporary page changes disappear on reload")
        browser.close()
    print(f"{checks} browser checks passed")


if __name__ == "__main__":
    main()
