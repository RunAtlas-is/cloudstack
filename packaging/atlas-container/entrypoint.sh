#!/bin/sh
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

# Starts the management server in the foreground. The database settings come
# from a db.properties mounted under /run/cloudstack, and the encryption key
# file beside it when the key is a file; cluster.node.IP must be this
# server's own address, so it is set from POD_IP here.

set -eu

conf=/etc/cloudstack/management
source_dir=${CLOUDSTACK_CONFIG_SOURCE:-/run/cloudstack}

if [ -f "$source_dir/db.properties" ]; then
    : "${POD_IP:?POD_IP must name the address of this server}"
    sed "s/^cluster\.node\.IP=.*/cluster.node.IP=${POD_IP}/" "$source_dir/db.properties" > "$conf/db.properties"
    chmod 0640 "$conf/db.properties"
fi
if [ -f "$source_dir/key" ]; then
    cp "$source_dir/key" "$conf/key"
    chmod 0640 "$conf/key"
fi

# shellcheck source=/dev/null
. /etc/default/cloudstack-management
# shellcheck disable=SC2086
exec java $JAVA_OPTS ${JAVA_EXTRA_OPTS:-} -cp "$CLASSPATH" "$BOOTSTRAP_CLASS"
