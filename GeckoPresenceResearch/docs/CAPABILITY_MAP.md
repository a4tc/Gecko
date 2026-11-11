# Research capability map

PreMiD activity sources and prior browser-adapter experiments are retained for reference only. No PreMiD compatibility runtime is shipped in Gecko Presence 0.6.0.

Current product capabilities and release gates are maintained in `docs/ROADMAP.md` at the repository root. Browser website detection is implemented as an optional Android AccessibilityService that reads known address-bar view IDs and publishes only a normalized hostname; it does not reuse extension code.
