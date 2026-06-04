# minecraft-world-downloader

> This is an updated fork of [mircokroon/minecraft-world-downloader](https://github.com/mircokroon/minecraft-world-downloader).
> It adds support for modded servers (NeoForge/Forge/Fabric), voice chat mods (PlasmoVoice, Simple Voice Chat) and includes several fixes for Minecraft 1.21+ servers.

A Minecraft world downloader that works as a proxy server between the client and the server to read & save chunk data. Download multiplayer worlds by connecting to them and walking around. Chunks can be sent back to the client to extend the render distance.

---

### Downloads  <a href="https://github.com/mircokroon/minecraft-world-downloader/releases/latest"><img align="right" src="https://img.shields.io/github/downloads/mircokroon/minecraft-world-downloader/total.svg"></a>
Latest cross-platform jar: [world-downloader.jar](https://github.com/mircokroon/minecraft-world-downloader/releases/latest/download/world-downloader.jar)

---

### Basic usage
Run the jar and enter the server address. Instead of connecting to the server directly, connect to `localhost` in Minecraft.

```
java -jar world-downloader.jar -s your.server.address
```

---

### Features

#### Core (upstream)
- Works with any client — vanilla, Fabric, Forge, NeoForge
- Automatically merges into previous downloads or existing worlds
- Save chests and inventories by opening them
- Extend render distance by sending previously downloaded chunks back to the client
- Overview map of downloaded chunks

#### Added in this fork

**Voice chat support**
- Transparent UDP proxy for **PlasmoVoice** and **Simple Voice Chat**
- Port detected automatically from server plugin channel packets — no configuration needed
- Works with any UDP port (including custom configs like sharing port 25565)
- IPv4 and IPv6 loopback both supported

**Modded block rendering on the map**
- Modded blocks (non-`minecraft:` namespace) are visible on the overview map
- Colors extracted from mod JAR texture files when available, deterministic hash color as fallback
- Fallback chain for vanilla block variants: `_wall`, `_fence_gate`, `_fence` inherit base block color
- 1.20+ blocks added to palette: `short_grass`, `tall_grass`, `fern`, all flowers, `pink_petals`, `wildflowers`, etc.

**Player heads on the map**
- Other players shown as their Minecraft skin head instead of a dot
- Loads directly from Mojang's texture CDN (`textures.minecraft.net`) — fast and no third-party dependency
- **Disk cache** (`cache/heads/`) — second session loads are < 2 ms, no re-download
- Hat/outer layer overlay included
- Falls back to a colored dot while loading

**Bug fixes for 1.21 / NeoForge**
- Microsoft OAuth `invalid_grant` fixed (auth code parsed correctly)
- `CustomPayload` packet (ID `0x19`) added to protocol definitions for 1.20.6 and 1.21
- `PluginChannelHandler` version selection fixed so namespaced channels are processed on 1.13+
- NPE in chunk parsing when modded block states are absent from the global palette
- `ClassCastException` / `StringTag(null)` on block entities and item slots with unknown IDs
- `PlayerEntity.incrementPosition` NPE when relative position arrives before absolute position

---

### Requirements
- Java 21 or higher
- Minecraft 1.12.2 / 1.13.2 / 1.14.4 / 1.15.2 / 1.16.2 / 1.17 / 1.18 / 1.19.3 / 1.20+ / 1.21+

---

### Planned features

- [ ] Block color extraction from the vanilla Minecraft JAR (no more missing palette entries after updates)
- [ ] Automatic head image refresh when a player changes their skin mid-session
- [ ] Configurable UDP proxy port range for voice mods
- [ ] Map legend / layer toggle (show/hide players, markers, etc.)
- [ ] Support for Minecraft 1.21.2+ protocol changes

---

### Building from source

**Windows (with Scoop):**
```powershell
scoop install temurin21-jdk maven
git clone <this-repo>
cd minecraft-world-downloader
mvn package
java -jar target/world-downloader.jar -s your.server.address
```

**Linux (Debian/Ubuntu):**
```bash
sudo apt-get install default-jdk maven
git clone <this-repo>
cd minecraft-world-downloader
mvn package
java -jar target/world-downloader.jar -s your.server.address
```

**Linux (Arch/Manjaro):**
```bash
sudo pacman -S --needed jdk-openjdk maven
```

---

### Command-line options

| Option | Description |
|---|---|
| `-s <address>` | Remote server address |
| `-l <port>` | Local proxy port (default: 25565) |
| `-o <dir>` | World output directory (default: `world`) |
| `-r <distance>` | Extended render distance (chunks) |
| `--no-gui` | Disable the GUI (requires `-s`) |
| `--help` | Show all options |

---

### Contact
For bugs, feature requests or questions, please [open an issue](https://github.com/mircokroon/minecraft-world-downloader/issues/new/choose) on GitHub.
