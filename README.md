# BetterAnimation

BetterAnimation plays cube-based Blockbench and Animated Java animations on Bukkit-compatible Minecraft Java Edition servers. It imports `.bbmodel` and `.ajblueprint` files and rebuilds the resource pack when models change.

## Build

Install **JDK 25** and Maven. The build uses Paper API 26.3 and targets Java 21 bytecode.

```sh
mvn package
```

The JAR is generated at `target/BetterAnimation-1.0.0.jar`.

## Install and play

1. Put the JAR in the server's `plugins/` directory and start the server.
2. Add models with embedded PNG textures to `plugins/BetterAnimation/models/`. Use lowercase ASCII letters, digits, underscores or hyphens in the filename.
3. Install the generated `plugins/BetterAnimation/BetterAnimation.zip` in the client, or configure delivery below.
4. Run `/ba list`, then `/ba play <model> <animation>` in game.

### Resource pack delivery

The plugin regenerates its ZIP when files are added, replaced or removed. For automatic delivery, set `pack.public-url` to a URL reachable by players. Serve the generated file yourself, or enable the built-in HTTP server and open its port on your host.

```yaml
pack:
  public-url: 'http://your-server.example:8123/betteranimation.zip'
  http:
    enabled: true
    bind: '0.0.0.0'
    port: 8123
```

Restart the server after changing delivery settings. With an empty URL, use the generated ZIP manually.

## Commands

| Command | Purpose |
| --- | --- |
| `/ba` | Show help. |
| `/ba list` | List models and animations. |
| `/ba play <model> <animation>` | Play an animation in front of the player and return its playback UUID. |
| `/ba stop <uuid>` | Stop a playback and remove its model. |
| `/ba stop all` | Stop all playbacks. |
| `/ba reload` | Rescan the models folder and rebuild the ZIP. |
| `/ba pack` | Request the pack or show the path to the ZIP. |

The full command is `/betteranimation`. The `betteranimation.admin` permission defaults to operators.

## Compatibility

Beta for Minecraft Java Edition **1.21.4–26.3** on Bukkit-compatible servers. The Bukkit code was compiled against Spigot API 1.21.4 and the regional scheduling adapter against Paper API 26.3. Both model import formats were tested. Live playback and pack delivery across the full range of server implementations and versions remain unverified.

Supported features include cube models, embedded PNG textures, nested groups, numeric animation keyframes and loops. Meshes, Molang, sound tracks and command tracks are outside the supported subset.

## License

Source code and documentation are available under the MIT License. The supplied `src/main/resources/pack.png` artwork is excluded from this license grant.
