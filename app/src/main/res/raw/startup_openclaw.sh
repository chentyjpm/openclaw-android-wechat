#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

LOG="$HOME/.termux/startup.log"
mkdir -p "$HOME/.termux"

if command -v tee >/dev/null 2>&1; then
  exec > >(tee -a "$LOG") 2>&1
else
  exec >>"$LOG" 2>&1
fi

now_human() { date '+%Y-%m-%d %H:%M:%S%z'; }

echo "=== openclaw startup $(now_human) ==="

# Preflight: only fix dpkg state if it looks broken/interrupted.
if dpkg --audit 2>/dev/null | grep -q .; then
  echo "dpkg audit reported issues, attempting repair..."
  export DEBIAN_FRONTEND=noninteractive
  DPKG_FORCE_OPTS=(-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
  dpkg --configure -a --force-confdef --force-confold || true
  apt-get -f install -y "${DPKG_FORCE_OPTS[@]}" || true
else
  echo "dpkg ok, skip repair"
fi

# Ensure proot-distro exists (Debian rootfs is expected to be bundled).
if command -v proot-distro >/dev/null 2>&1; then
  echo "proot-distro already installed"
else
  pkg install -y proot-distro
fi

# Debian rootfs: prefer bundled rootfs if present, otherwise install via proot-distro.
FILES_DIR="$(dirname "$PREFIX")"
BUNDLED_DEBIAN_ROOTFS_TAR="$FILES_DIR/openclaw/bundles/debian-rootfs.tar.gz"
INSTALLED_ROOTFS_DIR="$PREFIX/var/lib/proot-distro/installed-rootfs"
mkdir -p "$INSTALLED_ROOTFS_DIR"

if proot-distro login debian -- true >/dev/null 2>&1; then
  echo "Debian already available for proot-distro"
else
  if [ -f "$BUNDLED_DEBIAN_ROOTFS_TAR" ]; then
    echo "Installing Debian from bundled rootfs: $BUNDLED_DEBIAN_ROOTFS_TAR"
    # Tar is expected to contain a top-level `debian/` directory.
    tar -xzf "$BUNDLED_DEBIAN_ROOTFS_TAR" -C "$INSTALLED_ROOTFS_DIR"
  else
    echo "Bundled rootfs not found, installing Debian via proot-distro"
    proot-distro install debian
  fi
fi

echo "=== done $(now_human) ==="
echo "Entering Debian..."
proot-distro login debian || {
  echo "Failed to enter Debian via: proot-distro login debian"
  echo "Try manually running: proot-distro login debian"
  exit 1
}
