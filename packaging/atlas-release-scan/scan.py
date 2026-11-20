#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Scan a release artifact for secrets and environment detail.

The fork publishes its Debian packages and container images, so nothing in
them may carry a credential or a detail of the environment that built or runs
them. This scan unpacks an artifact completely: each Debian package's control
and data trees, each image layer on its own (a file a later layer deletes
still ships in the earlier one), the image configuration and history, and the
zip, jar, war, tar, gzip, xz, bzip2 and Debian archives inside them. Every
file becomes its printable strings, and gitleaks scans the result with its
default rules and those in rules.toml. Files of an image that are byte for
byte the files of an Ubuntu archive package, by that package's own md5sums,
are public archive content and are not scanned.

Literal values in the RELEASE_SCAN_DENYLIST environment variable, one per
line, are matched case-insensitively as whole words. A finding fails the scan
unless its rule and value hash are listed in baseline.tsv, the values that
upstream CloudStack's own packages and the public base images already carry.
A denylist match always fails.

Output names the rule and the file of a finding, never its value; a denylist
match is named by its line number in the denylist, and a denylist value in a
printed path is masked. --list adds each value's hash, for maintaining the
baseline from a local run.

Before trusting a result the scan plants a canary file with generated values
and every denylist value, and stops with an error unless gitleaks reports
each of them.

Usage:
  scan.py [options] dir DIR         every file in DIR, Debian packages unpacked
  scan.py [options] image TARBALL   an image written by docker save

