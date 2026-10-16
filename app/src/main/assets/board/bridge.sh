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
export PATH="$HOME/.local/bin:$HOME/.cargo/bin:$HOME/.local/share/mise/shims:/usr/local/bin:$PATH"
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
		api|agent|pane|tab|workspace|worktree)
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
