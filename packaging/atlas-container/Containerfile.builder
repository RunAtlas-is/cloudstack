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

# The build environment of the fork's Debian packages: the build
# dependencies of debian/control, installed once into an image so that a
# release build starts compiling at once.

FROM docker.io/library/ubuntu:24.04@sha256:73b999393d07f2bf65a40373778a44dc9a6d54ad7718d29247d8ff9413c4d6a8

ARG DEBIAN_FRONTEND=noninteractive

# Ubuntu's own archive hosts have timed out from build hosts, so packages
# come from the kernel.org mirror, which carries the same signed archive.
RUN sed -i -e "s#http://archive.ubuntu.com/ubuntu#http://mirrors.edge.kernel.org/ubuntu#g" -e "s#http://security.ubuntu.com/ubuntu#http://mirrors.edge.kernel.org/ubuntu#g" /etc/apt/sources.list.d/ubuntu.sources
# npm comes with Node.js from its own release archive, which goes first on the
# path: Ubuntu's npm package pulls in several hundred small packages, which
# made the install the slowest part of a build. Ubuntu's nodejs stays, because
# debian/control names it as a build dependency; both are version 18.
ARG NODE_VERSION=18.20.8

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential debhelper devscripts dpkg-dev fakeroot genisoimage \
       openjdk-17-jdk maven python3 python3-setuptools python3-mysql.connector nodejs lsb-release \
       git ca-certificates curl xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*' \
    && cd /tmp \
    && curl -fsSLO "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
    && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" \
       | grep " node-v${NODE_VERSION}-linux-x64.tar.xz\$" | sha256sum -c - \
    && tar -xJf "node-v${NODE_VERSION}-linux-x64.tar.xz" -C /usr/local --strip-components=1 \
    && rm "node-v${NODE_VERSION}-linux-x64.tar.xz"

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