Exit status: 0 clean, 1 findings, 2 the scan itself failed.
"""

import argparse
import bz2
import collections
import hashlib
import io
import json
import lzma
import os
import random
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
MAX_BLOB = 2 << 30
MAX_DEPTH = 8
STRINGS = re.compile(rb"[\t\n\r\x20-\x7e]{4,}")
HEADER = b"release-scan\n"
KEYSTORE_MAGIC = (b"\xfe\xed\xfe\xed", b"\xce\xce\xce\xce")
KEYSTORE_SUFFIX = (".jks", ".jceks", ".keystore", ".p12", ".pfx", ".bcfks")


class ScanError(Exception):
    pass


def value_hash(value):
    return hashlib.sha256(value.encode("utf-8", "surrogateescape")).hexdigest()[:16]


class Tree:
    """The unpacked artifact as text files, one per distinct content."""

    def __init__(self, root):
        self.root = root
        self.by_hash = {}
        self.paths = []
        self.findings = []
        self.stats = collections.Counter()

    def add_text(self, display, text, category="files"):
        if not text.strip():
            return
        digest = (category, hashlib.sha256(text).digest())
        index = self.by_hash.get(digest)
        if index is not None:
            self.paths[index].append(display)
            return
        index = len(self.paths)
        self.by_hash[digest] = index
        self.paths.append([display])
        directory = os.path.join(self.root, category, "%05d" % (index // 1000))
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, "%08d.txt" % index), "wb") as out:
            out.write(HEADER)
            out.write(text)
        self.stats["texts"] += 1

    def finding(self, rule, value, display):
        self.findings.append((rule, value, display))

    def index_of(self, tree_file):
        name = os.path.basename(tree_file)
        if not name.endswith(".txt") or not name[:-4].isdigit():
            raise ScanError("gitleaks reported a file outside the tree")
        return int(name[:-4])


def is_text(data):
    if b"\0" in data:
        return False
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def is_truststore(data):
    """True for a Java KeyStore holding only trusted certificates, which
    carries no secret: no private or secret key entry."""
    if data[:4] != b"\xfe\xed\xfe\xed":
        return False
    try:
        offset = 8
        count = int.from_bytes(data[offset:offset + 4], "big")
        offset += 4
        for _ in range(count):
            if int.from_bytes(data[offset:offset + 4], "big") != 2:
                return False
            offset += 4
            offset += 2 + int.from_bytes(data[offset:offset + 2], "big") + 8
            offset += 2 + int.from_bytes(data[offset:offset + 2], "big")
            offset += 4 + int.from_bytes(data[offset:offset + 4], "big")
        return offset + 20 == len(data)
    except IndexError:
        return False


def sniff(data):
    if data[:4] in (b"PK\x03\x04", b"PK\x05\x06"):
        return "zip"
    if data[:2] == b"\x1f\x8b":
        return "gzip"
    if data[:6] == b"\xfd7zXZ\x00":
        return "xz"
    if data[:3] == b"BZh" and data[4:10] == b"1AY&SY":
        return "bzip2"
    if data[:4] == b"\x28\xb5\x2f\xfd":
        return "zstd"
    if data[:8] == b"!<arch>\n":
        return "ar"
    if len(data) > 262 and data[257:262] == b"ustar":
        return "tar"
    return None


class TooLarge(Exception):
    pass


def decompress(kind, data):
    """Decompresses every stream of data, up to MAX_BLOB bytes in all."""
    parts, size = [], 0
    while data:
        if kind == "gzip":
            engine = zlib.decompressobj(wbits=31)
        elif kind == "xz":
            engine = lzma.LZMADecompressor()
        else:
            engine = bz2.BZ2Decompressor()
        part = engine.decompress(data, MAX_BLOB + 1 - size)
        size += len(part)
        if size > MAX_BLOB:
            raise TooLarge()
        parts.append(part)
        data = engine.unused_data
        if not data.strip(b"\0"):
            break
    return b"".join(parts)


def scan_blob(tree, data, display, depth, category="files"):
    tree.stats["files"] += 1
    if (data[:4] in KEYSTORE_MAGIC or display.lower().endswith(KEYSTORE_SUFFIX)) \
            and not is_truststore(data):
        tree.finding("keystore", hashlib.sha256(data).hexdigest(), display)
    kind = sniff(data)
    if kind is not None and depth < MAX_DEPTH:
        try:
            if kind == "zip":
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    for info in archive.infolist():
                        if info.is_dir():
                            continue
                        member = "%s!/%s" % (display, info.filename)
                        if info.file_size > MAX_BLOB:
                            tree.finding("too-large", member, member)
                            continue
                        scan_blob(tree, archive.read(info), member, depth + 1, category)
            elif kind in ("gzip", "xz", "bzip2"):
                scan_blob(tree, decompress(kind, data), display + "!", depth + 1, category)
            elif kind == "zstd":
                if shutil.which("zstd") is None:
                    raise ScanError("no zstd")
                out = subprocess.run(["zstd", "-dc"], input=data, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, check=True).stdout
                scan_blob(tree, out, display + "!", depth + 1, category)
            elif kind == "ar":
                with tempfile.NamedTemporaryFile(dir=tree.root, suffix=".deb") as deb:
                    deb.write(data)
                    deb.flush()
                    scan_deb(tree, deb.name, display + "!", depth + 1)
            elif kind == "tar":
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
                    scan_tar(tree, archive, display + "!", depth + 1, category=category)
            tree.stats["archives"] += 1
            return
        except TooLarge:
            tree.finding("too-large", display, display)
            return
        except (ScanError, zipfile.BadZipFile, NotImplementedError, RuntimeError, EOFError,
                OSError, lzma.LZMAError, zlib.error, tarfile.TarError, subprocess.CalledProcessError, ValueError):
            tree.finding("unscannable-" + kind, hashlib.sha256(data).hexdigest(), display)
    if is_text(data):
        tree.add_text(display, data, category)
    else:
        tree.add_text(display, b"\n".join(STRINGS.findall(data)),
                      "binary-strings" if category == "files" else category)


def scan_tar(tree, archive, prefix, depth, pristine=None, category="files"):
    for member in archive:
        if not member.isreg():
            continue
        name = member.name[2:] if member.name.startswith("./") else member.name.lstrip("/")
        display = "%s/%s" % (prefix, name)
        if member.size > MAX_BLOB:
            tree.finding("too-large", display, display)
            continue
        data = archive.extractfile(member).read()
        file_category = category
        if pristine is not None:
            if os.path.basename(name).startswith(".wh."):
                continue
            if hashlib.md5(data).hexdigest() in pristine.get(name, ()):
                tree.stats["pristine"] += 1
                continue
            if is_package_metadata(name):
                file_category = "package-metadata"
        scan_blob(tree, data, display, depth, file_category)


def scan_deb(tree, path, prefix, depth):
    for part, flag in (("control", "--ctrl-tarfile"), ("data", "--fsys-tarfile")):
        proc = subprocess.Popen(["dpkg-deb", flag, path], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL)
        with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
            scan_tar(tree, archive, "%s:%s" % (prefix, part), depth)
        if proc.wait() != 0:
            raise ScanError("dpkg-deb could not read %s" % os.path.basename(path))


PACKAGE_METADATA = re.compile(
    r"var/lib/dpkg/(?:status|status-old|available|available-old)"
    r"|var/cache/debconf/[^/]+\.dat(?:-old)?"
    r"|var/log/(?:dpkg\.log|alternatives\.log|bootstrap\.log|apt/[^/]+)")


def is_package_metadata(name):
    """True for the files dpkg and apt write from the packages they install,
    other than those of the fork's own packages. The rules for addresses,
    home paths and email addresses skip these; the others do not."""
    if PACKAGE_METADATA.fullmatch(name):
        return True
    info = re.fullmatch(r"var/lib/dpkg/info/(.+?)(?::[a-z0-9]+)?\.[a-z0-9_-]+", name)
    return info is not None and not info.group(1).startswith("cloudstack-")


def md5sums_owner(name):
    match = re.fullmatch(r"var/lib/dpkg/info/([^/:]+)(?::[^/]+)?\.md5sums", name)
    return match.group(1) if match else None


def status_conffiles(status):
    """Yields the path and md5 of each conffile of the packages in a dpkg status file."""
    for stanza in status.split("\n\n"):
        package = re.search(r"^Package: (\S+)", stanza, re.MULTILINE)
        if package is None or package.group(1).startswith("cloudstack-"):
            continue
        block = re.search(r"^Conffiles:\n((?: .*\n?)*)", stanza, re.MULTILINE)
        if block is None:
            continue
        for line in block.group(1).splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[0].startswith("/"):
                yield fields[0][1:], fields[1]


def scan_image(tree, path):
    with tarfile.open(path, mode="r:") as saved:
        manifest = json.load(saved.extractfile("manifest.json"))
        if len(manifest) != 1:
            raise ScanError("the image archive must hold exactly one image")
        config_bytes = saved.extractfile(manifest[0]["Config"]).read()
        config = json.loads(config_bytes)
        tree.add_text("image config", json.dumps(config, indent=1, sort_keys=True).encode())
        tree.add_text("image manifest", json.dumps(manifest, indent=1, sort_keys=True).encode())
        layers = manifest[0]["Layers"]
        diff_ids = config.get("rootfs", {}).get("diff_ids", [])

        # Files of Ubuntu archive packages are known by their md5sums; the
        # fork's own packages are always scanned.
        pristine = collections.defaultdict(set)
        for layer in layers:
            with tarfile.open(fileobj=saved.extractfile(layer), mode="r|*") as archive:
                for member in archive:
                    name = member.name[2:] if member.name.startswith("./") else member.name
                    if not member.isreg():
                        continue
                    if name == "var/lib/dpkg/status":
                        status = archive.extractfile(member).read().decode("utf-8", "replace")
                        for file_name, digest in status_conffiles(status):
                            pristine[file_name].add(digest)
                        continue
                    owner = md5sums_owner(name)
                    if owner is None or owner.startswith("cloudstack-"):
                        continue
                    for line in archive.extractfile(member).read().decode("utf-8", "replace").splitlines():
                        digest, _, file_name = line.partition("  ")
                        if file_name:
                            pristine[file_name].add(digest)

        for number, layer in enumerate(layers, 1):
            label = diff_ids[number - 1][:19] if number <= len(diff_ids) else layer
            prefix = "layer %d/%d %s:" % (number, len(layers), label)
            with tarfile.open(fileobj=saved.extractfile(layer), mode="r|*") as archive:
                scan_tar(tree, archive, prefix, 0, pristine=pristine)


def read_denylist():
    raw = os.environ.get("RELEASE_SCAN_DENYLIST", "")
    seen, values = set(), []
    for line in raw.splitlines():
        value = line.strip()
        if value and not value.startswith("#") and value.lower() not in seen:
            seen.add(value.lower())
            values.append(value)
    return values


def re2_literal(value):
    return "".join(c if c.isascii() and c.isalnum() else "\\x{%x}" % ord(c) for c in value)


def write_denylist_config(path, denylist):
    """Writes the denylist rules as a config of their own: gitleaks' default
    config allows every secret that looks like a Unix path, globally."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as out:
        for number, value in enumerate(denylist, 1):
            out.write("[[rules]]\n")
            out.write('id = "denylist-%d"\n' % number)
            out.write('description = "Denylist entry %d"\n' % number)
            out.write("regex = '''(?i)(?:^|[^a-z0-9_-])(%s)(?:[^a-z0-9_-]|$)'''\n" % re2_literal(value))
            out.write("secretGroup = 1\n")
            out.write("keywords = [%s]\n\n" % json.dumps(value.lower()))


