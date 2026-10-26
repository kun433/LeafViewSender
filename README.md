# LeafViewSender

A Leaf / Paper **1.21.11** plugin that only sends what a player can actually see, cutting pointless downstream traffic.

- Version: `1.1.0` (server `1.21.11`)
- Artifact: `1.21.11/dist/LeafViewSender-1.1.0-1.21.11.jar`
- Dependencies: none at runtime (no ProtocolLib / PacketEvents — it compiles directly against the server)

> 中文说明见 [README.zh-CN.md](README.zh-CN.md)

---

## 1. What it does

Downstream waste comes in layers, so the plugin ships three independently switchable paths.

### 1.1 Terrain chunks: send by field of view

Vanilla sends every chunk in a full circle around the player, no matter where the player looks. In practice only a cone in front of the player is visible.

The plugin installs an outbound filter on the player's Netty pipeline (before chunk packets are serialized):

- chunks inside the **horizontal field-of-view cone** (default 140°) or the **near buffer** (default 3 chunks ≈ 48 blocks) pass through;
- everything else is dropped — the client never receives it, so it costs no bandwidth;
- after the player turns or moves, the main thread re-sends the chunks that should be visible but are missing, walking outward from the player. Re-sends go through the native `PlayerChunkSender.sendChunk(...)` path, so anti-xray, heightmaps, block entities and `PlayerChunkLoadEvent` behave exactly as in vanilla.

### 1.2 Orphan updates: stop updating chunks the client never received

This is the free win that comes with 1.1 and is easy to miss: if a chunk was never sent to the client, everything the server keeps broadcasting for it — **block updates, multi-block updates, light updates, block entity data, block destruction animation, block events, non-global level events** — is silently ignored by the client. It is pure waste.

The plugin drops those packets, but only for chunks it explicitly dropped itself, so chunks the player really does have are never affected. On redstone-heavy, mining-heavy or explosion-heavy servers this is a substantial amount of traffic.

### 1.3 Decoration traffic: particles (and optionally sounds)

- **Particles**: vanilla already limits particles to a 32-block radius, but it still sends the ones *behind* you. The plugin applies the view cone as well. `/particle ... force` particles are never dropped, and anything inside the near buffer is kept.
- **Sounds**: **off by default**. Sound is how players hear a creeper or footsteps behind them, so cutting it changes gameplay. Turn it on only when you really need to squeeze bandwidth.

## 2. Why this makes the game *feel* smoother

Downstream bandwidth does not directly set your frame rate, but it sets **queueing latency**: once the link is saturated with pointless traffic, the data that matters (your own actions, entity positions, chunks) queues behind it — which shows up as input lag, jittery entities and stutter.

So there are two strategies:

1. **Send less** — do not send what the client will never render (out-of-view chunks, orphan updates, particles behind you).
2. **Send earlier** — push what the player is *about to* see before they need it. Two mechanisms:
   - **Turn prediction**: based on how fast the player is rotating, chunks that will be visible in 1–3 ticks are pre-sent, so whipping the mouse around no longer reveals a blank band first;
   - **No waiting between batches**: if a re-send pass is cut short by `max-sends-per-tick` (very common after turning or teleporting), the next tick continues immediately instead of waiting for `idle-rescan-ticks` (0.5 s by default).

## 3. What it cannot do (important)

**Vertical culling is impossible — this is a protocol limit, not a missing feature.**

A chunk packet (`ClientboundLevelChunkWithLightPacket`) is sent as a whole column: one chunk = 16×16×world height (384 blocks in the Overworld), all sections at once, and the client parses a fixed number of sections. Sending only a thin vertical slice (e.g. "the 3 blocks under the player's feet") makes the client misparse or disconnect; and if you replace the culled sections with air, the player sees terrain holes appearing out of nowhere when they look up, down or fly.

Vertical coverage is therefore handled by the **near buffer**: `near-radius-chunks: 3` keeps everything within 48 blocks of the player fully sent — i.e. "the 3 blocks under my feet" becomes "the ground and surroundings within 48 blocks are always there".

Two more things to know:

