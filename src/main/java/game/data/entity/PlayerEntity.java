package game.data.entity;

import com.google.gson.Gson;
import game.data.coordinates.CoordinateDouble3D;
import javafx.scene.image.Image;
import kong.unirest.Unirest;
import packets.DataTypeProvider;
import packets.UUID;
import util.PathUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks a player entity seen in the world.
 *
 * Head loading strategy (fastest path first):
 *  1. Memory cache        — sub-millisecond, same session
 *  2. Disk cache          — ~1 ms, across sessions  (cache/heads/{uuid}.png = 64×64 skin)
 *  3. Mojang profile API  — async, ~100-200 ms, fetches name + skin URL
 *     └─ textures.minecraft.net CDN — async, ~50-150 ms, downloads raw skin PNG
 */
public class PlayerEntity implements IMovableEntity {

    private static final String API_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** session-wide name cache — survives reconnects within one run */
    static final Map<UUID, String> knownNames = new ConcurrentHashMap<>();
    /** session-wide skin cache — key: hyphenated UUID, value: 64×64 skin Image */
    private static final Map<String, Image> skinCache = new ConcurrentHashMap<>();

    private CoordinateDouble3D pos;
    private final UUID uuid;
    private String name;
    private boolean profileRequested = false;

    PlayerEntity(UUID uuid) {
        this.uuid = uuid;
        // Kick off loading eagerly so the image is ready before the player appears on the map.
        ensureProfileLoaded();
    }

    public static PlayerEntity parse(DataTypeProvider provider) {
        PlayerEntity ent = new PlayerEntity(provider.readUUID());
        ent.readPosition(provider);
        return ent;
    }

    // -------------------------------------------------------------------------
    // Skin / head image
    // -------------------------------------------------------------------------

    /**
     * Returns the player's 64×64 skin texture, or {@code null} while loading.
     * Use {@link javafx.scene.canvas.GraphicsContext#drawImage(Image, double, double, double, double, double, double, double, double)}
     * with source region (8, 8, 8, 8) for the face and (40, 8, 8, 8) for the hat overlay.
     */
    public Image getSkinImage() {
        ensureProfileLoaded();
        return skinCache.get(toHyphenatedUUID());
    }

    private void ensureProfileLoaded() {
        if (profileRequested) return;
        profileRequested = true;

        String uuidStr = toHyphenatedUUID();

        // 1. Memory cache — already have the skin
        if (skinCache.containsKey(uuidStr)) return;

        // 2. Disk cache — skin PNG already downloaded
        Path skinFile = PathUtils.toPath("cache", "heads", uuidStr + ".png");
        if (skinFile.toFile().exists()) {
            // Load synchronously from disk (small file, ~3-10 KB, takes < 2 ms)
            Image img = new Image(skinFile.toUri().toString());
            if (!img.isError()) {
                skinCache.put(uuidStr, img);
                // Name might still be missing; fetch profile for name only
                if (!knownNames.containsKey(uuid)) {
                    fetchProfileForName(uuidStr);
                } else {
                    this.name = knownNames.get(uuid);
                }
                return;
            }
        }

        // 3. Full profile fetch: name + skin URL → skin download
        fetchFullProfile(uuidStr, skinFile);
    }

    /** Only fetches name (skin already on disk). */
    private void fetchProfileForName(String uuidStr) {
        Unirest.get(API_PROFILE + uuid.toString()).asStringAsync(r -> {
            if (!r.isSuccess()) return;
            ProfileResponse p = new Gson().fromJson(r.getBody(), ProfileResponse.class);
            if (p.name != null) {
                knownNames.put(uuid, p.name);
                this.name = p.name;
            }
        });
    }

    /** Fetches name + skin URL, then downloads the skin texture. */
    private void fetchFullProfile(String uuidStr, Path skinFile) {
        Unirest.get(API_PROFILE + uuid.toString()).asStringAsync(r -> {
            if (!r.isSuccess()) return;

            ProfileResponse p = new Gson().fromJson(r.getBody(), ProfileResponse.class);
            if (p.name != null) {
                knownNames.put(uuid, p.name);
                this.name = p.name;
            }

            String skinUrl = p.getSkinUrl();
            if (skinUrl == null) return;

            // Download skin from textures.minecraft.net (CDN-backed, very fast)
            Unirest.get(skinUrl).asBytesAsync(sr -> {
                if (!sr.isSuccess()) return;
                try {
                    Files.createDirectories(skinFile.getParent());
                    Files.write(skinFile, sr.getBody());
                    Image img = new Image(skinFile.toUri().toString());
                    if (!img.isError()) {
                        skinCache.put(uuidStr, img);
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    /** Formats UUID as xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx */
    private String toHyphenatedUUID() {
        String s = uuid.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
             + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20);
    }

    // -------------------------------------------------------------------------
    // Profile JSON parsing
    // -------------------------------------------------------------------------

    static class ProfileResponse {
        String name;
        Property[] properties;

        String getSkinUrl() {
            if (properties == null) return null;
            for (Property p : properties) {
                if (!"textures".equals(p.name) || p.value == null) continue;
                try {
                    String json = new String(Base64.getDecoder().decode(p.value));
                    TextureData d = new Gson().fromJson(json, TextureData.class);
                    if (d.textures != null && d.textures.SKIN != null) return d.textures.SKIN.url;
                } catch (Exception ignored) {}
            }
            return null;
        }

        static class Property { String name, value; }
        static class TextureData {
            Textures textures;
            static class Textures {
                Skin SKIN;
                static class Skin { String url; }
            }
        }
    }

    // -------------------------------------------------------------------------
    // IMovableEntity
    // -------------------------------------------------------------------------

    @Override
    public void incrementPosition(int dx, int dy, int dz) {
        if (pos == null) return;
        pos.increment(
            dx / Entity.CHANGE_MULTIPLIER,
            dy / Entity.CHANGE_MULTIPLIER,
            dz / Entity.CHANGE_MULTIPLIER
        );
    }

    @Override
    public void readPosition(DataTypeProvider provider) {
        this.pos = provider.readDoubleCoordinates();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public CoordinateDouble3D getPosition() { return pos; }

    public String getName() {
        if (name == null && knownNames.containsKey(uuid)) {
            name = knownNames.get(uuid);
        }
        return name;
    }

    public UUID getUUID() { return uuid; }

    @Override
    public String toString() {
        return "PlayerEntity{uuid=" + uuid + '}';
    }
}
