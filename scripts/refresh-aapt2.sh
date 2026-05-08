#!/usr/bin/env bash
#
# refresh-aapt2.sh
#
# Maintenance helper that refreshes the prebuilt aapt2 binaries shipped with
# Apktool. It:
#
#   1. Bootstraps the Android cmdline-tools (and therefore sdkmanager) if no
#      Android SDK is present locally.
#   2. Uses sdkmanager to install the requested build-tools package on every
#      supported host platform (linux / macosx / windows).
#   3. Copies the matching aapt2 binary into
#      brut.apktool/apktool-lib/src/main/resources/prebuilt/<platform>/.
#
# This is a *maintenance* script. It is intentionally NOT invoked by Gradle
# nor by CI - bumping aapt2 is an explicit, reviewed action.
#
# Usage:
#   scripts/refresh-aapt2.sh                 # default build-tools version (36.0.0)
#   BUILD_TOOLS_VERSION=36.0.0 scripts/refresh-aapt2.sh
#
# Environment overrides:
#   BUILD_TOOLS_VERSION   Build-tools package to install (e.g. 36.0.0).
#   ANDROID_SDK_ROOT      Existing Android SDK location. If unset, a temporary
#                         SDK is provisioned under .sisyphus/android-sdk/.
#   PLATFORMS             Space-separated list of platforms to refresh.
#                         Default: "linux macosx windows".
#
# Notes on cross-platform aapt2:
#   sdkmanager only installs aapt2 for the host that runs it. To refresh ALL
#   three prebuilt binaries from a single host you need either:
#     - to run this script on each host (linux/mac/windows), or
#     - to download the per-platform build-tools archives directly from
#       Google's repository. This script supports the latter via the
#       MIRROR_FROM_REMOTE=1 mode, which fetches the OS-specific zip from
#       https://dl.google.com/android/repository/.

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly PREBUILT_ROOT="${REPO_ROOT}/brut.apktool/apktool-lib/src/main/resources/prebuilt"

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
PLATFORMS="${PLATFORMS:-linux macosx windows}"
MIRROR_FROM_REMOTE="${MIRROR_FROM_REMOTE:-0}"

# Map of major build-tools revisions to the matching `dl.google.com` archive
# token. dl.google.com switched the separator in front of the platform suffix
# from `-` (e.g. `build-tools_r34-linux.zip`) to `_` starting at r36
# (e.g. `build-tools_r36_linux.zip`). The lookup below tracks that.
remote_archive_token() {
    local version="$1"
    local platform="$2"
    local major
    major="${version%%.*}"

    if (( major >= 36 )); then
        printf 'build-tools_r%s_%s.zip' "${major}" "${platform}"
    else
        printf 'build-tools_r%s-%s.zip' "${version}" "${platform}"
    fi
}

log()  { printf '[refresh-aapt2] %s\n' "$*" >&2; }
fail() { log "ERROR: $*"; exit 1; }

require() {
    command -v "$1" >/dev/null 2>&1 || fail "missing required tool: $1"
}

bootstrap_sdkmanager() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
        log "using existing sdkmanager at ${ANDROID_SDK_ROOT}"
        return 0
    fi

    local sdk_dir="${REPO_ROOT}/.sisyphus/android-sdk"
    local cmdline_dir="${sdk_dir}/cmdline-tools/latest"

    if [[ -x "${cmdline_dir}/bin/sdkmanager" ]]; then
        export ANDROID_SDK_ROOT="${sdk_dir}"
        log "using bootstrapped sdkmanager at ${sdk_dir}"
        return 0
    fi

    require curl
    require unzip

    local host_kind
    case "$(uname -s)" in
        Linux*)  host_kind="linux" ;;
        Darwin*) host_kind="mac" ;;
        MINGW*|MSYS*|CYGWIN*) host_kind="win" ;;
        *) fail "unsupported host OS: $(uname -s)" ;;
    esac

    # Pinned cmdline-tools revision; bump as needed.
    local cmdline_zip_url="https://dl.google.com/android/repository/commandlinetools-${host_kind}-11076708_latest.zip"
    local tmp_zip; tmp_zip="$(mktemp -t cmdline-tools.XXXXXX.zip)"

    log "downloading cmdline-tools (${host_kind})"
    curl -fSL "${cmdline_zip_url}" -o "${tmp_zip}"

    mkdir -p "${sdk_dir}/cmdline-tools"
    rm -rf "${cmdline_dir}"
    unzip -q "${tmp_zip}" -d "${sdk_dir}/cmdline-tools"
    mv "${sdk_dir}/cmdline-tools/cmdline-tools" "${cmdline_dir}"
    rm -f "${tmp_zip}"

    export ANDROID_SDK_ROOT="${sdk_dir}"
    log "bootstrapped sdkmanager at ${sdk_dir}"
}