1. **This saves bandwidth, not serialization CPU.** Chunk packets are still built on the server; they are just dropped at the network layer. Leaf's send path (Moonrise → `PlayerChunkSender` → Netty) gives plugins no "ask before sending" hook.
2. **Turning around can still show a brief gap**, mitigated by prediction and the backlog fix. If it bothers you, raise `fov-degrees`, raise `predictive-lookahead-ticks`, or add 1 to `near-radius-chunks`.

## 4. Installation

1. Put `LeafViewSender-1.1.0-1.21.11.jar` into `plugins/` (remove any 1.0.0 build);
2. Start the server (the first run generates `plugins/LeafViewSender/config.yml`);
3. After editing the config, run `/leafviewsender reload`.

## 5. Configuration

### Terrain chunks

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch; `false` lets everything through |
| `fov-degrees` | `140` | Total horizontal FOV, half on each side. `360` disables directional filtering |
| `near-radius-chunks` | `3` | Radius (in chunks) that is always sent in full; `0` disables it (not recommended) |
| `max-sends-per-tick` | `4` | Max chunks re-sent per player per tick, caps the burst when turning/teleporting |
| `max-view-distance` | `12` | Uppermost radius (chunks) used for scanning/re-sending; **must not be smaller than the server `view-distance`** |
| `yaw-threshold-degrees` | `10` | Rescan when the yaw changes by more than this |
| `idle-rescan-ticks` | `10` | Rescan at least every N ticks while standing still (catches chunks that were not loaded yet) |
| `keep-previous-view` | `true` | Keep the previous scan's view as extra slack |
| `predictive-lookahead-ticks` | `3` | Turn-prediction lookahead in ticks; `0` disables (lowest bandwidth) |
| `predictive-lookahead-max-degrees` | `45` | Cap on the predicted lead angle, so a fast flick does not send a huge burst |

### Traffic trimming

| Key | Default | Description |
| --- | --- | --- |
| `traffic.orphan-updates` | `true` | Drop block/light/block-entity updates for chunks the client does not have |
| `traffic.particles.enabled` | `true` | Particle trimming |
| `traffic.particles.max-distance` | `32` | Particles farther than this are not sent |
| `traffic.particles.respect-view-cone` | `true` | Also apply the view cone to particles |
| `traffic.sounds.enabled` | `false` | Sound trimming (consider directional audio before enabling) |
| `traffic.sounds.max-distance` | `64` | Sounds farther than this are not sent |
| `traffic.sounds.respect-view-cone` | `false` | Also apply the view cone to sounds |
| `traffic.wire-stats` | `true` | Measure real wire bytes (for the KB/s column of `/lvw status`) |

### Other

| Key | Default | Description |
| --- | --- | --- |
| `worlds.blacklist` | `[]` | Worlds that are left untouched (case-insensitive) |
| `debug` | `false` | Verbose logging |

### Tuning directions

- **Lowest bandwidth**: `fov-degrees: 110–130`, `near-radius-chunks: 2`, `predictive-lookahead-ticks: 0`, enable `traffic.sounds`
- **Steadiest picture**: `fov-degrees: 180–220`, `near-radius-chunks: 4`, `max-sends-per-tick: 6`, `predictive-lookahead-ticks: 4–5`
- **Fast-moving players (PvP, elytra)**: raise `predictive-lookahead-ticks` and `max-sends-per-tick` first, keep `fov` around 140

## 6. Commands and permissions

| Command | Description |
| --- | --- |
| `/leafviewsender status` (aliases `/lvw`, `/lvs`) | Module states plus per-player counters and **real downstream KB/s** |
| `/leafviewsender hints` | Reads `server.properties` and suggests bandwidth savings |
| `/leafviewsender reload` | Reload the config |

| Permission | Default | Description |
| --- | --- | --- |
| `leafviewsender.admin` | op | Use the commands above |
| `leafviewsender.bypass` | none | Player is never filtered (admins, recording, debugging) |

The KB/s figure in `/lvw status` is derived from real wire bytes **after compression and encryption**, so you can compare before/after directly. `/lvw hints` checks the compression threshold and view distances — e.g. `network-compression-threshold: -1` (compression off) usually saves far more than this plugin does.

