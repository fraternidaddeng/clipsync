#!/usr/bin/env bash
# Idempotent Cloud Agent setup for ClipSync on Linux (x86_64 Ubuntu).
#
# Prepares the toolchain needed by the repository's canonical commands:
#   - .NET 8 SDK  (windows/ClipSync.sln cross-platform projects + tests)
#   - JDK 17      (android Gradle build; default `java` is pinned to 17)
#   - Android SDK (platform 35, build-tools 35.0.0, platform-tools)
#   - Python + jsonschema/referencing (scripts/validate-protocol.py)
#   - PowerShell 7 (scripts/*.ps1 wrappers)
#
# The Windows WPF app (windows/ClipSync.App, net8.0-windows) is Windows-only and
# is intentionally not built here; CI builds it on windows-latest.
#
# Safe to run repeatedly: every step checks for an existing install first, so on a
# warm snapshot the heavy downloads are skipped and only dependency refresh runs.
set -euo pipefail

DOTNET_VERSION="8.0.419"
PWSH_VERSION="7.4.6"
JDK17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
DOTNET_DIR="/usr/share/dotnet"
ANDROID_SDK_DIR="/opt/android-sdk"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi
log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

log "Installing system packages (JDK 17, build utilities)"
if ! dpkg -s openjdk-17-jdk >/dev/null 2>&1; then
  $SUDO apt-get update -y
  $SUDO DEBIAN_FRONTEND=noninteractive apt-get install -y \
    openjdk-17-jdk unzip zip wget curl libicu-dev python3-pip python-is-python3
else
  echo "openjdk-17-jdk already present"
fi

log "Pinning default java/javac to 17"
if [ -x "$JDK17_HOME/bin/java" ]; then
  $SUDO update-alternatives --set java "$JDK17_HOME/bin/java" || true
  $SUDO update-alternatives --set javac "$JDK17_HOME/bin/javac" || true
fi

log "Installing .NET SDK ${DOTNET_VERSION}"
if [ ! -x "$DOTNET_DIR/dotnet" ] || ! "$DOTNET_DIR/dotnet" --list-sdks 2>/dev/null | grep -q "^${DOTNET_VERSION} "; then
  tmp_installer="$(mktemp)"
  curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$tmp_installer"
  chmod +x "$tmp_installer"
  $SUDO "$tmp_installer" --version "$DOTNET_VERSION" --install-dir "$DOTNET_DIR"
  rm -f "$tmp_installer"
else
  echo ".NET SDK ${DOTNET_VERSION} already present"
fi
$SUDO ln -sf "$DOTNET_DIR/dotnet" /usr/local/bin/dotnet

log "Installing PowerShell ${PWSH_VERSION}"
if ! command -v pwsh >/dev/null 2>&1; then
  tmp_pwsh="$(mktemp -d)"
  curl -fsSL "https://github.com/PowerShell/PowerShell/releases/download/v${PWSH_VERSION}/powershell-${PWSH_VERSION}-linux-x64.tar.gz" \
    -o "$tmp_pwsh/pwsh.tar.gz"
  $SUDO mkdir -p /opt/microsoft/powershell/7
  $SUDO tar zxf "$tmp_pwsh/pwsh.tar.gz" -C /opt/microsoft/powershell/7
  $SUDO chmod +x /opt/microsoft/powershell/7/pwsh
  $SUDO ln -sf /opt/microsoft/powershell/7/pwsh /usr/local/bin/pwsh
  rm -rf "$tmp_pwsh"
else
  echo "pwsh already present"
fi

log "Installing Python protocol-validation dependencies"
if ! python3 -c "import jsonschema, referencing" >/dev/null 2>&1; then
  $SUDO pip install --break-system-packages jsonschema referencing
else
  echo "jsonschema + referencing already present"
fi

log "Installing Android SDK (platform 35, build-tools 35.0.0, platform-tools)"
$SUDO mkdir -p "$ANDROID_SDK_DIR"
$SUDO chown -R "$(id -u):$(id -g)" "$ANDROID_SDK_DIR"
if [ ! -x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp_sdk="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}" -o "$tmp_sdk/tools.zip"
  unzip -q "$tmp_sdk/tools.zip" -d "$tmp_sdk/extract"
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
  rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
  mv "$tmp_sdk/extract/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
  rm -rf "$tmp_sdk"
fi
export ANDROID_HOME="$ANDROID_SDK_DIR" ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export JAVA_HOME="$JDK17_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$PATH"
SDKMANAGER="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -d "$ANDROID_SDK_DIR/platforms/android-35" ] \
   || [ ! -d "$ANDROID_SDK_DIR/build-tools/35.0.0" ] \
   || [ ! -d "$ANDROID_SDK_DIR/platform-tools" ]; then
  yes | "$SDKMANAGER" --licenses >/dev/null
  "$SDKMANAGER" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
else
  echo "Android SDK packages already present"
fi

log "Writing toolchain environment config"
# /etc/environment is read by PAM at session start; /etc/profile.d covers login and
# tmux shells; the bashrc hook covers interactive non-login shells. Together they make
# the toolchain visible to the agent regardless of how its shell is spawned.
$SUDO tee /etc/profile.d/clipsync.sh >/dev/null <<'PROFILE'
# ClipSync Cloud Agent toolchain environment
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export DOTNET_ROOT=/usr/share/dotnet
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
case ":$PATH:" in
  *":/usr/local/bin:"*) ;;
  *) export PATH="/usr/local/bin:$PATH" ;;
esac
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
PROFILE
$SUDO tee /etc/environment >/dev/null <<'ENVFILE'
PATH="/usr/lib/jvm/java-17-openjdk-amd64/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
DOTNET_ROOT="/usr/share/dotnet"
ANDROID_HOME="/opt/android-sdk"
ANDROID_SDK_ROOT="/opt/android-sdk"
DOTNET_CLI_TELEMETRY_OPTOUT="1"
ENVFILE
for rc in /etc/bash.bashrc "$HOME/.bashrc"; do
  [ -f "$rc" ] || continue
  if ! grep -qF "# >>> clipsync toolchain env >>>" "$rc"; then
    printf '\n# >>> clipsync toolchain env >>>\n[ -f /etc/profile.d/clipsync.sh ] && . /etc/profile.d/clipsync.sh\n# <<< clipsync toolchain env <<<\n' \
      | ($SUDO tee -a "$rc" >/dev/null 2>&1 || tee -a "$rc" >/dev/null)
  fi
done

log "Writing android/local.properties (SDK pointer for Gradle)"
printf 'sdk.dir=%s\n' "$ANDROID_SDK_DIR" > "$REPO_ROOT/android/local.properties"

log "Restoring .NET dependencies (cross-platform projects)"
"$DOTNET_DIR/dotnet" restore "$REPO_ROOT/windows/ClipSync.Tests/ClipSync.Tests.csproj"

log "ClipSync Cloud Agent setup complete"
