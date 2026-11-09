# MediaHub

Jedna Android appka (Kotlin + Jetpack Compose + Material 3) na **triedenie a prehrávanie médií** – hudba, fotky, videá – s **vestavěnou AI vrstvou**, ktorú ovládaš cez jednoduché JSON príkazy (lokálny model) alebo cez MCP (poriadne cloud modely).

## Prečo takto

Pevný **príkazový kontrakt** (`docs/ai-contract.md`) → mení sa len AI backend, nie appka. Zajtra vyjde nový malý model? Pridáš endpoint. Chceš omni model na PC cez Tailscale? Pridáš endpoint. Appka ani príkazy sa nemenia.

```
┌── Appka (1 APK) ─────────────────────────────────┐
│ Tab: Hudba   Tab: Galéria   Tab: Súbory          │
│ Univerzálne hľadanie · rename/move/tagy po ruke  │
│ ┌── AI vrstva ────────────────────────────────┐  │
│ │ REST 127.0.0.1:8765  ─► lokálny Qwen 4B     │  │
│ │ MCP (plán)            ─► cloud/PC modely    │  │
│ │ AiGateway (OpenAI-compat /v1/chat/completions│ │
│ │  + capability probe + šablóny dumb/smart)    │ │
│ └─────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

## Stav v0.1 (tento commit)

- [x] Android projekt (Gradle, Compose, Material 3, dynamic colors)
- [x] Shell UI: 3 taby (Hudba / Galéria / Súbory)
- [x] **AI kontrakt** – schéma príkazov (`docs/ai-contract.md`, `ai/AiCommands.kt`)
- [x] **AiGateway** – OpenAI-compatible klien, capability probe (`/v1/models`), šablóny pre hlúpych aj chytrých modelov
- [x] **LocalApiServer** – REST na `127.0.0.1:8765` (`/tools`, `/list_media`, `/rename`, ...)
- [x] **MediaRepository** – reálne čítanie + rename cez MediaStore
- [x] GitHub Actions build → APK artifact

## Ďalej (plán)

1. Prehrávač hudby (Media3/ExoPlayer, fronta, notifikácia) – referencia: Retro Music Player
2. Galéria s náhľadmi + video preview – referencia: Aves Gallery
3. Tagy (ID3 zápis) + AI analýza (`analyze_audio` → názov/žáner/tagy)
4. MCP server (android-mcp-sdk) pre vzdialené modely

## Build

**CI (bez ničoho):** push na `main` → GitHub Actions → artifact `MediaHub-debug.apk`.

**Lokálne:** `gradle assembleDebug` (Gradle 8.9, JDK 17; wrapper zatiaľ nie je súčasťou repo).

## Rýchly test AI vrstvy

```bash
# zoznam dostupných príkazov (aj pre model)
curl -s http://127.0.0.1:8765/tools
# zoznam médií
curl -s -X POST http://127.0.0.1:8765/list_media -d '{"type":"audio","query":""}'
# premenovanie
curl -s -X POST http://127.0.0.1:8765/rename -d '{"id":123,"kind":"audio","new_name":"Interpret - Skladba.mp3"}'
```

Licencia: GPL-3.0 (plán).
