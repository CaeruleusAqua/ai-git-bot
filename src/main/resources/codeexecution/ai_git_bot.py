"""Runtime bridge for the execute-code tool.

A program gets its tool surface from here:

    import ai_git_bot

    names = ai_git_bot.tools.list()
    schema = ai_git_bot.tools.describe("rg")
    result = ai_git_bot.tools.call("rg", {"args": ["TODO"]})
    print(result["output"])

Every call becomes one JSON line on the AF_UNIX socket named by $AI_GIT_BOT_BRIDGE, and the
reply is decoded back into Python objects. An ordinary refusal or a failed tool comes back as a
result with ``success=False`` and an ``error`` string; ToolError is for a broken bridge itself.

stdout is the program's own output channel and carries no protocol frames — the socket does.
"""

import json
import os
import socket

BRIDGE_ENV = "AI_GIT_BOT_BRIDGE"


class ToolError(Exception):
    """Raised when the bridge itself fails — a closed socket, an unreadable reply."""

    def __init__(self, code, message):
        super().__init__("%s: %s" % (code, message))
        self.code = code
        self.message = message


class _Bridge:
    """One connection, one outstanding request: the JVM side serves sequentially."""

    def __init__(self):
        path = os.environ.get(BRIDGE_ENV)
        if not path:
            raise RuntimeError(
                "%s is not set; this module only works inside the execute-code tool" % BRIDGE_ENV)
        self._socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._socket.connect(path)
        self._reader = self._socket.makefile("rb")
        self._next_id = 0

    def request(self, payload):
        self._next_id += 1
        payload["id"] = str(self._next_id)
        self._socket.sendall((json.dumps(payload) + "\n").encode("utf-8"))
        line = self._reader.readline()
        if not line:
            raise ToolError("BRIDGE_CLOSED", "the agent closed the tool bridge")
        reply = json.loads(line.decode("utf-8"))
        if reply.get("type") == "tool_error":
            error = reply.get("error") or {}
            raise ToolError(error.get("code", "TOOL_ERROR"),
                            error.get("message", "tool call failed"))
        return reply.get("result")


_bridge = None


def _connection():
    global _bridge
    if _bridge is None:
        _bridge = _Bridge()
    return _bridge


class _Tools:
    """The tools the agent exposes to this program."""

    def list(self):
        """Names, sources and one-line descriptions of the tools you may call."""
        return _connection().request({"type": "tools_list"}).get("tools", [])

    def describe(self, name):
        """Name, description and full JSON input schema of one tool."""
        return _connection().request({"type": "tools_describe", "name": name})

    def call(self, name, arguments=None):
        """Run one tool. Returns {"success", "exitCode"?, "output", "error"?}.

        A refusal (the tool is not on this surface) and a tool that failed both come back as a
        result with ``success=False``. ToolError is raised when the sandbox itself would not relay
        the call: the tool-call budget is used up, or the request is not one it understands.
        """
        return _connection().request(
            {"type": "tool_call", "name": name, "arguments": arguments or {}})


tools = _Tools()
