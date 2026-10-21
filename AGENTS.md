# MineThunder agent instructions

For every task in this repository:

1. Load the `minethunder-context` skill from `.opencode/skills/minethunder-context/SKILL.md`.
2. Read `LOCK_LOGIC.md` for the original Demo lock/TWS/ACM/seeker behavior.
3. Read `TWS.md` for the current implemented behavior.
4. Read `HANDOFF.md`, `MISSILE_LOCK_LOGIC.md`, `SEEKER_HUD.md`, `HEAT_SOURCES.md`, and `OCCLUSION.md` for the latest state, visual/IR, heat, and LOS rules.
5. Inspect current source before editing; do not rely on old assumptions.
6. Whenever the user specifies a new visual style, ask whether it belongs on the radar panel or the main screen unless they already said so.
7. After completing any version change, read and follow `.opencode/skills/minethunder-auto-release/SKILL.md`: build with `./gradlew build --no-daemon --console=plain`, copy the jar to the NeoForge mods directory, commit, and push to `origin main`. Do not launch the game unless explicitly asked.
