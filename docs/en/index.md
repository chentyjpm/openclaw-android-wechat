---
page_ref: /docs/apps/termux/index.html
---

# Termux App Docs

<!--- DOC_HEADER_PLACEHOLDER -->

Welcome to documentation for the [Termux App].

## Platform support

This fork currently only supports `aarch64` / `arm64-v8a`.

## Startup Script

If `~/.termux/startup.sh` exists, then it will be executed automatically in the background when Termux is opened.

This build will also auto-create a default `~/.termux/startup.sh` (OpenClaw install/update) and keep it updated across app updates if the file contains the `# OPENCLAW-MANAGED-SHA256:` marker.

In this build, the script is executed in a dedicated terminal session named `openclaw-startup` so you can see the output.

## Bundled proot-distro rootfs

Optionally, you can bundle a pre-installed `proot-distro` Debian rootfs into the APK to avoid downloading on first run.

- Create tarball on a device that already has Debian installed:
  - `tar -czf debian-rootfs.tar.gz -C "$PREFIX/var/lib/proot-distro/installed-rootfs" debian`
- Build the APK with env var `OPENCLAW_BUNDLED_DEBIAN_ROOTFS` pointing to that tarball on your build machine.

##

[Termux App]: https://github.com/termux/termux-app
