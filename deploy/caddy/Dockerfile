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
#   docker build -t ghcr.io/jakefearsd/pressroom-caddy:<tag> -f deploy/caddy/Dockerfile .
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
#      `caddy:2.11.9-alpine` image.
FROM caddy:2.11.9-builder-alpine@sha256:86e132d1d6db8850352b2dbc15f82a4cb25363ac305ee41e11405873e5931f37 AS builder

# The module is pinned like the base images: without @version xcaddy takes
# whatever its newest tag is on the day of the build, so one release tag could
# ship different code in the binary that holds the Cloudflare API token.
RUN xcaddy build --with github.com/caddy-dns/cloudflare@v0.2.4

FROM caddy:2.11.9-alpine@sha256:003a97808e781aa9f7e75cc2f43d34d461099031faf5e20faefb9f61126836f0

COPY --from=builder /usr/bin/caddy /usr/bin/caddy
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