accept_licenses() {
    log "accepting Android SDK licenses"
    yes | "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
}

install_build_tools_local() {
    local sdkmanager="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
    log "installing build-tools;${BUILD_TOOLS_VERSION} via sdkmanager"
    "${sdkmanager}" "build-tools;${BUILD_TOOLS_VERSION}" >/dev/null
}

copy_local_aapt2() {
    local target_platform="$1"
    local src_root="${ANDROID_SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}"

    [[ -d "${src_root}" ]] || fail "build-tools not found at ${src_root}"

    local src_bin dest_bin
    case "${target_platform}" in
        linux|macosx)
            src_bin="${src_root}/aapt2"
            dest_bin="${PREBUILT_ROOT}/${target_platform}/aapt2"
            ;;
        windows)
            src_bin="${src_root}/aapt2.exe"
            dest_bin="${PREBUILT_ROOT}/windows/aapt2.exe"
            ;;
        *)
            fail "unknown platform: ${target_platform}"
            ;;
    esac

    [[ -f "${src_bin}" ]] || fail "aapt2 binary not found at ${src_bin} (host sdkmanager only ships host-native binaries; use MIRROR_FROM_REMOTE=1 to fetch others)"

    install -m 0755 "${src_bin}" "${dest_bin}"
    log "updated ${dest_bin}"
}

mirror_from_remote() {
    local target_platform="$1"
    require curl
    require unzip

    local remote_kind
    case "${target_platform}" in
        linux)   remote_kind="linux" ;;
        macosx)  remote_kind="macosx" ;;
        windows) remote_kind="windows" ;;
        *) fail "unknown platform: ${target_platform}" ;;
    esac

    local zip_url="https://dl.google.com/android/repository/$(remote_archive_token "${BUILD_TOOLS_VERSION}" "${remote_kind}")"
    local tmp_zip tmp_dir
    tmp_zip="$(mktemp -t build-tools-${remote_kind}.XXXXXX.zip)"
    tmp_dir="$(mktemp -d -t build-tools-${remote_kind}.XXXXXX)"

    log "downloading ${zip_url}"
    curl -fSL "${zip_url}" -o "${tmp_zip}"
    unzip -q "${tmp_zip}" -d "${tmp_dir}"

    local extracted_root
    extracted_root="$(find "${tmp_dir}" -maxdepth 2 -type d -name 'android-*' | head -n1)"
    [[ -n "${extracted_root}" ]] || fail "could not locate extracted build-tools directory"

    local src_bin dest_bin
    if [[ "${target_platform}" == "windows" ]]; then
        src_bin="${extracted_root}/aapt2.exe"
        dest_bin="${PREBUILT_ROOT}/windows/aapt2.exe"
    else
        src_bin="${extracted_root}/aapt2"
        dest_bin="${PREBUILT_ROOT}/${target_platform}/aapt2"
    fi

    [[ -f "${src_bin}" ]] || fail "aapt2 missing inside ${zip_url}"
    install -m 0755 "${src_bin}" "${dest_bin}"
    log "updated ${dest_bin}"

    rm -rf "${tmp_zip}" "${tmp_dir}"
}

main() {
    [[ -d "${PREBUILT_ROOT}" ]] || fail "prebuilt directory missing: ${PREBUILT_ROOT}"

    if [[ "${MIRROR_FROM_REMOTE}" == "1" ]]; then
        log "MIRROR_FROM_REMOTE=1 - fetching per-platform build-tools archives directly"
        for platform in ${PLATFORMS}; do
            mirror_from_remote "${platform}"
        done
    else
        bootstrap_sdkmanager
        accept_licenses
        install_build_tools_local
        for platform in ${PLATFORMS}; do
            copy_local_aapt2 "${platform}" || log "skipped ${platform} (host sdkmanager cannot provide it)"
        done
    fi

    log "verifying prebuilt aapt2 binaries"
    for platform in ${PLATFORMS}; do
        local bin
        if [[ "${platform}" == "windows" ]]; then
            bin="${PREBUILT_ROOT}/windows/aapt2.exe"
        else
            bin="${PREBUILT_ROOT}/${platform}/aapt2"
        fi
        if [[ -f "${bin}" ]]; then
            log "  ${platform}: $(file -b "${bin}" | cut -c1-80)"
        else
            log "  ${platform}: MISSING"
        fi
    done

    log "done. Review the diff and commit if intentional."
}

main "$@"
