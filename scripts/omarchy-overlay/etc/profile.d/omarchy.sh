# Omarchy userland housekeeping, run at every login shell.
#
# 1. First login: initialize the pacman keyring. Its master key is generated
#    per device, so it can't ship in the rootfs.
# 2. Automatic updates: at most once a day, a full `pacman -Syu` runs in the
#    background (omarchy-update --auto). Opt out with
#    `touch /etc/omarchy/no-auto-update`.

if [ "$(id -u)" = 0 ] && [ ! -e /etc/pacman.d/gnupg/trustdb.gpg ]; then
	echo "Omarchy: setting up the package keyring (first login only)..."
	pacman-key --init >/dev/null 2>&1 &&
		pacman-key --populate archlinuxarm >/dev/null 2>&1 &&
		echo "Omarchy: ready. Updates install automatically in the background."
fi

if [ -s /var/log/omarchy-update.notice ]; then
	cat /var/log/omarchy-update.notice
	: > /var/log/omarchy-update.notice 2>/dev/null
fi

if [ "$(id -u)" = 0 ] && [ ! -e /etc/omarchy/no-auto-update ] &&
	[ -z "$(find /var/lib/omarchy/last-update -mmin -1440 2>/dev/null)" ]; then
	(setsid omarchy-update --auto >/dev/null 2>&1 &)
fi
