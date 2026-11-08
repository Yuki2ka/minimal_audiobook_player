#!/usr/bin/env python3
"""Minimal, dependency-free `zipalign` replacement.

The shipped APK is assembled with `aapt2` (resources) plus `zip` (classes.dex).
Android maps some entries of the APK directly from the file, so every entry's
data must start on a 4-byte boundary — that is exactly what `zipalign` does.

When the Android build-tools' native `zipalign` binary is not available (for
example in a sandbox that only has the Java tools), this script produces the
same result with nothing but the Python standard library: it reads the archive,
rewrites it with padding in each entry's extra field, and keeps the compression
method, CRC and timestamps of every entry.

Usage: zipalign.py [--alignment 4] input.apk output.apk
"""
import argparse
import struct
import sys
import zipfile
import zlib

LOCAL_SIG = 0x04034B50
CENTRAL_SIG = 0x02014B50
END_SIG = 0x06054B50


def extra_padding(length):
    """Extra field that is pure padding (header id 0xD935 is never interpreted)."""
    if length < 4:
        return b""
    # id 0xD935 with a payload that only exists to fill the field.
    payload = length - 4
    return struct.pack("<HH", 0xD935, payload) + b"\x00" * payload


def write_archive(source, destination, alignment):
    with zipfile.ZipFile(source) as archive:
        entries = []
        for info in archive.infolist():
            data = archive.read(info.filename)
            if info.compress_type == zipfile.ZIP_DEFLATED:
                compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
                payload = compressor.compress(data) + compressor.flush()
                method = 8
            else:
                payload = data
                method = 0
            crc = zlib.crc32(data) & 0xFFFFFFFF
            entries.append({
                "name": info.filename.encode("utf-8"),
                "method": method,
                "crc": crc,
                "uncompressed": len(data),
                "payload": payload,
                "date": info.date_time,
                "external": info.external_attr >> 16 if info.external_attr else 0,
            })

    out = bytearray()
    central = bytearray()
    for entry in entries:
        name = entry["name"]
        time_bits = to_dos_time(entry["date"])
        header_length = 30 + len(name)
        # Padding must start the data on a boundary; an extra field needs at
        # least 4 bytes (id + length), so 1..3 bytes are rounded up by a whole
        # alignment step (the data stays aligned because the pad keeps the
        # same remainder modulo `alignment`).
        pad = (alignment - ((len(out) + header_length) % alignment)) % alignment
        if 0 < pad < 4:
            pad += alignment
        extra = extra_padding(pad)
        offset = len(out)
        entry["offset"] = offset

        out += struct.pack(
            "<IHHHHHIIIHH", LOCAL_SIG, 20, 0, entry["method"], time_bits[1], time_bits[0],
            entry["crc"], len(entry["payload"]), entry["uncompressed"],
            len(name), len(extra))
        out += name
        out += extra
        out += entry["payload"]

        central += struct.pack(
            "<IHHHHHHIIIHHHHHII", CENTRAL_SIG, 20, 20, 0, entry["method"],
            time_bits[1], time_bits[0], entry["crc"], len(entry["payload"]),
            entry["uncompressed"], len(name), len(extra), 0, 0, 0,
            entry["external"] or 0o100644 << 16, offset)
        central += name
        central += extra

    central_offset = len(out)
    out += central
    out += struct.pack("<IHHHHIIH", END_SIG, 0, 0, len(entries), len(entries),
                       len(central), central_offset, 0)

    with open(destination, "wb") as handle:
        handle.write(out)


def to_dos_time(date_time):
    year, month, day, hour, minute, second = date_time
    if year < 1980:
        year, month, day, hour, minute, second = 1980, 1, 1, 0, 0, 0
    dos_date = ((year - 1980) << 9) | (month << 5) | day
    dos_time = (hour << 11) | (minute << 5) | (second // 2)
    return dos_time, dos_date


def verify(path, alignment):
    """Every entry's data must start on an `alignment` boundary."""
    problems = []
    with zipfile.ZipFile(path) as archive, open(path, "rb") as handle:
        for info in archive.infolist():
            handle.seek(info.header_offset)
            header = handle.read(30)
            if len(header) != 30:
                problems.append(f"{info.filename}: truncated local header")
                continue
            fields = struct.unpack("<IHHHHHIIIHH", header)
            data_offset = info.header_offset + 30 + fields[9] + fields[10]
            if data_offset % alignment:
                problems.append(f"{info.filename}: data at {data_offset} is not "
                                f"{alignment}-byte aligned")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--alignment", type=int, default=4)
    parser.add_argument("input")
    parser.add_argument("output")
    args = parser.parse_args()

    write_archive(args.input, args.output, args.alignment)
    problems = verify(args.output, args.alignment)
    if problems:
        for problem in problems:
            print("alignment problem:", problem, file=sys.stderr)
        return 1
    print(f"aligned {args.output} ({args.alignment}-byte)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
