# AI kontrakt – MediaHub

**Zásada: appka nepozná model. Pozná len tieto príkazy.** Kto ich plní, je vymeniteľné (lokálny Qwen, omni model na PC cez Tailscale, cloud). Nový model = nový endpoint, appka sa nemení.

## Príkazy (v0.1)

| Príkaz | Parametre | Vráti |
|---|---|---|
| `list_media` | `type` (audio\|image\|video\|all), `query`, `limit` | `{items:[{id,name,artist,album,duration_ms,size,path}]}` |
| `rename` | `id`, `kind`, `new_name` | `{ok,id,new_name}` |
| `move` | `id`, `kind`, `folder` | `{ok,new_path}` |
| `search` | `text` | `{items:[...]}` |
| `play` | `id`, `kind` | `{ok}` |
| `analyze_audio` | `id` | `{title,artist,genre,language,mood,bpm,tags[]}` |
| `analyze_image` | `id` | `{description,tags[],suggested_name}` |
| `set_tags` | `id`, `kind`, `tags[]` | `{ok}` |

## Dvere

1. **REST** (lokálne, `127.0.0.1:8765`) – pre malý lokálny model aj človeka:
   - `GET /tools` → tento kontrakt ako JSON (model dostane zoznam nástrojov)
   - `POST /<príkaz>` s JSON telom
2. **MCP** (plán, `android-mcp-sdk`) – pre poriadne cloud modely; tie isté príkazy.

## Backendy

Každý backend hovorí OpenAI-compatible `/v1/chat/completions`:

| Backend | URL | Trieda |
|---|---|---|
| Qwen 4B (llama.cpp) | `http://127.0.0.1:8080/v1` | DUMB |
| Omni model na PC (Tailscale) | `http://100.79.170.120:8000/v1` | SMART |
| ľubovoľný cloud | `https://.../v1` + API kľúč | SMART |

Trieda (tier) určuje šablónu promptu: **DUMB** = striktný malý JSON, 3–6 tagov; **SMART** = hlbšia analýza.

## Ako AI „číta" médium (plán)

- **audio:** prepis (SenseVoice/SherpaOnnx, lokálne) + mel-spektrogram → vision model → žáner/jazyk/nálada → tagy + názov → zápis do ID3
- **obrázok:** multimodal model (Qwen3.5 vision) → popis/tagy/návrh názvu
- **medzivýsledky sa ukladajú** – výmena modelu neprepisuje existujúce súbory