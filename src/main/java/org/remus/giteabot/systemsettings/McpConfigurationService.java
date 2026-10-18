package org.remus.giteabot.systemsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class McpConfigurationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final McpConfigurationRepository mcpConfigurationRepository;
    private final BotRepository botRepository;
    private final EncryptionService encryptionService;
    private final McpSelectedToolRepository mcpSelectedToolRepository;

    @Transactional(readOnly = true)
    public List<McpConfiguration> findAll() {
        return mcpConfigurationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<McpConfiguration> findById(Long id) {
        return mcpConfigurationRepository.findById(id);
    }

    /**
     * Validates and persists the configuration. The JSON content may contain
     * MCP server credentials (authorization tokens, custom headers), so it is
     * stored encrypted via {@link EncryptionService}. The incoming value is
     * always plaintext (e.g. from the admin form).
     */
    public McpConfiguration save(McpConfiguration mcpConfiguration) {
        if (mcpConfiguration.getName() == null || mcpConfiguration.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        mcpConfiguration.setName(mcpConfiguration.getName().trim());
        if (mcpConfiguration.getJsonContent() == null || mcpConfiguration.getJsonContent().isBlank()) {
            throw new IllegalArgumentException("MCP JSON content is required");
        }
        validateJson(mcpConfiguration.getJsonContent());
        boolean duplicateName = mcpConfiguration.getId() == null
                ? mcpConfigurationRepository.existsByName(mcpConfiguration.getName())
                : mcpConfigurationRepository.existsByNameAndIdNot(mcpConfiguration.getName(), mcpConfiguration.getId());
        if (duplicateName) {
            throw new IllegalArgumentException("An MCP configuration with this name already exists");
        }
        if (mcpConfiguration.getId() != null) {
            String hash = configurationHash(mcpConfiguration);
            mcpSelectedToolRepository.findByMcpConfigurationId(mcpConfiguration.getId()).stream()
                    .filter(tool -> !hash.equals(tool.getReviewApprovalConfigurationHash()))
                    .forEach(McpSelectedTool::clearReviewReadOnlyApproval);
        }
        McpConfiguration stored = mcpConfiguration.getId() == null ? new McpConfiguration()
                : mcpConfigurationRepository.findById(mcpConfiguration.getId())
                        .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
        // Never bind selections/approvals from the configuration form, or replace its orphan-removal collection.
        stored.setName(mcpConfiguration.getName());
        stored.setJsonContent(encryptionService.encrypt(mcpConfiguration.getJsonContent()));
        return mcpConfigurationRepository.save(stored);
    }

    /** Hash of the complete decrypted configured JSON, never resolved runtime secrets. */
    @Transactional(readOnly = true)
    public String configurationHash(McpConfiguration configuration) {
        try {
            return approvalHash(OBJECT_MAPPER.readValue(getDecryptedJsonContent(configuration), Object.class));
        } catch (Exception e) {
            // Parser exceptions can quote credential-bearing JSON.
            throw new IllegalArgumentException("Cannot bind approval to invalid MCP configuration");
        }
    }

    static String approvalHash(Object value) {
        try {
            Object jsonValue = OBJECT_MAPPER.convertValue(value, Object.class);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(OBJECT_MAPPER.writeValueAsBytes(jsonValue)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot hash MCP approval evidence");
        }
    }

    /** Decrypted JSON content of the configuration (may contain credentials). */
    @Transactional(readOnly = true)
    public String getDecryptedJsonContent(McpConfiguration configuration) {
        String json = configuration.getJsonContent();
        return (json == null || json.isBlank()) ? json : encryptionService.decrypt(json);
    }

    /**
     * Returns a transient copy of the configuration with decrypted JSON
     * content, for rendering in the admin edit form. Never persisted.
     */
    @Transactional(readOnly = true)
    public McpConfiguration decryptedView(McpConfiguration configuration) {
        McpConfiguration copy = new McpConfiguration();
        copy.setId(configuration.getId());
        copy.setName(configuration.getName());
        copy.setJsonContent(getDecryptedJsonContent(configuration));
        return copy;
    }

    public void deleteById(Long id) {
        McpConfiguration mcpConfiguration = mcpConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
        List<Bot> bots = botRepository.findByMcpConfigurationId(id);
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).toList().toString();
            throw new IllegalStateException("MCP configuration is used by bot(s): " + botNames);
        }
        mcpConfigurationRepository.delete(mcpConfiguration);
    }

    void validateJson(String jsonContent) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP configuration must be valid JSON", e);
        }
        if (containsStdioTransport(root)) {
            throw new IllegalArgumentException("stdio MCP transport is not supported");
        }
        if (!containsRemoteEndpoint(root)) {
            throw new IllegalArgumentException("MCP configuration must contain a remote HTTP, HTTPS, WS, WSS, or SSE endpoint");
        }
    }

    private boolean containsStdioTransport(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (("transport".equalsIgnoreCase(field.getKey()) || "type".equalsIgnoreCase(field.getKey()))
                        && field.getValue().isTextual()
                        && "stdio".equalsIgnoreCase(field.getValue().asText())) {
                    return true;
                }
                if ("command".equalsIgnoreCase(field.getKey())) {
                    return true;
                }
                if (containsStdioTransport(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsStdioTransport(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsRemoteEndpoint(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            String value = node.asText().toLowerCase();
            return value.startsWith("http://")
                    || value.startsWith("https://")
                    || value.startsWith("ws://")
                    || value.startsWith("wss://")
                    || value.startsWith("sse://");
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (containsRemoteEndpoint(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsRemoteEndpoint(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
