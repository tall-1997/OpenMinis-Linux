# Guest address space is the host's decision.
#
# The app does NOT set RLIMIT_AS here (and GuardianScript, which wraps every
# command, never sets it either — a previous soft-probe line there was a
# per-subshell no-op and is gone). HyperOS and the memory-pressure
# policies clamp the hard limit on the new process and that clamp survives
# an app restart, so setting it here is either redundant or a brick:
# raising a hard limit needs privilege, the shell gets EPERM, and an
# `exit 1` here would kill the shell. Never make this fatal.
#
# Address-space pressure is handled by the wall clock, the output rate and
# the host. CPU, process count and file size are set per command by
# GuardianScript, also non-fatally.
