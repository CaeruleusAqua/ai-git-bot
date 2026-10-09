#!/bin/sh
#
# Provisions the execute-code sandbox identities on a host: the accounts the program may run as, the
# pool file the JVM allocates them from, and the sudo rule that lets the service switch to one.
#
#	install-execute-code-sandbox.sh [service-user]
#
# The Dockerfile calls this as root with `appuser`; an operator who wants the same sandbox outside the
# image runs the same script under sudo with their own service user. One script rather than two sets
# of commands, because the two must agree — the account names, the group ids the workspace is handed
# over to, and the rule that authorises the switch are one thing, not three.
#
# Why sudo, and not setpriv/runuser/su: switching to another uid needs privilege, and sudo is the only
# one of them that can be told *which* target users are allowed. A general tool carrying CAP_SETUID —
# a copy of setpriv — hands root to whoever can exec it, one --reuid=0 away, and the service user is
# exactly the user that runs repository-supplied build scripts and plugins. `runuser` refuses
# non-root callers ("may not be used by non-root users") and `su` wants a password; both measured,
# not assumed.
#
# What it creates:
#   * one account per slot: uid = gid = slot, primary group the slot's own group, shell nologin, no
#     home. sudo needs an account to switch to; the JVM needs the group, because handing the
#     throwaway workspace over is a chgrp and chgrp is a membership check.
#   * /etc/execute-code/sandbox-slots — the pool, "name uid gid" per line. The JVM reads it, and its
#     length is how many runs can have an identity of their own at once. Root-owned and not writable
#     by anyone else: a line the service user could add would be an identity it could hand to a
#     program.
#   * /etc/sudoers.d/execute-code — one rule, `service ALL=(the slots) NOPASSWD: ALL`. No (root) and
#     no (ALL) runas, so the rule cannot be turned into root; the commands are unrestricted *for those
#     targets* because running model-written code as them is the entire point. Checked with visudo
#     before it is installed.
#
# Deliberately not created: a capability on any binary. The whole privileged surface is sudo (already
# required by the package, setuid root, and the only thing here that can switch uid) plus that one
# rule. In particular the java binary gets no capabilities: a binary with them runs in
# secure-execution mode, where the loader ignores $ORIGIN and LD_LIBRARY_PATH, so the launcher stops
# finding libjli.so and the service would not start at all.
#
# One pool file is one service. Two JVMs sharing it would each clear the other's running slots when
# they read it — and killing a slot's processes is how the pool is initialised.
#
# Overridable: EXECUTE_CODE_FIRST_SLOT (default 10001), EXECUTE_CODE_SLOT_COUNT (default 16).
set -eu

SERVICE_USER="${1:-appuser}"
FIRST_SLOT="${EXECUTE_CODE_FIRST_SLOT:-10001}"
SLOT_COUNT="${EXECUTE_CODE_SLOT_COUNT:-16}"
POOL=/etc/execute-code/sandbox-slots
SUDOERS=/etc/sudoers.d/execute-code

if ! getent passwd "$SERVICE_USER" >/dev/null; then
	echo "install-execute-code-sandbox: no such service user: $SERVICE_USER" >&2
	exit 1
fi
if ! command -v sudo >/dev/null; then
	echo "install-execute-code-sandbox: sudo is not installed" >&2
	exit 1
fi

install -d -m 0755 /etc/execute-code

: >"$POOL"
slot=0
pool=""
while [ "$slot" -lt "$SLOT_COUNT" ]; do
	id=$((FIRST_SLOT + slot))
	name="execute-code-$id"
	if ! getent group "$id" >/dev/null; then
		groupadd -g "$id" "$name"
	fi
	if ! getent passwd "$id" >/dev/null; then
		useradd -u "$id" -g "$id" -M -d /nonexistent -s /usr/sbin/nologin "$name"
	fi
	echo "$name $id $id" >>"$POOL"
	pool="$pool,$name"
	slot=$((slot + 1))
done
chown root:root "$POOL"
chmod 0644 "$POOL"
# The service user has to be a member of every slot's group to hand a workspace over to it.
usermod -aG "${pool#,}" "$SERVICE_USER"

{
	echo "# Written by install-execute-code-sandbox.sh — see that script for what and why."
	echo "# The service may become a slot identity and nothing else: no (root), no (ALL) runas."
	printf '%s ALL=(%s) NOPASSWD: ALL\n' "$SERVICE_USER" "${pool#,}"
} >"$SUDOERS"
chown root:root "$SUDOERS"
chmod 0440 "$SUDOERS"
visudo -cf "$SUDOERS"

echo "install-execute-code-sandbox: $SLOT_COUNT slots from $FIRST_SLOT for $SERVICE_USER"
echo "  pool:   $POOL"
echo "  rule:   $SUDOERS"
