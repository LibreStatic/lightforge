# Android UI route and state matrix

The HTML prototype is a visual fixture, not a production route graph. Production uses approximately sixteen functional surfaces. Variants remain reproducible `UiState`, overlays, previews, or goldens.

| Production surface | HTML fixtures | Production form | Required state families |
|---|---|---|---|
| Photos | `01-fotos*.html` | Route | content, density, indexing, empty, error, limited access, recognition consent, selection |
| Collections | `02-colecciones*.html` | Route | device albums, virtual collections, recognition disabled/enabled, moments, trash badge |
| Album | `03-album.html` | Route | all/photos/videos, sort, selection, unavailable volume |
| Search home | `04-buscar-inicio*.html` | Route | local-index readiness, recent queries, people/pets enabled or disabled |
| Search results | `05-buscar-resultados.html` | Route | results, empty, loading, partial index, limited permission, error |
| Selection | `06-seleccion*.html` | Shared mode | explicit/query-all selection, album sheet, loading, empty, error, operation progress |
| Viewer | `07-visor.html` | Route | image/video, chrome, favorite, unsupported, system action pending |
| Details | `08-detalles.html` | Sheet or expanded panel | cheap metadata, lazy EXIF, redacted location, removed media |
| Photo editor | `09-edicion.html` | Route | recipe, preview, history, export, explicit overwrite |
| Moment | `10-momento.html` | Route | suggested, edited, invalid members, save/share |
| Permissions | `11-permisos.html` | Route | explain, requesting, full, selected, denied, permanently denied |
| Global state | `12-estados*.html` | Reusable surface | empty, low storage, pending work, confirmation, recoverable error |
| Settings | `13-ajustes.html` | Route | local processing controls, cache/index maintenance, privacy controls |
| Me | `14-yo.html` | Route | unconfigured, reference selection, indexing, ready, no matches, disabled, error |
| Trash | `15-papelera.html` | Route | content, empty, restore, delete confirmation, real system expiration |
| Video editor | `16-edicion-video.html` | Route | trim/speed/audio/music, preview, export, codec/HDR fallback |

## Navigation rules

- `SelectionMode`, details, add-to-album, and system confirmations are not duplicate global routes.
- Compact uses bottom navigation and sheets; medium/expanded may use rails and side panels.
- Fold, rotation, density, and multi-window changes preserve a `MediaAnchor`, never a list or bitmap.
- External temporary URIs use a dedicated external-viewer destination and are not coerced into a `MediaKey`.

## Fixture registry

Every HTML file remains registered by `DESIGN-MANIFEST.json` and `docs/design/HANDOFF_INVENTORY.json`. Compose screenshot fixtures will use stable names derived from `UI_ROUTE_MAP_GALERIA_ANDROID.md`.