def plant_canary(tree, denylist):
    rng = random.SystemRandom()
    label = "".join(rng.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(10))
    address = "10.%d.%d.%d" % (rng.randrange(1, 255), rng.randrange(1, 255), rng.randrange(1, 255))
    lines = [
        "listen %s here" % address,
        "contact %s@%s.net" % (label, label[::-1]),
        "path /home/%s/build" % label,
    ]
    lines += [" %s " % value for value in denylist]
    tree.add_text("canary", ("\n".join(lines) + "\n").encode())
    expected = {"rfc1918-address", "email-address", "home-path"}
    expected |= {"denylist-%d" % n for n in range(1, len(denylist) + 1)}
    return expected


def run_gitleaks(gitleaks, tree, config, report):
    ignore_dir = os.path.join(os.path.dirname(report), "ignore")
    os.makedirs(ignore_dir, exist_ok=True)
    proc = subprocess.run(
        [gitleaks, "dir", tree.root, "--config", config, "--no-banner", "--no-color",
         "--log-level", "debug", "--report-format", "json", "--report-path", report,
         "--exit-code", "0", "--gitleaks-ignore-path", ignore_dir],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise ScanError("gitleaks exited with %d" % proc.returncode)
    skipped = proc.stderr.count(b"skipping binary file")
    if skipped:
        raise ScanError("gitleaks skipped %d files of the tree as binary" % skipped)
    with open(report, encoding="utf-8", errors="surrogateescape") as handle:
        return json.load(handle)


def load_baseline(path):
    entries = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#") or not line.strip():
                continue
            fields = line.rstrip("\n").split("\t")
            entries.add((fields[0], fields[1]))
    return entries


def mask(text, denylist):
    for number, value in enumerate(denylist, 1):
        text = re.sub(re.escape(value), "<denylist-%d>" % number, text, flags=re.IGNORECASE)
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("kind", choices=("dir", "image"))
    parser.add_argument("target")
    parser.add_argument("--gitleaks", default="gitleaks")
    parser.add_argument("--rules", default=os.path.join(HERE, "rules.toml"))
    parser.add_argument("--baseline", default=os.path.join(HERE, "baseline.tsv"))
    parser.add_argument("--require-denylist", action="store_true",
                        help="fail unless RELEASE_SCAN_DENYLIST holds at least one value")
    parser.add_argument("--list", action="store_true",
                        help="print every finding with its value hash, baselined ones too")
    parser.add_argument("--write-baseline", metavar="FILE",
                        help="write the rule and value hash of every finding except denylist ones")
    parser.add_argument("--source", default="upstream", help="source label for --write-baseline")
    args = parser.parse_args()

    denylist = read_denylist()
    if args.require_denylist and not denylist:
        print("release scan: RELEASE_SCAN_DENYLIST is empty", file=sys.stderr)
        return 2
    baseline = load_baseline(args.baseline)
    work = tempfile.mkdtemp(prefix="release-scan-")
    try:
        tree = Tree(os.path.join(work, "tree"))
        os.makedirs(tree.root)
        if args.kind == "dir":
            names = sorted(os.listdir(args.target))
            if not names:
                raise ScanError("%s is empty" % args.target)
            for name in names:
                path = os.path.join(args.target, name)
                if not os.path.isfile(path) or os.path.islink(path):
                    raise ScanError("%s is not a regular file" % name)
                if name.endswith(".deb"):
                    tree.stats["files"] += 1
                    scan_deb(tree, path, name, 0)
                else:
                    with open(path, "rb") as handle:
                        scan_blob(tree, handle.read(), name, 0)
        else:
            scan_image(tree, args.target)
        if tree.stats["texts"] == 0:
            raise ScanError("nothing to scan")
        expected = plant_canary(tree, denylist)
        canary_index = len(tree.paths) - 1
        report = run_gitleaks(args.gitleaks, tree, os.path.abspath(args.rules),
                              os.path.join(work, "report.json"))
        if denylist:
            config = os.path.join(work, "denylist.toml")
            write_denylist_config(config, denylist)
            report += run_gitleaks(args.gitleaks, tree, config, os.path.join(work, "denylist.json"))

        canary_hits = set()
        findings = list(tree.findings)
        for item in report:
            index = tree.index_of(item["File"])
            if index == canary_index:
                canary_hits.add(item["RuleID"])
                continue
            for display in tree.paths[index]:
                findings.append((item["RuleID"], item["Secret"], display))
        missing = expected - canary_hits
        if missing:
            raise ScanError("the canary was not found by the rules %s" % ", ".join(sorted(missing)))
    except ScanError as error:
        print("release scan failed: %s" % mask(str(error), denylist), file=sys.stderr)
        return 2
    finally:
        shutil.rmtree(work, ignore_errors=True)

    groups = collections.OrderedDict()
    for rule, value, display in findings:
        key = (rule, value_hash(value))
        groups.setdefault(key, []).append(display)
    new = [key for key in groups if key[0].startswith("denylist-") or key not in baseline]

    if args.write_baseline:
        with open(args.write_baseline, "w", encoding="utf-8") as out:
            for rule, digest in sorted(k for k in groups if not k[0].startswith("denylist-")):
                out.write("%s\t%s\t%s\n" % (rule, digest, args.source))
    if args.list:
        for (rule, digest), displays in groups.items():
            state = "new" if (rule, digest) in new else "baseline"
            for display in displays:
                print("%s\t%s\t%s\t%s" % (state, rule, digest, mask(display, denylist)))

    print("release scan: %d files, %d distinct texts, %d archives, %d Ubuntu archive files skipped; "
          "%d findings in the baseline, %d not"
          % (tree.stats["files"], tree.stats["texts"], tree.stats["archives"],
             tree.stats["pristine"], len(groups) - len(new), len(new)))
    if not new:
        return 0
    if not args.list:
        for rule, digest in new:
            displays = groups[(rule, digest)]
            more = " (and %d more)" % (len(displays) - 1) if len(displays) > 1 else ""
            print("finding: %s in %s%s" % (rule, mask(displays[0], denylist), more))
        print("release scan: see packaging/atlas-release-scan/README.md")
    return 1


if __name__ == "__main__":
    sys.exit(main())
