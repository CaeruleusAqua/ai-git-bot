package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AiIntegrationService {

    private final AiIntegrationRepository aiIntegrationRepository;
    private final EncryptionService encryptionService;
    private final AiIntegrationConcurrencyLimiter concurrencyLimiter;

    @Transactional(readOnly = true)
    public List<AiIntegration> findAll() {
        return aiIntegrationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<AiIntegration> findById(Long id) {
        return aiIntegrationRepository.findById(id);
    }

    public AiIntegration save(AiIntegration integration) {
        return save(integration, false);
    }

    /**
     * Saves an AI integration, resolving the API key from the form input.
     *
     * <p>The key field is a one-way write: the stored value is never echoed
     * back into the form. A blank field therefore means "keep the stored
     * value", while {@code clearApiKey} requests explicit removal (the Clear
     * button in the UI). Re-encrypting the kept ciphertext would corrupt the
     * key, so only freshly provided plaintext keys are encrypted.</p>
     */
    public AiIntegration save(AiIntegration integration, boolean clearApiKey) {
        String apiKey = integration.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            integration.setApiKey(encryptionService.encrypt(apiKey));
        } else if (clearApiKey) {
            integration.setApiKey(null);
        } else if (integration.getId() != null) {
            aiIntegrationRepository.findById(integration.getId())
                    .ifPresent(existing -> integration.setApiKey(existing.getApiKey()));
        }
        return aiIntegrationRepository.save(integration);
    }

    public void deleteById(Long id) {
        aiIntegrationRepository.deleteById(id);
        concurrencyLimiter.forget(id);
    }

    public String decryptApiKey(AiIntegration integration) {
        String apiKey = integration.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(apiKey);
    }
}
