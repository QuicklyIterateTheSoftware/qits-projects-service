#!/bin/sh
# golden-masters.sh decide | jar-published | npm-publish
#
# THE GOLDEN MASTERS SHIP AS TWO ARTIFACTS AND ONE DECISION (epic qits-546): the repository-root
# `golden-masters/` tree goes out as the jar `eu.wohlben.qits:qits-projects-golden-masters` AND as the
# npm package `@qits/projects-golden-masters`, both at `$QITS_VERSION` or neither — so a consumer's
# maven and npm coordinates never name two different versions of one tree. Release step two calls
# this three times; the maven deploy itself stays in the step, because it is `./mvnw`.
#
#   decide          one line on stdout, exit 0:
#                     publish <reason>   publish BOTH at $QITS_VERSION
#                     skip <reason>      publish neither
#                   It is `publish` when ANY of these holds, and `skip` only when none does:
#                     - published-tree-changed.sh answers `first` or `changed` for the jar;
#                     - the npm package does not exist at all (its packument is a 404) — the first
#                       npm publish happens even when the jar is unchanged, and the jar then gets a
#                       new, byte-identical version so the two stay aligned;
#                     - either artifact already carries $QITS_VERSION — this is a re-run of a release
#                       that decided `publish` and may have published only one half. Without this
#                       arm a re-run after a half-landed publish would read the jar back as
#                       `unchanged $QITS_VERSION` and leave the npm half missing for good.
#   jar-published   `yes` or `no`: is the jar's .pom at $QITS_VERSION already in the store?
#   npm-publish     pack the tree as `@qits/projects-golden-masters@$QITS_VERSION` and PUT it, unless
#                   that version is already there; then assert it is. Prints the tarball's path.
#
# EVERY REGISTRY ANSWER THAT IS NOT THE EXPECTED ONE FAILS, never decides: on the packument, 200 is
# present, 404 is absent, and a 5xx, a 401 or no answer at all exits non-zero. Same rule as
# published-tree-changed.sh, and for the same reason — an error read as "absent" publishes over an
# outage, one read as "present" skips in silence.
#
# WHY A HAND-BUILT TARBALL AND THE REGISTRY'S PUBLISH PUT, NOT `npm publish`: step two runs on
# maven-base (maven + JDK on alpine, plus bash/curl/git/jq) and that image has no node, no npm and no
# python. Release steps share no filesystem, so moving the npm half to a node step would mean passing
# this decision across steps — and recomputing it there is wrong, because once step two has deployed
# the jar the gate reads `unchanged`. The npm publish protocol is one JSON document, which jq builds:
# the manifest under `versions[v]`, `dist-tags.latest`, and the tarball base64 under `_attachments`.
# qits-artifacts recomputes the tarball's hashes itself and checks only the claims a document makes,
# so the document claims the sha1 (busybox has sha1sum) and leaves `integrity` (sha512 in base64 of the
# raw digest, which busybox cannot spell without xxd) to the store, which re-emits it in packuments.
#
# THE TARBALL is what `npm pack` would produce for this package: every entry under `package/`, with
# `package/package.json` — name, version = $QITS_VERSION, `files: ["golden-masters/"]` — generated
# here, and the tree under `package/golden-masters/`. Nothing in the repository holds a package.json
# for it, so there is no second version number to drift from the tag.
#
# ENVIRONMENT
#   QITS_VERSION                the release version (the composer guards it)
#   QITS_MAVEN_REGISTRY_URL     the maven repository root
#   QITS_NPM_REGISTRY_URL       the hosted npm registry (the @qits scope's), trailing slash or not
#   QITS_TOKEN                  optional bearer for reads, omitted when unset — never an empty bearer
#   QITS_PUBLISH_TOKEN_COMMAND  optional; mints the publish bearer at the call site, the way the npm
#                               and maven publishes in qits-ci's archetypes do. No mint = anonymous.
#   GOLDEN_MASTERS_TGZ          optional; where npm-publish writes the tarball (default /tmp/…)
set -eu

die() {
  echo "golden-masters: $*" >&2
  exit 1
}

here=$(dirname "$0")
tree=golden-masters
coordinate="eu.wohlben.qits:qits-projects-golden-masters"
package="@qits/projects-golden-masters"

version=${QITS_VERSION:?QITS_VERSION is the version both artifacts are published at}
case "$version" in
  ''|*[!A-Za-z0-9._+-]*) die "'$version' is not a version" ;;
esac

npm_root=${QITS_NPM_REGISTRY_URL:?QITS_NPM_REGISTRY_URL is the npm registry to publish to}
npm_root=${npm_root%/}
packument_url="$npm_root/@qits%2fprojects-golden-masters"

maven_root=${QITS_MAVEN_REGISTRY_URL:?QITS_MAVEN_REGISTRY_URL is the maven repository}
maven_root=${maven_root%/}
pom_url="$maven_root/eu/wohlben/qits/qits-projects-golden-masters/$version/qits-projects-golden-masters-$version.pom"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# GET <url> into <file>, printing the HTTP status; no answer at all is fatal.
get() {
  if [ -n "${QITS_TOKEN:-}" ]; then
    curl -sS -L --retry 2 --retry-delay 1 -o "$2" -w '%{http_code}' \
      -H "Authorization: Bearer $QITS_TOKEN" "$1" || die "cannot reach $1"
  else
    curl -sS -L --retry 2 --retry-delay 1 -o "$2" -w '%{http_code}' "$1" || die "cannot reach $1"
  fi
}

