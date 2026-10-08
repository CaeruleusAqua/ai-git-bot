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
 * <p>The order is read from the tool's own schema in {@link ToolCatalog}, which declares its
 * properties in the order its executor takes them — so a property is mapped by being declared,
 * and this class cannot fall behind a tool. Used by {@code CodingAgentStrategy} for direct calls
 * and by the code-execution bridge for nested ones: one mapping, both paths.</p>
 *
 * <p>Falling behind used to be silent. A property this mapping did not know was replaced by the
 * whole argument object as a single string, and for {@code execute-code} that string is valid
 * Python — a dict literal. The program ran, printed nothing and exited 0, so the empty result
 * looked like a program that had chosen to say nothing.</p>
 *
 * <p>A hole is a {@code ""} in the vector, and an executor that reads a position must treat a blank
 * as "not sent". Audited: {@code cat} is the only positional executor with a skipped <em>middle</em>
 * optional property ({@code path, startLine, endLine}) and it does; every other positional schema
 * either has all-required properties or its optionals last, and a trailing hole is dropped (nothing
 * follows it that it could shift). A new tool that reads position {@code n} of a schema carrying an
 * optional property before {@code n} must handle the blank the same way.</p>
 */
@Slf4j
public final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * Positional arguments for {@code toolName}, or an empty list when the call carried none.
     *
     * @param schema the tool's parameter schema from {@link ToolCatalog#schemaOf(String)}, or
     *               {@code null} for a tool the catalog does not declare; without it the argument
     *               object's own shape is all there is to go on.
     */
    public static List<String> toPositional(String toolName, JsonNode root, JsonNode schema) {
        List<String> args = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return args;
        }
        // MCP tools accept arbitrary provider-defined schemas (any field name). Flattening only
        // known property names would silently drop all of them and the MCP server would
        // reject the call with a parameter-validation error. Pass the full args object as a
        // single JSON-encoded arg so McpOrchestrationService.parseArguments can turn it back
        // into a Map.
        if (McpTools.looksLikeMcpTool(toolName)) {
            args.add(root.toString());
            return args;
        }
        // Varargs convention: a top-level "args" array, one element per token. This is also the
        // shape the validation tools are called with — they are declared by configuration, so the
        // catalog holds no schema whose order could be read.
        JsonNode varargs = root.get("args");
        if (varargs != null && varargs.isArray()) {
            varargs.forEach(node -> args.add(asString(node)));
            return args;
        }
        // Declared properties, in declared order. An unknown field is not passed through here: the
        // schema is the contract, and a tool that declares a property gets it.
        JsonNode properties = schema == null ? null : schema.get("properties");
        if (properties != null && properties.isObject()) {
            // A missing property is a hole, not a shorter vector: the executors read by declared
            // index, so cat's {"path": "…", "endLine": 50} would otherwise arrive as startLine=50.
            // Trailing holes are dropped — nothing follows them that they could shift.
            List<String> declared = new ArrayList<>(properties.propertyNames());
            int lastPresent = -1;
            for (int i = 0; i < declared.size(); i++) {
                if (isPresent(root.get(declared.get(i)))) {
                    lastPresent = i;
                }
            }
            for (int i = 0; i <= lastPresent; i++) {
                JsonNode value = root.get(declared.get(i));
                if (!isPresent(value)) {
                    args.add("");
                    continue;
                }
                if (value.isArray()) {
                    value.forEach(node -> args.add(asString(node)));
                } else {
                    args.add(asString(value));
                }
            }
        }
        // Safety net, for tools the catalog declares no schema for (validation tools, unknown
        // names): without a schema there is no property to map, so the raw object is all there is
        // to pass. A tool whose schema IS known must not reach here — no declared property matched,
        // so the call does not carry what its executor reads, and passing the raw object would hand
        // it a value nobody sent. For execute-code that object is a valid Python dict literal: it
        // evaluates, prints nothing and exits 0, so the empty result looked deliberate.
        if (args.isEmpty() && !root.isEmpty() && schema == null) {
            log.warn("Tool '{}' called with fields {} but the catalog declares no schema for it — "
                            + "passing raw JSON. Check the tool's schema in ToolCatalog.",
                    toolName, new ArrayList<>(root.propertyNames()));
            args.add(root.toString());
        }
        return args;
    }

    /** Whether the caller sent a value for a declared property; a JSON null counts as absent. */
    private static boolean isPresent(JsonNode value) {
        return value != null && !value.isMissingNode() && !value.isNull();
    }

    private static String asString(JsonNode node) {
        return node.isString() ? node.asString() : node.toString();
    }
}
