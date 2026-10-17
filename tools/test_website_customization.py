#!/usr/bin/env python3
"""Run production-generated website customization scripts in Chromium.

First run WebsiteCustomizationTest to generate the fixture under app/build/reports.
Then: python tools/test_website_customization.py [--browser PATH]
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import Error, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
BODY = """<!doctype html><html><head><style id="existing" nonce="test-nonce">
#existing-target { font-weight: bold; }
</style></head><body><div id="label">original</div><div id="ad">Advertisement</div>
<div id="existing-target">Existing style</div></body></html>"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser")
    parser.add_argument("--fixtures", type=Path, default=ROOT / "app/build/reports/website-customization/scripts.json")
    args = parser.parse_args()
    scripts = json.loads(args.fixtures.read_text())
    checks = 0
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=args.browser)
        page = browser.new_page()

        def respond(route):
            headers = {}
            if route.request.url.endswith("/csp"):
                headers["Content-Security-Policy"] = "default-src 'self'; style-src 'nonce-test-nonce'; script-src 'self'"
            route.fulfill(content_type="text/html", headers=headers, body=BODY)

        page.route("**/*", respond)

        def check(condition, name):
            nonlocal checks
            assert condition, name
            checks += 1
            print("PASS:", name)

        def run(name):
            return page.evaluate(scripts[name])

        def color():
            return page.evaluate("getComputedStyle(document.body).color")

        def styles():
            return page.locator('style[id^="__twig_user_style_"]').count()

        page.goto("https://twig.test/site")
        run("style")
        check(color() == "rgb(12, 34, 56)", "custom CSS changes actual page appearance")
        check(not page.locator("#ad").is_visible(), "custom CSS hides matching page elements")
        run("style")
        check(styles() == 1, "reapplying page options does not duplicate stylesheets")
        run("script")
        run("script")
        check(page.evaluate("window.twigRuns") == 1, "custom script runs once despite repeated load callbacks")
        check(page.locator("#label").inner_text() == "updated", "custom script can modify the page DOM")
        page.evaluate("location.hash = 'spa'")
        run("script")
        check(page.evaluate("window.twigRuns") == 1, "same-document navigation does not rerun the script")
        run("updatedStyle")
        check(color() == "rgb(90, 80, 70)" and styles() == 1, "editing CSS updates the existing stylesheet")
        run("clearStyle")
        check(color() == "rgb(0, 0, 0)" and styles() == 0, "clearing CSS restores the original page appearance")
        check(page.locator("#existing").count() == 1, "clearing custom CSS preserves the website stylesheet")
        run("literalStyle")
        literal = page.locator('style[id^="__twig_user_style_"]').text_content()
        check("</style>" in literal and "\u2028" in literal and "\u2029" in literal,
              "quotes, backslashes, closing tags and Unicode separators survive serialization")
        check(page.locator("img").count() == 0, "CSS content is inserted as text rather than HTML")
        run("style")
        try:
            run("invalidScript")
            raise AssertionError("Malformed JS should fail parsing")
        except Error:
            pass
        check(color() == "rgb(12, 34, 56)", "invalid custom JS cannot prevent a separate CSS injection")
        run("throwingScript")
        check(page.locator("#label").inner_text() == "updated", "script runtime errors are contained")
        page.reload()
        run("script")
        check(page.evaluate("window.twigRuns") == 1, "a newly loaded document runs the script once again")
        page.goto("https://other.test/site")
        run("style")
        run("script")
        check(color() == "rgb(0, 0, 0)" and styles() == 0, "foreign origins do not receive custom CSS")
        check(page.evaluate("typeof window.twigRuns") == "undefined", "foreign origins do not execute custom JS")
        page.goto("https://twig.test/site")
        page.evaluate("""() => {
            const frame = document.createElement('iframe');
            frame.srcdoc = '<body><div id="label">frame</div></body>';
            document.body.append(frame);
        }""")
        page.wait_for_function("document.querySelector('iframe').contentDocument?.querySelector('#label')")
        run("style")
        run("script")
        check(page.evaluate("document.querySelector('iframe').contentWindow.twigRuns === undefined"),
              "main-page customization does not implicitly run inside frames")
        page.goto("https://twig.test/csp")
        run("style")
        check(color() == "rgb(12, 34, 56)", "a page stylesheet nonce is reused when available")
        run("script")
        check(page.evaluate("window.twigRuns") == 1, "script execution uses no eval or Function constructor")
        page.goto("https://twig.test/site")
        page.evaluate("() => { const node = document.createElement('div'); node.id = 'space '; node.textContent = 'Space'; document.body.append(node); }")
        run("hiddenOnly")
        check(not page.locator("#ad").is_visible(), "hidden selector works without custom CSS")
        check(not page.locator('[id="space "]').is_visible(), "escaped trailing spaces in selectors survive storage normalization")
        check(color() != "rgb(255, 0, 0)", "invalid rows cannot inject arbitrary CSS rules")
        check(page.locator("#label").is_visible(), "invalid selector does not disable other page content")
        page.evaluate("() => { const node = document.createElement('div'); node.id = 'later'; node.textContent = 'Later'; document.body.append(node); }")
        check(not page.locator("#later").is_visible(), "rules also hide matching elements added later")
        run("keepCssClearHidden")
        check(page.locator("#ad").is_visible() and page.locator("#later").is_visible(), "removing selectors restores matching elements")
        check(color() == "rgb(12, 34, 56)", "restoring hidden elements preserves independent custom CSS")
        page.reload()
        run("hiddenOnly")
        check(not page.locator("#ad").is_visible(), "saved hiding rules apply again after page reload")
        page.goto("https://other.test/site")
        run("hiddenOnly")
        check(page.locator("#ad").is_visible(), "hidden element rules do not affect foreign origins")
        browser.close()
    print(f"{checks} website customization checks passed")


if __name__ == "__main__":
    main()