# The packument into $work/packument.json; prints `present` or `absent`, fails on anything else.
npm_package() {
  status=$(get "$packument_url" "$work/packument.json")
  case "$status" in
    200) echo present ;;
    404) echo absent ;;
    *) die "GET $packument_url answered HTTP $status — not deciding on an error" ;;
  esac
}

# Does the fetched packument carry $version? `yes` / `no`; a body that is not a packument fails.
npm_has_version() {
  jq -e 'type == "object"' "$work/packument.json" > /dev/null 2>&1 \
    || die "$packument_url answered 200 with a body that is not a JSON object"
  if jq -e --arg v "$version" '(.versions // {}) | has($v)' "$work/packument.json" > /dev/null; then
    echo yes
  else
    echo no
  fi
}

jar_published() {
  status=$(get "$pom_url" "$work/pom.xml")
  case "$status" in
    200) echo yes ;;
    404) echo no ;;
    *) die "GET $pom_url answered HTTP $status — not deciding on an error" ;;
  esac
}

decide() {
  gate=$(sh "$here/published-tree-changed.sh" "$coordinate" "$tree") \
    || die "the jar's change gate failed"
  npm=$(npm_package)
  npm_v=no
  if [ "$npm" = present ]; then
    npm_v=$(npm_has_version)
  fi
  jar_v=$(jar_published)

  case "$gate" in
    first)         echo "publish first publish of the jar"; return ;;
    "changed "*)   echo "publish the tree changed since ${gate#changed }"; return ;;
    "unchanged "*) ;;
    *) die "the jar's change gate answered '$gate'" ;;
  esac
  if [ "$npm" = absent ]; then
    echo "publish first publish of $package (the jar is unchanged since ${gate#unchanged })"
  elif [ "$jar_v" = yes ] || [ "$npm_v" = yes ]; then
    echo "publish re-run: $version is already published (jar: $jar_v, npm: $npm_v) — completing both"
  else
    echo "skip unchanged since ${gate#unchanged }, and $package exists"
  fi
}

pack() {
  out=$1
  [ -n "$(find "$tree" -type f | head -1)" ] || die "$tree holds no files"
  mkdir -p "$work/pack/package"
  cp -R "$tree" "$work/pack/package/$tree"
  jq -n --arg name "$package" --arg v "$version" --arg tree "$tree/" '{
      name: $name,
      version: $v,
      description: "qits-projects provider golden masters (epic qits-546): golden-masters/index.json and one JSON per (state, operation)",
      license: "UNLICENSED",
      files: [$tree]
    }' > "$work/pack/package/package.json"
  # Sorted entries so the archive does not depend on directory order; mtime/owner are not content.
  (cd "$work/pack" && find package -type f | LC_ALL=C sort > "$work/entries")
  tar -czf "$out" -C "$work/pack" -T "$work/entries" || die "cannot pack $tree"
}

npm_publish() {
  tgz=${GOLDEN_MASTERS_TGZ:-/tmp/qits-projects-golden-masters-$version.tgz}
  pack "$tgz"

  # Assigned, never tested inline: `set -e` does not reach a `$(…)` inside an `if`, so a failing
  # probe there would read as "no".
  npm=$(npm_package)
  npm_v=no
  if [ "$npm" = present ]; then
    npm_v=$(npm_has_version)
  fi
  if [ "$npm_v" = yes ]; then
    echo "$package@$version is already published — not publishing it again" >&2
  else
    shasum=$(sha1sum "$tgz" | cut -d' ' -f1)
    base64 < "$tgz" | tr -d '\n' > "$work/tgz.b64"
    jq -n --arg name "$package" --arg v "$version" --arg shasum "$shasum" \
        --arg file "$package-$version.tgz" --rawfile data "$work/tgz.b64" \
        --argjson length "$(wc -c < "$tgz")" \
        --slurpfile manifest "$work/pack/package/package.json" '{
          _id: $name,
          name: $name,
          "dist-tags": { latest: $v },
          versions: { ($v): ($manifest[0] + { _id: ($name + "@" + $v), dist: { shasum: $shasum } }) },
          _attachments: { ($file): { content_type: "application/octet-stream", data: $data, length: $length } }
        }' > "$work/publish.json"

    token=""
    if [ -n "${QITS_PUBLISH_TOKEN_COMMAND:-}" ] \
      && command -v "$QITS_PUBLISH_TOKEN_COMMAND" > /dev/null 2>&1; then
      token=$("$QITS_PUBLISH_TOKEN_COMMAND" 2>/dev/null) || token=""
    fi
    set -- -X PUT -H "Content-Type: application/json" --data-binary "@$work/publish.json"
    [ -z "$token" ] || set -- "$@" -H "Authorization: Bearer $token"
    status=$(curl -sS -o "$work/publish.out" -w '%{http_code}' "$@" "$packument_url") \
      || die "cannot reach $packument_url"
    case "$status" in
      200|201) echo "published $package@$version" >&2 ;;
      *) die "PUT $packument_url answered HTTP $status: $(head -c 500 "$work/publish.out")" ;;
    esac
  fi

  # AND THE VERSION PUBLISHED IS THE VERSION THIS RELEASE IS, or `announce: if-published` would
  # skip a release whose package went somewhere else.
  npm=$(npm_package)
  [ "$npm" = present ] || die "the publish did not leave $package at all"
  npm_v=$(npm_has_version)
  [ "$npm_v" = yes ] || die "the publish did not leave $package at $version"
  echo "$tgz"
}

[ "$#" -eq 1 ] || die "usage: $0 decide | jar-published | npm-publish"
case "$1" in
  decide) decide ;;
  jar-published) jar_published ;;
  npm-publish) npm_publish ;;
  *) die "usage: $0 decide | jar-published | npm-publish" ;;
esac
