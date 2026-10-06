package org.remus.giteabot.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.McpTools;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the JSON argument object of a native tool call into the positional argument
 * vector the built-in executors declare.
 *
 * <p>Single source of truth on purpose: the flattening order has to match the property
 * order of the schemas in {@link ToolCatalog}, and the executors take positional args
 * while the model produces named ones. A second copy of this mapping would drift. Used by
 * {@code CodingAgentStrategy} for direct calls and by the code-execution bridge for
 * nested ones.</p>
 */
@Slf4j
public final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * Positional arguments for {@code toolName}, or an empty list when the call carried
     * none. An argument object this class does not recognise is passed through as a single
     * JSON blob rather than dropped, so the call still reaches its executor with data.
     */
    public static List<String> toPositional(String toolName, JsonNode root) {
        List<String> args = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return args;
        }
        // MCP tools accept arbitrary provider-defined schemas (any field name). Flattening
        // only known property names would silently drop all of them and the MCP server would
        // reject the call with a parameter-validation error. Pass the full args object as a
        // single JSON-encoded arg so McpOrchestrationService.parseArguments can turn it back
        // into a Map.
        if (McpTools.looksLikeMcpTool(toolName)) {
            args.add(root.toString());
            return args;
        }
        // 1) varargs convention: a top-level "args" array.
        JsonNode varargs = root.get("args");
        if (varargs != null && varargs.isArray()) {
            varargs.forEach(node -> args.add(asString(node)));
            return args;
        }
        // 2) Typed schema (write-file/patch-file/mkdir/delete-file/cat/branch-switcher):
        //    flatten the known property order into positional args.
        addIfPresent(root, "path", args);
        addIfPresent(root, "branch", args);
        addIfPresent(root, "content", args);
        addIfPresent(root, "search", args);
        addIfPresent(root, "replacement", args);
        addIfPresent(root, "startLine", args);
        addIfPresent(root, "endLine", args);
        // 3) Safety net: the whitelist matched nothing but the object did carry fields, so the
        //    caller is using a tool or schema this mapping does not know. Pass the raw JSON so
        //    the call still carries data, and warn so the schema drift gets noticed.
        if (args.isEmpty() && !root.isEmpty()) {
            log.warn("Tool '{}' called with unrecognised arg fields {} — passing raw JSON. "
                            + "Update ToolArguments.toPositional if this tool is meant to be "
                            + "supported natively.",
                    toolName, new ArrayList<>(root.propertyNames()));
            args.add(root.toString());
        }
        return args;
    }

    private static void addIfPresent(JsonNode root, String field, List<String> out) {
        JsonNode v = root.get(field);
        if (v != null && !v.isMissingNode() && !v.isNull()) {
            out.add(asString(v));
        }
    }

    private static String asString(JsonNode node) {
        return node.isString() ? node.asString() : node.toString();
    }
}
