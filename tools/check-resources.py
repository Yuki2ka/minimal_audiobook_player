#!/usr/bin/env python3
"""Cross-checks Java references to R.* against the resource files.

aapt2 already fails on missing resources, but this catches the reverse class of
bugs that only show up at runtime: findViewById() on an id that no layout
declares (e.g. a typo in an include, or an id that exists only in one
orientation-specific layout). It also locks in the layout shapes that keep
file-row text and the fast-speed label fully visible, and the compact row
spacing shared by smooth and discrete browsing.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

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

# A SeekPad without padDirection silently defaults to "forward", which is how the
# back pad ended up seeking forward once; require the attribute explicitly.
for xml_path in layout_files:
    xml_text = open(xml_path, encoding="utf-8").read()
    for element in re.findall(r"<io\.github\.yuki2ka\.audiobookplayer\.ui\.SeekPad\b[^>]*>", xml_text):
        if "padDirection" not in element:
            problems.append(f"{os.path.basename(xml_path)}: SeekPad without app:padDirection")

# Every @layout/... include must resolve.
for name in sorted(set(re.findall(r'@layout/(\w+)', layout_text))):
    if name not in defined["layout"]:
        problems.append(f"layout references @layout/{name}, which does not exist")

# Every @+id declared in only one orientation variant is fine, but every id used
# from Java must exist in at least one layout (checked above).

# A MediaSession used for remote volume must install a callback: Android drops
# every adjustment (and therefore every volume key) while no callback exists.
# This cannot be caught by the desktop tests, so assert it here.
volume_seeker = os.path.join(SRC, "io", "github", "yuki2ka", "audiobookplayer",
                             "playback", "VolumeKeySeeker.java")
if os.path.exists(volume_seeker):
    seeker_text = open(volume_seeker, encoding="utf-8").read()
    if "setPlaybackToRemote(" in seeker_text:
        if not re.search(r"setCallback\(\s*callback", seeker_text):
            problems.append("VolumeKeySeeker uses setPlaybackToRemote without "
                            "session.setCallback(...); the system would drop every "
                            "volume key")
        if "onSetVolumeTo" not in seeker_text:
            problems.append("VolumeKeySeeker's VolumeProvider must handle onSetVolumeTo")

# Layout regressions that only show up on a device with real text / a larger
# system font. Both were introduced by the discrete-list rewrite:
#   * a fixed-height vertical row squeezed the size / resume-position line;
#   * the fast speed sat beside its icon and was ellipsised to "...".
# Keep the two shapes that fix them explicit in the XML.
ANDROID = "{http://schemas.android.com/apk/res/android}"
def layout(name):
    return ET.parse(os.path.join(RES, "layout", name)).getroot()

def with_id(root, name):
    for element in root.iter():
        if element.get(ANDROID + "id") == "@+id/" + name:
            return element
    return None

audio = layout("item_audio.xml")
if audio.tag != "FrameLayout" or audio.get(ANDROID + "layout_height") != "wrap_content" \
        or audio.get(ANDROID + "minHeight") != "@dimen/row_min_height":
    problems.append("item_audio.xml root must wrap_content with minHeight=row_min_height, "
                    "otherwise long names and the size / saved-position line get clipped")
title = with_id(audio, "row_title")
if title is None or title.get(ANDROID + "singleLine") == "true" \
        or title.get(ANDROID + "maxLines") != "2":
    problems.append("item_audio.xml row_title must allow two lines (no singleLine)")
subtitle = with_id(audio, "row_subtitle")
if subtitle is None or subtitle.get(ANDROID + "singleLine") != "true":
    problems.append("item_audio.xml row_subtitle must stay one line for even row heights")
if with_id(audio, "row_group_end") is not None:
    problems.append("item_audio.xml must not draw a group-closing rule: folders are "
                    "closed by their own item_folder_delimiter row")

# The All-files list draws a folder tree: a named separator opens every folder
# and a nameless one (just the rule) closes it. The adapter swaps the two by
# hiding the icon and the name, so both widgets must exist and the rule must be
# the only element that is always visible.
delimiter = layout("item_folder_delimiter.xml")
if delimiter.get(ANDROID + "paddingStart") is None:
    problems.append("item_folder_delimiter.xml needs paddingStart: nesting is shown "
                    "by indenting this row")
delimiter_icon = with_id(delimiter, "folder_delimiter_icon")
delimiter_name = with_id(delimiter, "folder_delimiter_name")
if delimiter_icon is None or delimiter_icon.tag != "ImageView":
    problems.append("item_folder_delimiter.xml needs folder_delimiter_icon for the "
                    "opening row (a closing row is a bare line)")
if delimiter_name is None or delimiter_name.get(ANDROID + "singleLine") != "true":
    problems.append("item_folder_delimiter.xml needs a single-line "
                    "folder_delimiter_name for the opening row")

panel = layout("player_panel.xml")
fast_button = with_id(panel, "btn_fast_play")
if fast_button is None or fast_button.tag != "LinearLayout" \
        or fast_button.get(ANDROID + "orientation") != "vertical":
    problems.append("player_panel.xml btn_fast_play must be vertical: its speed label "
                    "belongs under the glyph, not beside it")
speed_label = with_id(fast_button, "fast_speed_label") if fast_button is not None else None
if speed_label is None or speed_label.get(ANDROID + "layout_width") != "match_parent" \
        or speed_label.get(ANDROID + "singleLine") == "true" \
        or speed_label.get(ANDROID + "ellipsize") is not None:
    problems.append("player_panel.xml fast_speed_label needs full width and no ellipsize, "
                    "otherwise the speed is truncated to '...'")

def dp_value(element, attr):
    """Numeric dp value of an android attribute, or None when it is not dp."""
    value = element.get(ANDROID + attr) if element is not None else None
    if value is None or not value.endswith("dp"):
        return None
    try:
        return float(value[:-2])
    except ValueError:
        return None

# Spacing regression: a fixed 64 sp discrete row (and 64 dp / 10 dp padding
# smooth rows) made the gaps between files much larger, especially with a
# larger system font. Keep the compact geometry and the shared measured height.
row_min = re.search(r'<dimen name="row_min_height">(\d+)dp</dimen>', values_text)
if row_min is None or int(row_min.group(1)) > 60:
    problems.append("row_min_height must be <= 60dp so file rows stay compact")
delimiter_dimen = re.search(r'<dimen name="folder_delimiter_height">(\d+)dp</dimen>',
                            values_text)
if delimiter_dimen is None or int(delimiter_dimen.group(1)) > 32:
    problems.append("folder_delimiter_height must be <= 32dp so folder groups "
                    "do not open a wide gap")
details = with_id(audio, "row_details")
for attr in ("paddingTop", "paddingBottom"):
    pad = dp_value(details, attr)
    if pad is None or pad > 8:
        problems.append(f"item_audio.xml row_details {attr} must be <= 8dp to keep "
                        "the gaps between file rows small")
for attr in ("layout_marginTop", "layout_marginBottom"):
    margin = dp_value(audio, attr)
    if margin is None or margin > 1:
        problems.append(f"item_audio.xml root {attr} must be <= 1dp to keep "
                        "the gaps between file rows small")
if re.search(r'<dimen name="row_discrete_height">\d+sp</dimen>', values_text):
    problems.append("a fixed sp row height inflates discrete gaps with the font "
                    "scale; measure a one-line row instead")
if '<dimen name="folder_indent">' not in values_text:
    problems.append("missing folder_indent: sub-folder separators in the All-files "
                    "tree are indented by their nesting depth")

# The unified list must stay a rotation of the scanned tree: files and folder
# separators live in one queue. A flat "sort every file by name" would silently
# bring back the bug where a book's chapters interleaved with another folder's.
scanner_src = open(os.path.join(SRC, "io", "github", "yuki2ka", "audiobookplayer",
                                "scan", "Scanner.java"), encoding="utf-8").read()
if "TreeOrder.files(" not in scanner_src:
    problems.append("Scanner must build the unified order from the folder tree "
                    "(TreeOrder.files), not from a flat name sort")
if "Collections.sort(allFiles" in scanner_src:
    problems.append("Scanner must not flat-sort all files: the All-files list is a "
                    "folder tree")
queue_src = open(os.path.join(SRC, "io", "github", "yuki2ka", "audiobookplayer",
                              "playback", "PlayQueue.java"), encoding="utf-8").read()
if "QueueEntry" not in queue_src:
    problems.append("PlayQueue must rotate files and folder separators together "
                    "(one list of QueueEntry rows)")

adapter_src = open(os.path.join(SRC, "io", "github", "yuki2ka", "audiobookplayer",
                                "ui", "AudioFileAdapter.java"), encoding="utf-8").read()
list_src = open(os.path.join(SRC, "io", "github", "yuki2ka", "audiobookplayer",
                             "ui", "DiscreteListView.java"), encoding="utf-8").read()
if "discreteRowHeightPx" not in adapter_src or ".measure(" not in adapter_src:
    problems.append("AudioFileAdapter must measure a one-line row for the discrete "
                    "height, so discrete gaps match smooth mode at any font scale")
if "setDiscreteRowHeight" not in list_src:
    problems.append("DiscreteListView must use the adapter's measured row height "
                    "for its gutter and drag step")

if problems:
    print("Resource check FAILED:")
    for problem in problems:
        print("  -", problem)
    sys.exit(1)

print(f"Resource check passed: {len(defined['id'])} ids, {len(defined['layout'])} layouts, "
      f"{len(defined['string'])} strings, {len(defined['drawable'])} drawables")
