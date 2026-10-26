# Compile-time dependencies

`build.ps1` reads every jar in `lib/compile/` and `lib/server/`. **Nothing from this directory is packaged into the plugin jar**, and the jars themselves are git-ignored — you need to provide them locally before building.

## `lib/compile/` — API jars

| Artifact | Version used | Coordinates |
| --- | --- | --- |
| paper-api | `1.21.11-R0.1-SNAPSHOT` | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` — from <https://repo.papermc.io/repository/maven-public/> |
| adventure-api | `4.26.1` | `net.kyori:adventure-api:4.26.1` |
| adventure-key | `4.26.1` | `net.kyori:adventure-key:4.26.1` |
| adventure-text-minimessage | `4.26.1` | `net.kyori:adventure-text-minimessage:4.26.1` |
| adventure-text-serializer-plain | `4.26.1` | `net.kyori:adventure-text-serializer-plain:4.26.1` |
| bungeecord-chat | `1.21` | `net.md-5:bungeecord-chat:1.21` |
| netty-transport | `4.2.7.Final` | `io.netty:netty-transport:4.2.7.Final` |
| netty-common | `4.2.7.Final` | `io.netty:netty-common:4.2.7.Final` |
| netty-buffer | `4.2.7.Final` | `io.netty:netty-buffer:4.2.7.Final` |

`bungeecord-chat` and Netty are not optional: without bungeecord-chat the `sendMessage(Component)` / `sendMessage(BaseComponent)` overloads do not resolve, and without Netty the `io.netty.channel.*` types used by the pipeline handlers do not compile.

`paper-api` publishes timestamped snapshot files; the file without a timestamp returns 404. Take a `paper-api-1.21.11-R0.1-<timestamp>-<build>.jar` from the `1.21.11-R0.1-SNAPSHOT/` directory and rename it to `paper-api-1.21.11.jar`.

## `lib/server/` — the server jar

The plugin uses server internals (`net.minecraft.*`, `org.bukkit.craftbukkit.*`), which are not in `paper-api`. You need a Mojang-mapped, patched server jar:

1. Run a Paper 1.21.11 server once (paperclip). It produces `<server>/versions/1.21.11/paper-1.21.11.jar` after the first start;
2. Copy that jar to `lib/server/paper-1.21.11.jar`.

A Leaf 1.21.11 build works the same way and is arguably a better compile target if you plan to run on Leaf: Leaf's paperclip also emits a patched jar under `versions/1.21.11/`.

Note: the `dev-bundle` zip from `repo.papermc.io` is **not** a server jar — its `data/paperclip-mojang.jar` is only the paperclip bootstrap. It is still useful for reading Paper's patched sources when you need to verify a signature.

## Why not commit these?

`paper-1.21.11.jar` alone is ~29 MB of third-party code. Keeping the repository source-only makes it reviewable and keeps redistribution clean.
