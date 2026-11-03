<p align="center">
  <img src="docs/icon.webp" width="128" alt="Generative logo">
</p>

<h1 align="center">Generative</h1>

<p align="center">
  <strong>Your AI models, tools, and remote machines — together on Android.</strong>
</p>

<p align="center">
  <a href="https://starfall-org.github.io/generative/">
    <img src="https://img.shields.io/badge/Official_Website-Explore-0D9488?style=for-the-badge&amp;logo=googlechrome&amp;logoColor=white" height="40" alt="Visit the official Generative website">
  </a>
  &nbsp;&nbsp;
  <a href="https://discord.gg/fBwePdCxFP">
    <img src="https://img.shields.io/badge/Discord-Join_Community-5865F2?style=for-the-badge&amp;logo=discord&amp;logoColor=white" height="40" alt="Join the Generative Discord community">
  </a>
</p>

<p align="center">
  <a href="https://github.com/starfall-org/generative/releases">Download APK</a> ·
  <a href="https://github.com/starfall-org/generative/issues">Report an issue</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white">
  <img alt="ChatGPT" src="https://img.shields.io/badge/ChatGPT-412991?logo=openai&logoColor=white">
  <img alt="Claude" src="https://img.shields.io/badge/Claude-D97757?logo=claude&logoColor=white">
  <img alt="Gemini" src="https://img.shields.io/badge/Gemini-4285F4?logo=googlegemini&logoColor=white">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-Starfall%20Org%20Copyleft%20v1.1-blue"></a>
</p>

<p align="center" style="white-space: nowrap;">
  <img src="docs/image1.png" width="30%" alt="Generative screenshot 1">
  <img src="docs/image2.png" width="30%" alt="Generative screenshot 2">
  <img src="docs/image3.png" width="30%" alt="Generative screenshot 3">
</p>

Generative is a native Android AI client for your own models, tools, and remote machines. Bring your own API key or supported account; no API credits are included.

## Highlights

- **Models on your terms:** Connect OpenAI-compatible APIs, OpenAI Responses, Anthropic, Gemini, Ollama, or supported account sign-ins (ChatGPT Codex, Claude Code, Antigravity, GitHub Copilot). Configure reasoning and model capabilities.
- **Navigate long conversations:** Use the swipe-open chat minimap, follow streamed reasoning and tool activity, switch message versions, and read Markdown and LaTeX.
- **MCP tools with control:** Connect remote MCP servers over Streamable HTTP or SSE, manage tool access, and choose an MCP server for web search.
- **SSH workspace:** Open remote terminals, forward ports, browse files over SFTP, and connect through SSH jump hosts or WebSocket proxies.
- **Custom media workflows:** Build reusable multi-step request profiles for image, video, and music generation, including custom endpoints and request templates.
- **Reusable AI instructions:** Save prompt instructions, manage agent skills, and import skills from GitHub.
- **Speech beyond system TTS:** Configure voices and preview speech with Android TTS, ElevenLabs, Fish Audio, Cartesia, or Speechify.
- **Personalize the interface:** Use dynamic colors, AMOLED mode, custom palettes, backgrounds, and adjustable component transparency.
- **Keep control of your data:** Store conversations locally, import/export ZIP backups, and protect OAuth credentials with Android Keystore-backed encryption.

Availability of individual model, media, and speech capabilities depends on the connected service.

## Get started

1. [Download the APK](https://github.com/starfall-org/generative/releases) for Android 8.0 or later.
2. Add a provider using your API key or a supported sign-in.
3. Select a model and start chatting.

[User guide](https://starfall-org.github.io/generative/docs.html)

Connect only to trusted providers and MCP servers. Provider usage fees may apply.

<details>
<summary>Add a provider via deep link</summary>

Open the provider editor with prefilled details from a browser or another app:

```text
generative://provider?name={name}&url={url}&key={key}&auth_type={auth_type}&provider_type={provider_type}&icon_url={icon_url}
```

URL-encode values. The link creates a new provider and may expose credentials; share it carefully. The legacy `mg://` scheme is also supported.

</details>

<details>
<summary>Open screens or send a message via deep link</summary>

```text
generative://providers
generative://mcp
generative://mcp?name=Example&url=https%3A%2F%2Fexample.com%2Fmcp
generative://chat?text=Hello%20world
generative://chat?text=Hello%20world&temp=true
```

For MCP links, supply both `name` and `url`; an existing name is updated, otherwise a new connector is created. Updating its endpoint clears the old endpoint's credentials and cached tools. Chat links create a new conversation, immediately send `text`, and default to a persistent conversation unless `temp=true`. URL-encode query parameter values, especially URLs containing `?` or `&`. Only open import/chat links you trust.

</details>

## Build from source

Requires JDK 17 and Android SDK (`sdk.dir` in `local.properties`):

```bash
./gradlew :app:assembleDebug
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and testing guidance.

### Localization

- **Tier 1:** English (source)
- **Tier 2:** Vietnamese
- **Tier 3:** Japanese, Chinese (Simplified), Chinese (Traditional), Korean, German, Russian, French, Spanish, Italian, Indonesian, Thai, Portuguese (Brazil)

> Contact us if you would like to contribute the translations.

## License

Licensed under the [Starfall Org Contributor Commercial Copyleft License v1.1](LICENSE). Non-commercial use is required unless permitted by the license; app-store distribution requires prior written permission from Starfall Org.