## 7. Implementation notes

- **Interception point**: `Connection.channel.pipeline()`, before `packet_handler`, so the handler still sees `Packet` objects. If that name is missing it falls back to just after `encoder`.
- **Measurement point**: a second, outbound-only handler is placed at the very front of the pipeline (`addFirst`). Outbound events travel from tail to head, so it is the last one to see them and therefore counts the real post-compression/encryption bytes.
- **Filtering**: the angle of a chunk's center relative to the player is compared with the player's yaw (Minecraft: yaw 0 = +Z, 90 = −X). Chunks within ±fov/2 pass; the near buffer uses chunk-coordinate distance.
- **Bookkeeping**: the server's own `sentChunks` includes chunks we dropped, so the plugin tracks two sets — `clientChunks` (what the client really has) and `droppedChunks` (what we dropped, the only chunks eligible for orphan-update trimming).
- **Re-send**: `PlayerChunkSender.sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)`, only for chunks where `ServerChunkCache.getChunkNow(...)` returns a loaded chunk; unloaded ones are retried next pass.
- **Threading**: the network thread only reads immutable snapshots (`volatile`) and concurrent sets; re-sends and pipeline injection happen on the main thread / event loop.
- **Startup self-check** in two tiers. If a core member is missing (`PlayerChunkSender.sendChunk`, the `connection`/`channel` fields, `getChunkNow`, `getX/getZ`) the plugin degrades to "no filtering" so terrain keeps working. If an optional module's members are missing, only that module is disabled and logged.

### Two things deliberately not implemented

- **Entity move throttling** (sending move packets less often for distant entities): saves traffic, but makes distant entities stick or teleport and can mislead players in PvP. `entity-tracking-range` in `spigot.yml` is the controllable way to shrink that scope.
- **Flush coalescing** (batching all writes into one flush per tick): Leaf already does this in `optimize-non-flush-packet-sending` (patch `0212`); doing it again in a plugin risks fighting the server for little gain.

### Version coupling

The plugin references server internals (`net.minecraft.*`, `org.bukkit.craftbukkit.*`), so it is version-specific:

- Compiled against the Paper 1.21.11 server jar (`lib/server/paper-1.21.11.jar`, compile-time only, never shipped);
- Targets Leaf 1.21.11. Verified against Leaf sources: `0198-async-chunk-sender` only **adds** a 4-argument overload to `PlayerChunkSender` (the 3-argument one remains), and `ServerChunkCache.getChunkNow`, `Connection.channel`, `ServerCommonPacketListenerImpl.connection`, `ClientboundLevelChunkWithLightPacket#getX/getZ` are untouched by Leaf.

After a server upgrade, a `NoSuchMethodError` / `NoClassDefFoundError` means you need to rebuild against the new server jar.

## 8. Building

```powershell
cd LeafViewSender\1.21.11
powershell -ExecutionPolicy Bypass -File build.ps1
```

- Requires JDK 21 (defaults to `C:\Program Files\Java\jdk-21`, override with `LEAFVIEWSENDER_JDK`);
- Compile-time jars go into `lib/compile` (Paper API, Adventure, Netty) and `lib/server` (Paper server jar) — see [1.21.11/lib/README.md](1.21.11/lib/README.md) for the exact coordinates. They are git-ignored and never packaged;
- Output goes to `dist/`.

## 9. Verification status

- `javac --release 21` compiles with zero errors; the jar contains 12 classes plus `plugin.yml` and `config.yml`.
- Every server-internal member used is checked against the Paper 1.21.11 server jar (`javap`) and Leaf `ver/1.21.11` patch sources.
- 29/29 offline geometry assertions pass (`tests/GeometryTest.java`): yaw 0/90/180/270 map to +Z/−X/−Z/+X, near buffer, `fov=360`, angle wrapping, chunk key packing, and point-based cone/distance checks used for particles and sounds. Run with `powershell -ExecutionPolicy Bypass -File tests\run-geometry-test.ps1`.
- **Not done**: in-game smoke test. When rolling out, give your own account `leafviewsender.bypass`, join with a test account and watch the KB/s and re-send/drop counters in `/lvw status`.
