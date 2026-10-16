#!/bin/bash
# A-SH agent board bridge — runs on the paired computer, inside one SSH
# session the phone keeps open (a-sh-board-link sends this script along with
# the ssh command, so nothing is installed on the computer).
#
# Requests, one per line on stdin:  ID<TAB>xARG<TAB>xARG...
#   each ARG is "x" + base64 (the "x" keeps empty arguments as fields).
# Replies, one per line on stdout:  @@R ID STATUS BASE64(OUTPUT)
#
# Verbs are a fixed list: herdr API calls, plus a few read-only helpers the
# board needs (installed agent CLIs, git repos for the folder picker, an
# agent's session log for its chat and trace views).
export PATH="$HOME/.local/bin:$HOME/.local/share/mise/shims:$HOME/.cargo/bin:$HOME/.bun/bin:$HOME/.opencode/bin:$HOME/.npm-global/bin:/usr/local/bin:$PATH"
export LC_ALL=C.UTF-8 2>/dev/null

b64() { base64 -w0 2>/dev/null || base64 | tr -d '\n'; }

reply() { printf '@@R %s %s %s\n' "$1" "$2" "$(printf '%s' "$3" | b64)"; }

info() {
	printf 'host=%s\n' "$(hostname 2>/dev/null || cat /etc/hostname)"
	printf 'herdr=%s\n' "$(herdr --version 2>/dev/null | awk '{print $2}')"
	printf 'uptime=%s\n' "$(uptime -p 2>/dev/null | sed 's/^up //')"
	printf 'home=%s\n' "$HOME"
}

# Git checkouts up to three levels under $HOME, newest activity first, with
# their current branch: the new-agent folder picker.
repos() {
	find "${A_SH_REPOS_ROOT:-$HOME}" -maxdepth 4 -name .git \
		-not -path '*/node_modules/*' -not -path "${A_SH_REPOS_ROOT:-$HOME}/.*" \
		-not -path '*/.local/*' -not -path '*/.cargo/*' 2>/dev/null |
		while IFS= read -r g; do
			d=${g%/.git}
			b=$(git -C "$d" symbolic-ref --short -q HEAD 2>/dev/null || echo detached)
			printf '%s\t%s\t%s\n' "$(stat -c %Y "$g" 2>/dev/null || echo 0)" "$d" "$b"
		done | sort -rn | head -40 | cut -f2-
}

# The last BYTES (default 400 KB, at most 2 MB) of an agent's session log. Claude Code: the session id, found
# under ~/.claude/projects. omp and others: a path, accepted only under the
# agent's own state directory.
session_log() {
	local kind=$1 value=$2 bytes=${3:-400000} file=
	case $bytes in ''|*[!0-9]*) bytes=400000 ;; esac
	[ "$bytes" -gt 2000000 ] && bytes=2000000
	case $kind in
	id)
		case $value in *[!0-9a-fA-F-]*|'') return 2 ;; esac
		file=$(ls -t "$HOME"/.claude/projects/*/"$value".jsonl 2>/dev/null | head -1)
		;;
	path)
		case $value in
		"$HOME"/.omp/*|"$HOME"/.claude/*|"$HOME"/.codex/*|"$HOME"/.pi/*) file=$value ;;
		*) return 2 ;;
		esac
		case $value in *..*) return 2 ;; esac
		;;
	esac
	[ -n "$file" ] && [ -f "$file" ] || return 1
	tail -c "$bytes" "$file"
}

# --- coding agents -------------------------------------------------------------
# One line per agent the board can install: herdr kind, command, mise package
# (the same packages Omarchy's omarchy-mise-install uses), binary name. The
# recipes live here, on the computer, so the phone only ever names an agent.
CATALOG='claude claude claude claude
codex codex codex codex
gemini gemini gemini gemini
copilot copilot copilot copilot
opencode opencode opencode opencode
pi pi pi pi
omp omp github:can1357/oh-my-pi omp
amp amp amp amp
qwen qwen qwen-code qwen
grok grok npm:@xai-official/grok grok
cursor cursor-agent cursor-agent cursor-agent
hermes hermes special hermes'

# For each agent: kind, state (ready = runs now; firstrun = a mise wrapper
# that downloads on first start; missing), herdr status integration
# (current | outdated | missing | none = herdr has none for it).
agents() {
	local integ
	integ=$(herdr integration status 2>/dev/null)
	printf '%s\n' "$CATALOG" | while read -r kind cmd pkg bin; do
		local p state=missing ig=none
		p=$(command -v "$cmd" 2>/dev/null)
		if [ -n "$p" ]; then
			state=ready
			# Omarchy-style wrapper: ready only once mise has the package.
			if [ -f "$p" ] && [ "$(head -c 2 "$p")" = "#!" ] && grep -q 'mise use -g' "$p" 2>/dev/null; then
				local wpkg
				wpkg=$(sed -n 's/^mise use -g --quiet "\([^"]*\)".*/\1/p' "$p" | head -1)
				mise where "${wpkg:-$pkg}" >/dev/null 2>&1 || state=firstrun
			fi
		fi
		case $(printf '%s\n' "$integ" | awk -v k="$kind:" '$1==k{print $2; exit}') in
		current) ig=current ;; outdated|stale) ig=outdated ;; not) ig=missing ;; '') ig=none ;; *) ig=current ;;
		esac
		printf '%s\t%s\t%s\n' "$kind" "$state" "$ig"
	done
}

