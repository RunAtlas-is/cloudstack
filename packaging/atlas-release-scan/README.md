<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# Release scan

The Debian packages and container images that the Atlas build publishes are
public, so none of them may carry a credential or a detail of the environment
that builds or runs them. The `packages`, `builder`, `lifecycle-image` and
`image` jobs of `.github/workflows/atlas-build.yml` run `scan.py` on what
they built, before the step that uploads or pushes it, and a finding fails
the job.

## What it checks

`scan.py` unpacks the artifact completely: each package's control and data
trees, each image layer on its own, the image configuration and history, and
every archive inside them. Every file becomes its printable strings, and
gitleaks scans the result with its default rules and `rules.toml`: private key
material, private IPv4 addresses, home directory paths other than the CI
runner's, and email addresses outside upstream's domain. Files of an image
that are byte for byte the files of an Ubuntu archive package are not
scanned.

The repository secret `RELEASE_SCAN_DENYLIST` holds literal values of the
environment, one per line: host names, addresses, user names, domains. A
release run fails when it is empty. The values never appear in the repository
or a log: a match is reported by its line number in the secret.

## When it fails

The job log names the rule and the file of each finding, never its value.
Run the scan on the same artifact locally to see the value's hash:

```bash
bash packaging/atlas-release-scan/install-gitleaks.sh /tmp/gitleaks
python3 -I packaging/atlas-release-scan/scan.py --gitleaks /tmp/gitleaks/gitleaks --list dir dist
```

A value of ours is fixed in the build; nothing is published until the scan
passes. A value that upstream CloudStack or a third-party component already
publishes goes into `baseline.tsv` with its source. `--write-baseline FILE
--source NAME` writes the entries for every finding of a run, for example of
upstream's own packages of the same version.
