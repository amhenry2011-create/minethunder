# NeoForge migration

## Result

MineThunder has been migrated from ForgeGradle / Forge 52.1.20 to:

- Minecraft 1.21.1
- NeoForge 21.1.236
- ModDevGradle 2.0.148
- Java 21
- ModDevGradle `neoForge` configuration
- `META-INF/neoforge.mods.toml`

The Forge project backup is:

`/Users/henry/code/minethunder-forge-backup-20261019`

## Network workaround

`maven.neoforged.net` is unreachable from this network. The build currently uses:

- CreeperHost Maven mirror: `https://maven.creeperhost.net/`
- A repository rewrite in `build.gradle` for ModDevGradle-added NeoForged repositories.
- `local-maven` containing a generated `net.neoforged:minecraft-dependencies:1.21.1` POM.
- The local Minecraft libraries directory:
  `/Users/henry/Desktop/minecraft/libraries`

This is machine/network-specific. If the build is moved to another machine, either remove the workaround and use normal NeoForged Maven, or regenerate the local POM from the installed version JSON.

## Code migration done

- Mod entry point: `ModContainer.getEventBus()`.
- Registration: `DeferredRegister` / `DeferredHolder` / `Registries`.
- Events: NeoForge packages; `ClientTickEvent.Post`.
- HUD: `RegisterGuiLayersEvent` + `LayeredDraw`.
- Networking: `CustomPacketPayload` + `PayloadRegistrar` + `IPayloadContext`.
- Metadata: `neoforge.mods.toml`.
- Forge imports removed from `src/main/java`.

## Verification

- `./gradlew build --no-daemon --console=plain` succeeded.
- Jar copied to:
  `/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge_21.1.236/mods/minethunder-0.1.0.jar`
- Game not launched by the agent.
- User should verify radar HUD, keybinds, command `/minethunder testtarget`, networking and basic missile launch on the NeoForge instance.

## Pending

- Base NeoForge runtime/crash verification by user.
- Then implement firework crossbow + full guidance + ARH launch line/inertial display.
