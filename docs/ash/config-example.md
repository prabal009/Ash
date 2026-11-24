# Configuration Examples (No Secrets)

Ash stores all API keys securely on device using Android Keystore AES-GCM encryption (`ProviderCredentials`). Never commit live keys, `.env` files, or keystores to Git.

## Cloud Providers (Configured in Ash → Models)

| Provider | Base URL | Example Model ID | Notes |
|---|---|---|---|
| **Groq** | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` or `openai/gpt-oss-120b` | Fast spoken chat; `gpt-oss` models auto-configure `reasoning_effort: "low"` and 1024+ token budgets |
| **Google Gemini** | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-2.5-flash` | Uses OpenAI-compatible endpoint with low reasoning effort |
| **Anthropic Claude** | `https://api.anthropic.com` | `claude-opus-5-5` | Uses official Anthropic Java SDK with prompt caching |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-5` | Standard OpenAI chat/tool endpoint |
| **OpenRouter** | `https://openrouter.ai/api/v1` | Any tool-capable model ID | Supports multi-vendor fallback |

## Local / Self-Hosted Providers (No API Key Required)

| Provider | Example Base URL | Example Model ID | Setup Requirement |
|---|---|---|---|
| **LM Studio** | `http://192.168.1.50:1234/v1` | `qwen3-14b` | Enable *Serve on Local Network* in LM Studio |
| **Ollama** | `http://192.168.1.50:11434/v1` | `qwen2.5:14b` | Launch Ollama with `OLLAMA_HOST=0.0.0.0` |
| **Termux / Embedded `llama-server`** | `http://127.0.0.1:8080/v1` | Local GGUF | See [`docs/INSTALL.md`](../INSTALL.md) |

## Optional Release Signing Environment Variables (CI / Local Release Only)

```bash
export JARVIS_KEYSTORE="/path/to/your-release.jks"
export JARVIS_KEYSTORE_PASSWORD="YOUR_STORE_PASSWORD"
export JARVIS_KEY_ALIAS="YOUR_KEY_ALIAS"
export JARVIS_KEY_PASSWORD="YOUR_KEY_PASSWORD"
```
