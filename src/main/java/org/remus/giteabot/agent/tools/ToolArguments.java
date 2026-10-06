package org.remus.giteabot.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.McpTools;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                JsonNode value = root.get(property.getKey());
                if (value == null || value.isMissingNode() || value.isNull()) {
                    continue;
                }
                if (value.isArray()) {
                    value.forEach(node -> args.add(asString(node)));
                } else {
                    args.add(asString(value));
                }
            }
        }
        // Safety net: the object carried fields but none matched a declared property, so this is a
        // tool or a schema the catalog does not know. Pass the raw JSON so the call still carries
        // data, and say so, because the alternative is an executor that reads an argument nobody
        // sent.
        if (args.isEmpty() && !root.isEmpty()) {
            log.warn("Tool '{}' called with fields {} that its schema does not declare — passing "
                            + "raw JSON. Check the tool's schema in ToolCatalog.",
                    toolName, new ArrayList<>(root.propertyNames()));
            args.add(root.toString());
        }
        return args;
    }

    private static String asString(JsonNode node) {
        return node.isString() ? node.asString() : node.toString();
    }
}
