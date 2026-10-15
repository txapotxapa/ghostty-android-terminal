# Omarchy-style shell for A-SH. Omarchy's own rc lives under
# ~/.local/share/omarchy on a desktop; this is its terminal subset.
[[ $- != *i* ]] && return

HISTCONTROL=ignoreboth
HISTSIZE=32768
HISTFILESIZE=$HISTSIZE
shopt -s histappend checkwinsize
[[ -r /usr/share/bash-completion/bash_completion ]] && . /usr/share/bash-completion/bash_completion

export EDITOR=nvim SUDO_EDITOR=nvim BAT_THEME=ansi

if command -v eza >/dev/null; then
  alias ls='eza -lh --group-directories-first --icons=auto'
  alias lsa='ls -a'
  alias lt='eza --tree --level=2 --long --icons --git'
  alias lta='lt -a'
fi
alias ff="fzf --preview 'bat --style=numbers --color=always {}'"
alias n='nvim'
alias g='git'
alias gcm='git commit -m'
alias gcam='git commit -a -m'
alias ..='cd ..'
alias ...='cd ../..'
alias update='omarchy-update'

if command -v zoxide >/dev/null; then
  eval "$(zoxide init bash)"
  alias cd='z'
fi
command -v fzf >/dev/null && eval "$(fzf --bash)"
command -v starship >/dev/null && eval "$(starship init bash)"
