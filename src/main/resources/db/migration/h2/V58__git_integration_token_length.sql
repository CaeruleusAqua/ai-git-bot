-- bitbucket uses 190 char length tokens, which requires more than the initial schemas 255-column length when encryption is activated.
ALTER TABLE git_integrations ALTER COLUMN token SET DATA TYPE VARCHAR(1000);
