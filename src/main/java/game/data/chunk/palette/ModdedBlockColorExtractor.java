package game.data.chunk.palette;

import config.Config;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Provides colors for modded blocks not in block-colors.json.
 *
 * Two strategies, tried in order:
 *   1. Extract the average color of the block's top-face texture from the mod JAR inside .minecraft/mods/.
 *   2. Generate a deterministic pastel color from the block name's hash so the block is at least
 *      visible on the map even when no texture can be found.
 *
 * Results are cached so each block name is only resolved once per session.
 */
public class ModdedBlockColorExtractor {

    private static ModdedBlockColorExtractor instance;

    /** Per-block-name color cache (resolved once, reused forever). */
    private final Map<String, SimpleColor> cache = new ConcurrentHashMap<>();

    /**
     * modId → the JAR file that contains assets for that mod.
     * Populated lazily on first use by {@link #ensureJarsScanned()}.
     */
    private volatile Map<String, File> modJars;

    private ModdedBlockColorExtractor() {}

    public static ModdedBlockColorExtractor getInstance() {
        if (instance == null) {
            instance = new ModdedBlockColorExtractor();
        }
        return instance;
    }

    /**
     * Return a color for the given block name.
     * Never returns {@link SimpleColor#BLACK} so the block is always treated as solid/visible.
     */
    public SimpleColor getColor(String blockName) {
        return cache.computeIfAbsent(blockName, this::resolve);
    }

    /** Trigger a background scan of the mods directory so textures are ready quickly. */
    public void preloadAsync() {
        Thread t = new Thread(() -> {
            ensureJarsScanned();
            System.out.println("[ModdedColors] Indexed assets from " + modJars.size() + " mod namespace(s)");
        }, "modded-color-preload");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Resolution logic
    // -------------------------------------------------------------------------

    private SimpleColor resolve(String blockName) {
        SimpleColor fromTexture = tryExtractTexture(blockName);
        if (fromTexture != null) {
            System.out.printf("[ModdedColors] texture  %-50s → rgb(%3d,%3d,%3d)%n",
                blockName, (int)fromTexture.getR(), (int)fromTexture.getG(), (int)fromTexture.getB());
            return fromTexture;
        }
        SimpleColor hash = hashColor(blockName);
        System.out.printf("[ModdedColors] hash     %-50s → rgb(%3d,%3d,%3d)%n",
            blockName, (int)hash.getR(), (int)hash.getG(), (int)hash.getB());
        return hash;
    }

    /**
     * Attempt to read the block's texture from its mod JAR and return the average color
     * of all non-transparent pixels.
     */
    private SimpleColor tryExtractTexture(String blockName) {
        int colon = blockName.indexOf(':');
        if (colon < 0) return null;

        String modId = blockName.substring(0, colon);
        // Strip block state properties like [facing=north]
        String name = blockName.substring(colon + 1);
        if (name.contains("[")) {
            name = name.substring(0, name.indexOf('['));
        }

        ensureJarsScanned();
        File jar = modJars.get(modId);
        if (jar == null) return null;

        // Try texture paths from most to least specific.
        // Older Forge used "textures/blocks/" (plural), newer use "textures/block/".
        String[] candidates = {
            "assets/" + modId + "/textures/block/"  + name + ".png",
            "assets/" + modId + "/textures/block/"  + name + "_top.png",
            "assets/" + modId + "/textures/block/"  + name + "_all.png",
            "assets/" + modId + "/textures/blocks/" + name + ".png",
            "assets/" + modId + "/textures/blocks/" + name + "_top.png",
        };

        for (String path : candidates) {
            SimpleColor c = extractFromJar(jar, path);
            if (c != null) return c;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // JAR scanning
    // -------------------------------------------------------------------------

    private void ensureJarsScanned() {
        if (modJars != null) return;
        synchronized (this) {
            if (modJars != null) return;

            Map<String, File> found = new HashMap<>();
            try {
                Path modsDir = Paths.get(Config.getDefaultMinecraftPath(), "mods");
                if (Files.isDirectory(modsDir)) {
                    Files.list(modsDir)
                         .filter(p -> p.toString().endsWith(".jar"))
                         .forEach(p -> indexJar(p.toFile(), found));
                }
            } catch (Exception e) {
                System.err.println("[ModdedColors] Could not scan mods directory: " + e.getMessage());
            }
            modJars = found;
        }
    }

    /**
     * Discover all asset namespaces inside a JAR and map them to the JAR file.
     * A single JAR can contain assets for multiple mod IDs (e.g. Forge + mod in one file).
     */
    private void indexJar(File jar, Map<String, File> target) {
        try (ZipFile zip = new ZipFile(jar)) {
            zip.stream()
               .map(ZipEntry::getName)
               .filter(name -> name.startsWith("assets/") && name.split("/").length >= 3)
               .map(name -> name.split("/")[1])
               .filter(id -> !id.isEmpty() && !id.equals("minecraft"))
               .distinct()
               .forEach(id -> target.put(id, jar));
        } catch (Exception ignored) {
            // Unreadable / non-mod JAR — skip silently.
        }
    }

    // -------------------------------------------------------------------------
    // Texture color extraction
    // -------------------------------------------------------------------------

    private SimpleColor extractFromJar(File jar, String texturePath) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(texturePath);
            if (entry == null) return null;

            BufferedImage img = ImageIO.read(zip.getInputStream(entry));
            if (img == null) return null;

            return averageColor(img);
        } catch (Exception e) {
            return null;
        }
    }

    /** Average the color of all pixels with alpha > 128. */
    private SimpleColor averageColor(BufferedImage img) {
        long r = 0, g = 0, b = 0;
        int count = 0;

        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                int argb  = img.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha > 128) {
                    r += (argb >> 16) & 0xFF;
                    g += (argb >>  8) & 0xFF;
                    b +=  argb        & 0xFF;
                    count++;
                }
            }
        }

        if (count == 0) return null;
        return new SimpleColor((int)(r / count), (int)(g / count), (int)(b / count));
    }

    // -------------------------------------------------------------------------
    // Hash-based fallback
    // -------------------------------------------------------------------------

    /**
     * Generate a deterministic, mid-range color from the block's name so it is always
     * visible on the map even when no texture file is available.
     * The color is in the 90–217 range per channel (neither too dark nor too bright).
     */
    private static SimpleColor hashColor(String blockName) {
        int h = blockName.hashCode();
        int r = 90 + (h         & 0x7F);   // 90–217
        int g = 90 + ((h >>  8) & 0x7F);   // 90–217
        int b = 90 + ((h >> 16) & 0x7F);   // 90–217
        return new SimpleColor(r, g, b);
    }
}