# Writes the install script for one catalog agent and prints its path. The
# board runs it in a herdr pane, so the install is visible (and answerable)
# in herdr, and its last line tells the board how it went.
install_prep() {
	local want=$1 line kind cmd pkg bin f
	line=$(printf '%s\n' "$CATALOG" | awk -v k="$want" '$1==k')
	[ -n "$line" ] || return 2
	read -r kind cmd pkg bin <<<"$line"
	mkdir -p "$HOME/.cache/a-sh"
	f="$HOME/.cache/a-sh/install-$kind.sh"
	{
		echo '#!/bin/bash'
		echo "# Written by the A-SH agent board: install $cmd, then herdr's status integration for it."
		printf 'kind=%q cmd=%q pkg=%q bin=%q\n' "$kind" "$cmd" "$pkg" "$bin"
		cat <<'SCRIPT'
export PATH="$HOME/.local/bin:$HOME/.local/share/mise/shims:$PATH"
export MISE_MINIMUM_RELEASE_AGE=0
done_() { echo; echo "A-SH-INSTALL-DONE $1"; exit "$1"; }
echo "== Installing $cmd"
if [ "$pkg" = special ]; then
	# Hermes: Omarchy's installer when there is one, else Nous Research's own.
	if command -v omarchy-install-hermes-cli >/dev/null; then
		omarchy-install-hermes-cli --now || done_ 1
	else
		curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --skip-setup || done_ 1
	fi
else
	if ! command -v mise >/dev/null; then
		echo "== mise isn't installed; installing it from https://mise.run"
		curl -fsSL https://mise.run | sh || done_ 1
	fi
	case $pkg in npm:*)
		command -v npm >/dev/null || { echo "== Node.js is needed for $cmd; adding it with mise"; mise use -g --quiet node@lts || done_ 1; } ;;
	esac
	if command -v omarchy-mise-install >/dev/null; then
		omarchy-mise-install "$pkg" "$cmd" "$bin" || done_ 1
	else
		# The same wrapper omarchy-mise-install writes: ~/.local/bin is on PATH
		# for login shells whether or not mise is activated there.
		mkdir -p "$HOME/.local/bin"
		rm -f "$HOME/.local/bin/$cmd"
		printf '#!/bin/bash\nexport MISE_MINIMUM_RELEASE_AGE=0\nmise use -g --quiet "%s" || exit 1\nexec mise x "%s" -- "%s" "$@"\n' "$pkg" "$pkg" "$bin" >"$HOME/.local/bin/$cmd"
		chmod +x "$HOME/.local/bin/$cmd"
	fi
	echo "== Downloading $pkg"
	mise use -g --quiet "$pkg" || done_ 1
fi
command -v "$cmd" >/dev/null || done_ 1
"$cmd" --version </dev/null 2>&1 | head -1
# herdr's status integration (precise working / needs-you states). herdr
# wants the agent's config folder to exist, which normally appears on first
# launch; create it where herdr expects it.
ipath=$(herdr integration status 2>/dev/null | awk -v k="$kind:" '$1==k{print $NF}' | tr -d '()')
if [ -n "$ipath" ]; then
	echo "== Adding herdr's status integration for $kind"
	mkdir -p "$(dirname "$ipath")"
	herdr integration install "$kind" || echo "(herdr integration: try again after the first start)"
fi
done_ 0
SCRIPT
	} >"$f"
	chmod 700 "$f"
	printf '%s' "$f"
}

# herdr's own per-agent sound overrides ([ui.sound.agents]), so the phone
# mutes what herdr mutes (droid by default).
mutes() {
	local cfg="${XDG_CONFIG_HOME:-$HOME/.config}/herdr/config.toml"
	echo "droid=off"
	[ -f "$cfg" ] || return 0
	awk '/^\[/{sec=$0; next} sec=="[ui.sound.agents]" && /=/{gsub(/[ "]/,""); print}' "$cfg"
	awk '/^\[/{sec=$0; next} sec=="[ui.sound]" && /^ *enabled *=/{gsub(/[ "]/,""); print "*" substr($0, index($0,"="))}' "$cfg"
}

if ! command -v herdr >/dev/null 2>&1; then
	echo "@@E noherdr"
	exit 3
fi
printf '@@READY %s\n' "$(info | b64)"

while IFS= read -r line; do
	id=${line%%$'\t'*}
	rest=${line#*$'\t'}
	[ "$rest" = "$line" ] && rest=
	args=()
	IFS=$'\t' read -ra parts <<<"$rest"
	for p in "${parts[@]}"; do
		args+=("$(printf '%s' "${p#x}" | base64 -d 2>/dev/null)")
	done
	verb=${args[0]-}
	args=("${args[@]:1}")
	case $verb in
	herdr)
		case ${args[0]-} in
		api|agent|pane|tab|workspace|worktree|integration)
			out=$(herdr "${args[@]}" 2>&1 </dev/null)
			rc=$?
			;;
		*)
			out="herdr ${args[0]-}: not allowed from the board"
			rc=126
			;;
		esac
		;;
	which)
		out=$(for a in "${args[@]}"; do command -v "$a" >/dev/null 2>&1 && echo "$a"; done)
		rc=0
		;;
	repos)
		out=$(repos)
		rc=0
		;;
	log)
		out=$(session_log "${args[0]-}" "${args[1]-}" "${args[2]-}")
		rc=$?
		;;
	agents)
		out=$(agents)
		rc=0
		;;
	install-prep)
		out=$(install_prep "${args[0]-}")
		rc=$?
		;;
	mutes)
		out=$(mutes)
		rc=0
		;;
	info)
		out=$(info)
		rc=0
		;;
	ping)
		out=pong
		rc=0
		;;
	*)
		out="unknown verb: $verb"
		rc=127
		;;
	esac
	reply "$id" "$rc" "$out"
done
