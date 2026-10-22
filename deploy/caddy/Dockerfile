# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  The ASF licenses this file to You
# under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# The reverse proxy with its config baked in, so a production host needs no
# checkout to bind-mount a Caddyfile from. Build from the REPO ROOT:
#   docker build -t ghcr.io/jakefearsd/roller-caddy:<tag> -f deploy/caddy/Dockerfile .
#
# Two stages, both pinned to the SAME Caddy version:
#
#   1. A `xcaddy` build stage that compiles a custom Caddy binary with the
#      Cloudflare DNS provider module baked in. Per-weblog custom domains
#      (virtual hosting) terminate on a WILDCARD certificate for the vhost
#      zone (*.{$VHOST_ZONE}, see the Caddyfile and docker_deployment.md's
#      "Custom domains" section), and a wildcard can only ever be proven via
#      the ACME DNS-01 challenge -- HTTP-01 (what stock Caddy does out of the
#      box) cannot issue for a name with a "*" in it at all. Stock
#      `caddy:*` therefore is not merely slower here, it is categorically
#      unable to obtain this certificate: it would build and run fine and
#      only fail, silently and repeatedly, when it actually tries to request
#      the wildcard. Building with xcaddy at IMAGE BUILD time is what turns
#      "no DNS provider module" into a build failure instead of a production
#      incident with no certificate ever arriving.
#   2. The same runtime base as before, with the custom-built binary swapped
#      in for the stock one -- everything else about the image (config path,
#      entrypoint, exposed ports) is inherited unchanged from the upstream
#      `caddy:2.11.7-alpine` image.
FROM caddy:2.11.7-builder-alpine@sha256:bb53bc0dd94d5e93eca88bac50e435fd5f9dc6cc828fc6140086f282fb329e92 AS builder

RUN xcaddy build --with github.com/caddy-dns/cloudflare

FROM caddy:2.11.7-alpine@sha256:24ec7b05bc532c7bb57f727030c5056e257ff7138b4b50460306819ff79362bd

COPY --from=builder /usr/bin/caddy /usr/bin/caddy
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
