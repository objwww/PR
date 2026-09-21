"""Parse the locally generated fragment; never navigate or execute its contents."""
from html.parser import HTMLParser
from pathlib import Path


class Attributes(HTMLParser):
    def handle_starttag(self, tag, attrs):
        if tag == "a":
            values = dict(attrs)
            assert values.get("onmouseover") == "globalThis.audit=1", values
            print("REPRODUCED: HTML parser recognizes injected onmouseover attribute")
            self.found = True


parser = Attributes()
parser.found = False
parser.feed((Path(__file__).resolve().parents[1] / "review-current-render-fragment.txt").read_text())
assert parser.found
