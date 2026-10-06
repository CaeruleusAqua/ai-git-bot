package org.remus.giteabot.agent.codeexecution;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The authority for one {@code execute_code} invocation: the tools a Python program may
 * name, and the invoker that will run each of them.
 *
 * <p>Built once per execution. A name that is not in here is unreachable rather than
 * rejected at call time, and the set carries no API that takes a server URL, a server alias
 * or a transport — {@link #invoke(String, JsonNode)} is the whole surface.</p>
 *
 * <p>Invariant: every tool returned by {@link #list()} has an invoker, so Python can never
 * see a tool that would then fail to run.</p>
 */
public final class ResolvedToolSet {

    private final List<ResolvedTool> tools;
    private final Map<String, ToolInvoker> invokers;

    private ResolvedToolSet(List<ResolvedTool> tools, Map<String, ToolInvoker> invokers) {
        this.tools = List.copyOf(tools);
        this.invokers = Map.copyOf(invokers);
    }

    /**
     * Pairs descriptors with invokers by name, keeping only those that have both. Order is
     * the order of {@code tools}, so the set reads the same way it is advertised.
     */
    public static ResolvedToolSet of(List<ResolvedTool> tools, Map<String, ToolInvoker> invokers) {
        Map<String, ToolInvoker> available = invokers == null ? Map.of() : invokers;
        List<ResolvedTool> resolvable = new ArrayList<>();
        Map<String, ToolInvoker> paired = new LinkedHashMap<>();
        if (tools != null) {
            for (ResolvedTool tool : tools) {
                ToolInvoker invoker = available.get(tool.name());
                if (invoker != null) {
                    resolvable.add(tool);
                    paired.put(tool.name(), invoker);
                }
            }
        }
        return new ResolvedToolSet(resolvable, paired);
    }

    public static ResolvedToolSet empty() {
        return new ResolvedToolSet(List.of(), Map.of());
    }

    /** As advertised to Python through {@code tools.list()}, in resolution order. */
    public List<ResolvedTool> list() {
        return tools;
    }

    /** Java-side lookup for {@code tools.describe(name)} and for the bridge. */
    public Optional<ResolvedTool> find(String name) {
        return tools.stream().filter(tool -> tool.name().equals(canonical(name))).findFirst();
    }

    public boolean contains(String name) {
        return find(name).isPresent();
    }

    /**
     * The same tools minus the named ones. Applied when the set is built, never at call
     * time — this is what keeps recursion and agent-control tools out of Python's reach.
     */
    public ResolvedToolSet without(String... names) {
        Set<String> drop = new HashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    drop.add(canonical(name));
                }
            }
        }
        if (drop.isEmpty()) {
            return this;
        }
        List<ResolvedTool> kept = tools.stream().filter(tool -> !drop.contains(tool.name())).toList();
        if (kept.size() == tools.size()) {
            return this;
        }
        Map<String, ToolInvoker> keptInvokers = new LinkedHashMap<>();
        for (ResolvedTool tool : kept) {
            keptInvokers.put(tool.name(), invokers.get(tool.name()));
        }
        return new ResolvedToolSet(kept, keptInvokers);
    }

    /**
     * Runs one nested tool call.
     *
     * @throws ToolNotAllowedException when the name is not in this execution's set — the only
     *                                 condition that is exceptional; a tool that runs and fails
     *                                 comes back as an unsuccessful {@link ToolInvocationResult}.
     */
    public ToolInvocationResult invoke(String name, JsonNode arguments) {
        String key = canonical(name);
        ToolInvoker invoker = invokers.get(key);
        if (invoker == null) {
            throw new ToolNotAllowedException(key);
        }
        try {
            return invoker.invoke(arguments);
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ToolInvocationResult.failure(message);
        }
    }

    private static String canonical(String name) {
        return name == null ? "" : name.strip();
    }
}
