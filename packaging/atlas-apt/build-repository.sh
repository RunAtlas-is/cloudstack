#!/usr/bin/env bash
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

# Builds the signed apt repository of a release from its Debian packages and
# leaves it only encrypted to the transport key, so the release artifact can
# sit on a public repository without publishing the packages.
#
# Usage: build-repository.sh DEBS_DIR RELEASE OUT_DIR
#   DEBS_DIR  the packages and their SHA256SUMS
#   RELEASE   the release tag, for example 4.22.1.1-atlas-9
#   OUT_DIR   receives apt-repository-RELEASE.tar.gpg and SHA256SUMS
#
# The armored private signing key comes in APT_SIGNING_PRIVATE_KEY. It must
# belong to the release key next to this script. ATLAS_APT_RELEASE_KEY and
# ATLAS_APT_TRANSPORT_KEY override the two public key files.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
debs="${1:?debs directory}"
release="${2:?release tag}"
out="${3:?output directory}"
release_key="${ATLAS_APT_RELEASE_KEY:-$here/atlas-cloudstack-release.asc}"
transport_key="${ATLAS_APT_TRANSPORT_KEY:-$here/atlas-cloudstack-transport.asc}"
suite=noble

case "$release" in
  *-atlas-[0-9]*) ;;
  *) echo "not a release tag: $release" >&2; exit 1 ;;
esac
[ -n "${APT_SIGNING_PRIVATE_KEY:-}" ] || { echo "APT_SIGNING_PRIVATE_KEY is empty" >&2; exit 1; }
for tool in apt-ftparchive gpg gpgv; do
  command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

work="$(mktemp -d)"
trap 'gpgconf --homedir "$work/gnupg" --kill gpg-agent >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"

(cd "$debs" && sha256sum --check --quiet SHA256SUMS)

repo="$work/repository"
mkdir -p "$repo/pool/main" "$repo/dists/$suite/main/binary-amd64"
cp "$debs"/*.deb "$repo/pool/main/"
(
  cd "$repo"
  apt-ftparchive packages pool > "dists/$suite/main/binary-amd64/Packages"
  gzip -9n --keep "dists/$suite/main/binary-amd64/Packages"
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=Atlas \
    -o "APT::FTPArchive::Release::Label=Atlas CloudStack" \
    -o "APT::FTPArchive::Release::Suite=$suite" \
    -o "APT::FTPArchive::Release::Codename=$suite" \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Architectures=amd64 \
    -o "APT::FTPArchive::Release::Description=Atlas build of CloudStack, release $release" \
    release "dists/$suite" > "$work/Release"
  mv "$work/Release" "dists/$suite/Release"
)

printf '%s\n' "$APT_SIGNING_PRIVATE_KEY" | gpg --batch --quiet --import
signing_fpr="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')"
release_fpr="$(gpg --batch --with-colons --show-keys "$release_key" | awk -F: '$1 == "fpr" { print $10; exit }')"
if [ "$signing_fpr" != "$release_fpr" ]; then
  echo "the signing key $signing_fpr is not the release key $release_fpr" >&2
  exit 1
fi
gpg --batch --yes --local-user "$signing_fpr" --clearsign \
  --output "$repo/dists/$suite/InRelease" "$repo/dists/$suite/Release"
gpg --batch --yes --local-user "$signing_fpr" --armor --detach-sign \
  --output "$repo/dists/$suite/Release.gpg" "$repo/dists/$suite/Release"

gpg --batch --dearmor < "$release_key" > "$work/release.gpg"
gpgv --quiet --keyring "$work/release.gpg" "$repo/dists/$suite/InRelease"
gpgv --quiet --keyring "$work/release.gpg" "$repo/dists/$suite/Release.gpg" "$repo/dists/$suite/Release"

mkdir -p "$out"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 \
  -C "$repo" -cf "$work/repository.tar" .
gpg --batch --yes --trust-model always --recipient-file "$transport_key" \
  --output "$out/apt-repository-$release.tar.gpg" --encrypt "$work/repository.tar"
(cd "$out" && sha256sum -- "apt-repository-$release.tar.gpg" > SHA256SUMS && cat SHA256SUMS)
echo "signed by $signing_fpr; packages:"
awk '/^Package:/ { p = $2 } /^Version:/ { print p, $2 }' "$repo/dists/$suite/main/binary-amd64/Packages"
