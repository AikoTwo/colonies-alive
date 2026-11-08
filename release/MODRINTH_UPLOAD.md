# Checklist de publicação no Modrinth

## Projeto (criar uma vez)
| Campo | Valor |
|---|---|
| Nome | Colonies Alive |
| Slug (URL) | `colonies-alive` |
| Resumo (summary, até 256 caracteres) | A MineColonies addon that brings your colony to life: children play, cry and go to school, adults fall in love, babies are born, and moods show in how citizens walk. |
| Tipo | Mod |
| Categorias | Social, Adventure, Decoration (ou: Social, Game Mechanics) |
| Ambiente | **Client: Required · Server: Required** |
| Licença | GPL-3.0-or-later |
| Descrição | conteúdo de `DESCRIPTION.md` |
| Ícone | (opcional) um rosto de criança da colônia, 512x512 |
| Galeria | screenshots de `run/screenshots/ca_*.png` (poses) e do jogo com shaders |

## Versão 0.1.0
| Campo | Valor |
|---|---|
| Arquivo | `coloniesalive-neoforge-1.21.1-0.1.0.jar` (nesta pasta) |
| Nome da versão | Colonies Alive 0.1.0 |
| Número da versão | `0.1.0` |
| Canal | **Beta** |
| Loaders | NeoForge |
| Versões do jogo | 1.21.1 |
| Changelog | seção 0.1.0 de `CHANGELOG.md` |

## Dependências
O MineColonies **não é publicado no Modrinth para 1.21.1** (só há uma versão antiga de 1.18.2), então não dá para marcá-lo como dependência obrigatória lá. Ele já está como obrigatório dentro do jar (o jogo avisa se faltar) e o link do CurseForge está na descrição.

| Dependência | Tipo | Onde |
|---|---|---|
| MineColonies 1.1.1411+ (1.21.1) | Obrigatória | CurseForge (link na descrição) |
| Structurize, BlockUI, Domum Ornamentum, Multi-Piston | Obrigatórias (do MineColonies) | CurseForge |
| NeoForge 21.1.265+ | Obrigatória | — |
| Iris / Sodium | Opcionais (shaders) | Modrinth: `iris`, `sodium` |

**Créditos de sons**: MCA Reborn (GPL-3.0). Confira a licença na página do projeto antes de publicar e, se quiser, marque o MCA Reborn como "embedded" (embutido) nas dependências.
