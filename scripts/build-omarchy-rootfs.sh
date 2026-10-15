#!/bin/bash
# Build an Omarchy-flavored Arch Linux ARM rootfs as an app asset.
#
# Omarchy (https://omarchy.org) is an Arch Linux + Hyprland desktop; a phone
# terminal gets its command-line half: Arch's pacman and rolling packages plus
# Omarchy's terminal toolset and shell defaults (starship, eza, fzf, zoxide,
# bat, ripgrep, fd, btop, neovim, git) — see scripts/omarchy-overlay/.
#
# Rather than repacking the 800 MB Arch Linux ARM image (kernel + firmware the
# emulator never uses) this pacstraps a lean aarch64 tree straight from the
# Arch Linux ARM repos, running package scriptlets under qemu binfmt. The
# result is named per the asset scheme the app discovers
# (<id>_<version>_aarch64_rootfs.tar.xz — see UserlandDistro.java).
#
# Needs: pacman (an Arch host, or the archlinux container in CI), xz, curl,
# bsdtar, and an aarch64 binfmt handler with the F flag (qemu-user-static).
# Runs rootless through a user namespace (needs /etc/subuid ranges), or as
# real root inside a container.
#
# The pacman keyring is deliberately NOT initialized here: its master key must
# be generated on each device, so the first login does it (overlay
# /etc/profile.d/omarchy.sh).
set -e

MIRROR=${ALARM_MIRROR:-https://ca.us.mirror.archlinuxarm.org}
HERE=$(dirname "$(realpath "$0")")
ROOTFS_STORAGE=$HERE/../UserlandRootfs
OVERLAY=$HERE/omarchy-overlay

PACKAGES=(
	# base userland (no kernel, firmware, systemd or bootloader)
	filesystem bash coreutils findutils grep sed gawk tar gzip xz bzip2 zstd
	which less file diffutils procps-ng psmisc shadow util-linux iana-etc
	ca-certificates curl pacman archlinuxarm-keyring sudo nano man-db
	# Omarchy terminal toolset
	starship eza fzf zoxide bat ripgrep fd btop neovim git openssh
	bash-completion
)

if [ "$(id -u)" != 0 ]; then
	# Full uid/gid range so package files keep their real owners.
	exec unshare --map-auto --map-root-user --mount --fork -- "$0" "$@"
fi

if [ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ] && [ "$(uname -m)" != aarch64 ]; then
	echo "error: no aarch64 binfmt handler (install qemu-user-static-binfmt)" >&2
	exit 1
fi

mkdir -p "$ROOTFS_STORAGE"
WORK=$(mktemp -d)
# --one-file-system: never descend into the /dev and /proc binds below.
cleanup() {
	for node in null zero full random urandom tty; do
		umount "$ROOT/dev/$node" 2>/dev/null || true
	done
	umount -l "$ROOT/proc" 2>/dev/null || true
	rm -rf --one-file-system "$WORK"
}
trap cleanup EXIT
ROOT=$WORK/root
mkdir -p "$ROOT/var/lib/pacman" "$WORK/cache" "$WORK/keyrings" "$WORK/gnupg"

cat > "$WORK/pacman.conf" <<EOF
[options]
Architecture = aarch64
SigLevel = Required DatabaseOptional
LocalFileSigLevel = Optional
ParallelDownloads = 5
DisableSandbox
$(grep '^NoExtract' "$OVERLAY/etc/pacman.conf")
[core]
Server = ${MIRROR}/\$arch/\$repo
[extra]
Server = ${MIRROR}/\$arch/\$repo
[alarm]
Server = ${MIRROR}/\$arch/\$repo
EOF
PAC=(pacman --config "$WORK/pacman.conf" --root "$ROOT" --cachedir "$WORK/cache"
	--gpgdir "$WORK/gnupg" --noconfirm --disable-download-timeout)

# Bootstrap a throwaway build keyring from the archlinuxarm-keyring package
# (fetched unverified — it is the trust anchor), then install everything else
# with signatures required.
"${PAC[@]}" -Sy
sed 's/^SigLevel.*/SigLevel = Never/' "$WORK/pacman.conf" > "$WORK/pacman-nosig.conf"
"${PAC[@]}" --config "$WORK/pacman-nosig.conf" -Sw --nodeps archlinuxarm-keyring
bsdtar -xf "$WORK"/cache/archlinuxarm-keyring-*.pkg.tar.* -C "$WORK/keyrings" \
	--strip-components 4 usr/share/pacman/keyrings
pacman-key --gpgdir "$WORK/gnupg" --init >/dev/null
pacman-key --gpgdir "$WORK/gnupg" --populate-from "$WORK/keyrings" --populate archlinuxarm >/dev/null

# Arch's pacman depends on systemd, which pulls in dbus, udev, cryptsetup…
# none of which can run under the emulator (no init, no kernel interfaces).
# A local-db stub provides it, so neither this build nor on-device updates
# ever install it; systemd-libs (a real library dependency) still comes in.
STUB=$ROOT/var/lib/pacman/local/a-sh-systemd-stub-1-1
mkdir -p "$STUB"
echo 9 > "$ROOT/var/lib/pacman/local/ALPM_DB_VERSION"
printf '%s\n' %NAME% a-sh-systemd-stub '' %VERSION% 1-1 '' \
	%DESC% 'A-SH: systemd cannot run under the emulator; satisfies dependencies on it' '' \
	%ARCH% any '' %INSTALLDATE% "$(date +%s)" '' %SIZE% 0 '' \
	%REASON% 0 '' %PROVIDES% systemd=999 'systemd-sysvcompat=999' '' > "$STUB/desc"
printf '%%FILES%%\n\n' > "$STUB/files"

# Scriptlets run chrooted (through binfmt) and want a few device nodes and
# /proc. The mount namespace is private and dies with this script, so there
# is no unmount; tar and the cleanup trap both stay on this filesystem.
mkdir -p "$ROOT/dev" "$ROOT/proc"
for node in null zero full random urandom tty; do
	touch "$ROOT/dev/$node"
	mount --bind "/dev/$node" "$ROOT/dev/$node"
done
mount --rbind /proc "$ROOT/proc"
"${PAC[@]}" -S --needed "${PACKAGES[@]}"

# Packages the phone never needs pulled in as dependencies are fine; caches
# and the build-time db sync files are not.
rm -rf "$ROOT"/var/cache/pacman/pkg/* "$ROOT"/var/lib/pacman/sync/*
rm -f "$ROOT/etc/resolv.conf"   # the app writes a real one at install

cp -a "$OVERLAY"/. "$ROOT"/
chown -R 0:0 "$ROOT"/etc/omarchy "$ROOT"/etc/profile.d "$ROOT"/etc/pacman.conf \
	"$ROOT"/root "$ROOT"/usr/local/bin
chmod 755 "$ROOT"/usr/local/bin/*

VERSION=$(date -u +%Y.%m.%d)
OUT="${ROOTFS_STORAGE}/omarchy_${VERSION}_aarch64_rootfs.tar.xz"
echo "Packing ${OUT} ($(du -shx "$ROOT" | cut -f1) unpacked)..."
tar -C "$ROOT" --numeric-owner --one-file-system \
	--exclude=./dev/* --exclude=./proc/* -cf - . | xz -9 -T0 > "${OUT}.tmp"
mv "${OUT}.tmp" "$OUT"
echo "Done: $(du -h "$OUT" | cut -f1)"
