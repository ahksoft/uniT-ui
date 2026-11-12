# Adbify interactive Bash defaults
case "$-" in
  *i*) ;;
  *) return ;;
esac
export HISTCONTROL=ignoreboth
export HISTSIZE=2000
export HISTFILESIZE=4000
export PS1='\u@adbify:\w\$ '
