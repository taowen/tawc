#!/bin/bash
# Runs inside registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie.
# Mirrors the "fdroid build" job of fdroiddata's .gitlab-ci.yml as closely
# as this rig can: same image, same fdroidserver (master tarball), same
# user (vagrant), same fdroid invocation.
#
# Omitted vs CI, none of which affect this app's build:
#   - the changed-app detection and codequality/artifact plumbing
#   - the keystore setup and `fdroid publish`; the rig stops at the
#     unsigned APK, which the maintainer signs on the host
#   - the production-hardening .gitconfig, which CI installs for
#     `fdroid fetchsrclibs` and deletes again before `fdroid build`; this
#     recipe declares no srclibs
#
# TAWC_APP (me.phie.tawc:<versionCode>) and TAWC_MODE come from run.sh.
set -e
set -x

APP="${TAWC_APP:?}"
MODE="${TAWC_MODE:?}"
CI_PROJECT_DIR=/builds/fdroiddata

source /etc/profile.d/bsenv.sh
cd "$CI_PROJECT_DIR"

# The bind-mounted repo is owned by the host user, which is root in this
# rootless container, while the build runs as vagrant — git refuses to read
# it without an explicit exception. Rig-only; on the buildserver the repo is
# cloned over the network.
git config --system --add safe.directory /srv/tawc.git

apt-get update
apt-get -qy dist-upgrade

# fdroidserver from master, exactly as CI does it
rm -rf "$fdroidserver"
mkdir -p "$fdroidserver"
curl --silent https://gitlab.com/fdroid/fdroidserver/-/archive/master/fdroidserver-master.tar.gz \
    | tar -xz --directory="$fdroidserver" --strip-components=1
export PATH="$fdroidserver:$PATH"
export PYTHONPATH="$fdroidserver:$fdroidserver/examples"
export PYTHONUNBUFFERED=true
export serverwebroot=/tmp
fdroid --version || true  # source tarball has no version metadata

# packages the production buildserver has that the image lacks
sdkmanager "platform-tools" "build-tools;31.0.0"
git -C "$home_vagrant/gradlew-fdroid" pull

# the mounted caches come in owned by the host user (root here), and the
# build runs as vagrant
chown -R vagrant "$home_vagrant/.gradle" "$home_vagrant/.cargo" "$home_vagrant/.cache" 2>/dev/null || true

for d in logs tmp unsigned "$home_vagrant/.android" "$home_vagrant/.gradle" "$home_vagrant/metadata"; do
    test -d "$d" || mkdir -p "$d"
    chown -R vagrant "$d"
done
# fdroid writes its outputs next to $HOME; symlink them back onto the
# bind mount so the host can collect them.
ln -sfn "$CI_PROJECT_DIR/tmp" "$home_vagrant/tmp"
ln -sfn "$CI_PROJECT_DIR/unsigned" "$home_vagrant/unsigned"
ln -sfn "$CI_PROJECT_DIR/srclibs" "$home_vagrant/srclibs"
export GRADLE_USER_HOME=$home_vagrant/.gradle

apt-get install -y sudo openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java

fdroid_cmd_prefix="sudo --preserve-env --user vagrant
    env PATH=$fdroidserver:$PATH
    env PYTHONPATH=$fdroidserver:$fdroidserver/examples
    env PYTHONUNBUFFERED=true
    env TERM=${TERM:-dumb}
    env HOME=$home_vagrant"
fdroid_cmd="$fdroid_cmd_prefix fdroid"

# verify mode throws the APK away (`--test` leaves it in tmp/); release
# mode keeps it, which is what puts it in unsigned/.
build_args="--verbose --refresh-scanner --on-server --no-tarball"
[ "$MODE" = release ] || build_args="$build_args --test"

# reproduce mode: serve the reference APK so fdroidserver's `Binaries:`
# download-and-verify path runs against it exactly as it would against
# GitHub. prepare.sh rewrote the recipe to point here. It has to be
# HTTPS with a trusted cert: fdroidserver's downloader mounts no plain
# `http://` adapter, so an http URL fails with "No connection adapters
# were found".
if [ "$MODE" = reproduce ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
        -keyout /tmp/rig-key.pem -out /tmp/rig-cert.pem \
        -subj /CN=127.0.0.1 -addext subjectAltName=IP:127.0.0.1 2>/dev/null
    cp /tmp/rig-cert.pem /usr/local/share/ca-certificates/tawc-rig.crt
    update-ca-certificates >/dev/null
    export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
    fdroid_cmd="$fdroid_cmd_prefix env REQUESTS_CA_BUNDLE=$REQUESTS_CA_BUNDLE fdroid"
    python3 - <<'PYEOF' &
import functools, http.server, ssl
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain("/tmp/rig-cert.pem", "/tmp/rig-key.pem")
handler = functools.partial(http.server.SimpleHTTPRequestHandler,
                            directory="/srv/reference")
srv = http.server.HTTPServer(("127.0.0.1", 8099), handler)
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
PYEOF
    trap 'kill %1 2>/dev/null' EXIT
fi

test -d build || mkdir build
cp -R "$CI_PROJECT_DIR/build" "$home_vagrant/build"
cp -R metadata/me.phie.tawc.yml "$home_vagrant/metadata"
chown -R vagrant "$home_vagrant" "$CI_PROJECT_DIR"

cd "$home_vagrant"
$fdroid_cmd fetchsrclibs "$APP" --verbose
(unset CI; $fdroid_cmd build $build_args "$APP")

ls -l "$CI_PROJECT_DIR/tmp" "$CI_PROJECT_DIR/unsigned"
