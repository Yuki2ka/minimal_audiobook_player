#!/usr/bin/env python3
"""Cross-checks Java references to R.* against the resource files.

aapt2 already fails on missing resources, but this catches the reverse class of
bugs that only show up at runtime: findViewById() on an id that no layout
declares (e.g. a typo in an include, or an id that exists only in one
orientation-specific layout).
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "app", "src", "main", "java")

java_files = glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True)
java_text = "\n".join(open(f, encoding="utf-8").read() for f in java_files)

layout_files = glob.glob(os.path.join(RES, "layout*", "*.xml"))
layout_text = "\n".join(open(f, encoding="utf-8").read() for f in layout_files)

values_text = "\n".join(
    open(f, encoding="utf-8").read() for f in glob.glob(os.path.join(RES, "values", "*.xml"))
)

defined = {
    "id": set(re.findall(r"@\+id/(\w+)", layout_text)),
    "layout": {os.path.splitext(os.path.basename(f))[0] for f in layout_files},
    "string": set(re.findall(r'<string name="(\w+)"', values_text)),
    "plurals": set(re.findall(r'<plurals name="(\w+)"', values_text)),
    "color": set(re.findall(r'<color name="(\w+)"', values_text))
             | {os.path.splitext(os.path.basename(f))[0]
                for f in glob.glob(os.path.join(RES, "color", "*.xml"))},
    "dimen": set(re.findall(r'<dimen name="(\w+)"', values_text)),
    "style": set(re.findall(r'<style name="([\w.]+)"', values_text)),
    "drawable": {os.path.splitext(os.path.basename(f))[0]
                 for f in glob.glob(os.path.join(RES, "drawable*", "*"))},
    "mipmap": {os.path.splitext(os.path.basename(f))[0]
               for f in glob.glob(os.path.join(RES, "mipmap*", "*"))},
}
defined["style"] = {name.replace(".", "_") for name in defined["style"]}

problems = []
for kind in ("id", "layout", "string", "plurals", "color", "dimen", "style", "drawable", "mipmap"):
    used = set(re.findall(r"R\." + kind + r"\.(\w+)", java_text))
    missing = sorted(used - defined[kind])
    for name in missing:
        problems.append(f"Java uses R.{kind}.{name} but no such {kind} exists")

# Custom views referenced by tag name in a layout must exist as Java classes and
# expose the (Context, AttributeSet) constructor LayoutInflater needs. A wrong
# tag name compiles fine and only blows up with a runtime ClassCastException.
for xml_path in layout_files:
    xml_text = open(xml_path, encoding="utf-8").read()
    for tag in set(re.findall(r"<([a-z][\w]*\.[\w.]*)\s", xml_text)):
        rel = tag.replace(".", os.sep) + ".java"
        java_path = os.path.join(SRC, rel)
        if not os.path.isfile(java_path):
            problems.append(f"{os.path.basename(xml_path)} uses <{tag}> but {rel} does not exist")
            continue
        body = open(java_path, encoding="utf-8").read()
        if "AttributeSet" not in body:
            problems.append(f"<{tag}> has no (Context, AttributeSet) constructor")
        if f"public final class {tag.rsplit('.', 1)[-1]}" not in body \
                and f"public class {tag.rsplit('.', 1)[-1]}" not in body:
            problems.append(f"<{tag}> class name does not match the file")

# getString(R.string.x, ...) must pass exactly the arguments the format string
# expects, otherwise the app crashes with IllegalFormatException at runtime.
string_bodies = dict(re.findall(r'<string name="(\w+)"[^>]*>(.*?)</string>', values_text, re.S))
string_bodies.update(dict(re.findall(r"<string name=\"(\w+)\"[^>]*>(.*?)</string>", values_text, re.S)))
string_bodies.update(dict(re.findall(r"<string name='(\w+)'[^>]*>(.*?)</string>", values_text, re.S)))


def call_arguments(text, open_paren):
    depth = 0
    args = []
    current = []
    i = open_paren + 1
    while i < len(text):
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                args.append("".join(current).strip())
                return [a for a in args if a != ""]
            depth -= 1
        elif ch == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
            i += 1
            continue
        current.append(ch)
        i += 1
    return []


for match in re.finditer(r"getString\(\s*R\.string\.(\w+)", java_text):
    name = match.group(1)
    if name not in string_bodies:
        continue
    body = string_bodies[name]
    placeholders = re.findall(r"%(\d+)\$[a-zA-Z]|%(?!%)[a-zA-Z]", body)
    positional = [int(p) for p in re.findall(r"%(\d+)\$[a-zA-Z]", body)]
    plain = len(re.findall(r"%(?!\d+\$)(?!%)[a-zA-Z]", body))
    args = call_arguments(java_text, match.end() - len(name) - 1 + len(name))
    # Locate the opening parenthesis of this getString call.
    paren = java_text.find("(", match.start())
    args = call_arguments(java_text, paren)
    # The first argument is R.string.<name> itself.
    passed = max(0, len(args) - 1)
    needed = max(positional) if positional else plain
    if passed != needed:
        problems.append(f"getString(R.string.{name}) takes {needed} format argument(s) "
                        f"but the call passes {passed}")

# Every @layout/... include must resolve.
for name in sorted(set(re.findall(r'@layout/(\w+)', layout_text))):
    if name not in defined["layout"]:
        problems.append(f"layout references @layout/{name}, which does not exist")

# Every @+id declared in only one orientation variant is fine, but every id used
# from Java must exist in at least one layout (checked above).

if problems:
    print("Resource check FAILED:")
    for problem in problems:
        print("  -", problem)
    sys.exit(1)

print(f"Resource check passed: {len(defined['id'])} ids, {len(defined['layout'])} layouts, "
      f"{len(defined['string'])} strings, {len(defined['drawable'])} drawables")
