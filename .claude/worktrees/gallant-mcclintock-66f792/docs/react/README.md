# Influora React — Motion & 3D Documentation

Complete documentation pack for building **3D motion and animation** in the Influora React SPA (Vite + TypeScript).

Adapted from the `FrontendDesign/` generic kit, customized for **B2B influencer marketing SaaS** — not a marketing brochure site.

---

## Start here — Priya revamp (UI only, APIs unchanged)

| Order | File | Read when |
|-------|------|-----------|
| **★** | [PRIYA-FULL-REVAMP-MASTER-PROMPT.md](./PRIYA-FULL-REVAMP-MASTER-PROMPT.md) | **All 17 skills + master Cursor prompt** |
| **★** | [PRIYA-ALL-PAGES-AND-ELEMENTS.md](./PRIYA-ALL-PAGES-AND-ELEMENTS.md) | **Every page, element, API to preserve** |
| 0 | [PRIYA-INSTALL-AND-RUN-SKILLS.md](./PRIYA-INSTALL-AND-RUN-SKILLS.md) | Install & run — step-by-step commands |
| 0b | [INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md](./INFLUORA-SKILLS-GUIDE-FOR-PRIYA.md) | What the 32 skills mean (theory) |
| 1 | [INFLUORA-FLOW-MASTER.md](./INFLUORA-FLOW-MASTER.md) | Every session — pipeline + file map |
| 2 | [INFLUORA-PROJECT-CONFIG.md](./INFLUORA-PROJECT-CONFIG.md) | Brand colors, routes, stack constants |
| 3 | [INFLUORA-MASTER-PROMPT.md](./INFLUORA-MASTER-PROMPT.md) | Before coding — AI persona + rules |
| 4 | [INFLUORA-PLAN-OF-ACTION.md](./INFLUORA-PLAN-OF-ACTION.md) | Pick next page task |
| 5 | [INFLUORA-3D-MOTION-BLUEPRINT.md](./INFLUORA-3D-MOTION-BLUEPRINT.md) | Motion levels + canvas inventory |
| 6 | [../REACT-MOTION-FLOW.md](../REACT-MOTION-FLOW.md) | React folder order + integration loop |

---

## Prompt files (`prompts/`)

| File | Purpose |
|------|---------|
| [INFLUORA-BRAND-STYLE-RULES.md](./prompts/INFLUORA-BRAND-STYLE-RULES.md) | Visual style for Gemini image/video generation |
| [INFLUORA-IMAGE-PROMPT-TEMPLATE.md](./prompts/INFLUORA-IMAGE-PROMPT-TEMPLATE.md) | Hero, card, empty-state image prompts |
| [INFLUORA-VIDEO-PROMPT-TEMPLATE.md](./prompts/INFLUORA-VIDEO-PROMPT-TEMPLATE.md) | Looping background / showcase video prompts |
| [INFLUORA-MOTION-COMPONENT-PROMPTS.md](./prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md) | Cursor prompts to build motion primitives |
| [INFLUORA-PAGE-SESSION-PROMPTS.md](./prompts/INFLUORA-PAGE-SESSION-PROMPTS.md) | Cursor prompts per route/page |

---

## Related project docs

| Doc | Location |
|-----|----------|
| Project overview | `docs/PROJECT-OVERVIEW.md` |
| UI Cursor prompts (bugs/sprints) | `docs/CURSOR-AI-PROMPTS.md` |
| Creator portfolio spec | `docs/CREATOR-PORTFOLIO-PAGE.md` |
| Brand/creator features | `docs/brand-features.md`, `docs/creator-features.md` |

---

## Quick stack reference

- **Framework:** React 19 · Vite 6 · TypeScript 5.7 · React Router 7
- **Styling:** Tailwind CSS 4 · Lilac Mist palette (`src/app/globals.css`)
- **Motion:** Framer Motion 12 (primary) · GSAP optional Phase 4+
- **3D:** `@react-three/fiber` · `@react-three/drei` · Three.js
- **UI:** Radix / shadcn-style `src/components/ui/`

---

*Pack version 1.0 — June 2026*
