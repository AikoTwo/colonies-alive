package com.aiko.coloniesalive.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the eyes on the face of a humanoid skin and works out which pixels to paint to close them.
 * Plain Java on purpose (no Minecraft classes) so it can be tried out on the texture files outside the game.
 *
 * <p>Works in "face pixels": the 8x8 front face at (8,8) of a skin whose height is 64 base pixels, scaled up for HD skins.
 * The skin tone is the most common colour of the lower face; the eye row is the row between 2 and 5 that has the most
 * pixels that clearly differ from the skin tone (but isn't fully covered, which would be hair or a mask).
 */
public final class EyeFinder
{
    public interface Pixels
    {
        int width();

        int height();

        /** Colour as 0xAARRGGBB. */
        int argb(int x, int y);
    }

    /** One face pixel to paint: covers a {@code size}x{@code size} block at (x,y) in image coordinates. */
    public record Lid(int x, int y, int size, int argb) {}

    private static final int DIFFERENT = 60;

    private EyeFinder() {}

    public static List<Lid> find(final Pixels img)
    {
        final int scale = Math.max(1, img.height() / 64);
        final List<Lid> lids = new ArrayList<>();
        if (img.width() < 16 * scale || img.height() < 16 * scale)
        {
            return lids;
        }

        final int skin = skinTone(img, scale);
        if (skin == 0)
        {
            return lids;
        }

        // Most MineColonies faces have white eyeballs: rows with white pixels are the eye rows
        final List<Integer> whiteRows = new ArrayList<>();
        for (int fy = 2; fy <= 6; fy++)
        {
            for (int fx = 1; fx <= 6; fx++)
            {
                if (isWhite(face(img, scale, fx, fy)))
                {
                    whiteRows.add(fy);
                    break;
                }
            }
        }
        if (!whiteRows.isEmpty() && whiteRows.size() <= 2 && whiteRows.get(whiteRows.size() - 1) - whiteRows.get(0) <= 1)
        {
            final int lash = shade(skin, 0.55f);
            final boolean tall = whiteRows.size() == 2;
            for (final int fy : whiteRows)
            {
                final boolean bottom = fy == whiteRows.get(whiteRows.size() - 1);
                for (final int fx : differentColumns(img, scale, fy, skin))
                {
                    if (fx < 1 || fx > 6)
                    {
                        continue;
                    }
                    // only the eyes: the white or right next to it (the iris). Face paint between the eyes (druids)
                    // used to get a lid too, a band across the whole face when blinking
                    if (!isWhite(face(img, scale, fx, fy)) && !isWhite(face(img, scale, fx - 1, fy)) && !isWhite(face(img, scale, fx + 1, fy)))
                    {
                        continue;
                    }
                    final int color = !tall ? mix(skin, lash, 0.5f) : (bottom ? lash : shade(skin, 0.95f));
                    lids.add(lid(fx, fy, scale, color));
                }
            }
            // More than two 2x2 eyes worth of pixels: probably teeth or a mask, better leave the face alone
            return lids.size() <= 8 ? lids : new ArrayList<>();
        }

        int eyeRow = -1;
        int bestScore = -1;
        for (int fy = 2; fy <= 5; fy++)
        {
            final int count = differentColumns(img, scale, fy, skin).size();
            if (count < 2 || count > 6)
            {
                continue;
            }
            // prefer rows near the usual eye height (row 4)
            final int score = count * 10 - Math.abs(fy - 4);
            if (score > bestScore)
            {
                bestScore = score;
                eyeRow = fy;
            }
        }
        if (eyeRow < 0)
        {
            return lids;
        }

        final List<Integer> eyeColumns = differentColumns(img, scale, eyeRow, skin);

        // Two pixel tall eyes: the row above has differing pixels in the same columns
        boolean tall = false;
        if (eyeRow > 1)
        {
            final List<Integer> above = differentColumns(img, scale, eyeRow - 1, skin);
            int overlap = 0;
            for (final int column : above)
            {
                if (eyeColumns.contains(column))
                {
                    overlap++;
                }
            }
            tall = overlap >= 2 && above.size() <= 6;
        }

        final int lash = shade(skin, 0.55f);
        for (final int fx : eyeColumns)
        {
            if (tall)
            {
                lids.add(lid(fx, eyeRow - 1, scale, shade(skin, 0.95f)));
                lids.add(lid(fx, eyeRow, scale, lash));
            }
            else
            {
                lids.add(lid(fx, eyeRow, scale, mix(skin, lash, 0.5f)));
            }
        }
        return lids;
    }

    /**
     * The face row (0..7) of the mouth: among rows 5 to 7, the one with the most pixels in the middle of the face (columns
     * 2 to 5) that differ from the skin tone. Row 6 (the usual one) if nothing stands out.
     */
    public static int mouthRow(final Pixels img)
    {
        final int scale = Math.max(1, img.height() / 64);
        if (img.width() < 16 * scale || img.height() < 16 * scale)
        {
            return 6;
        }
        final int skin = skinTone(img, scale);
        if (skin == 0)
        {
            return 6;
        }
        int best = 6;
        int bestScore = 0;
        for (final int fy : new int[] {6, 7, 5})
        {
            int score = 0;
            for (int fx = 2; fx <= 5; fx++)
            {
                final int color = face(img, scale, fx, fy);
                if ((color >>> 24) > 128 && distance(color, skin) > 40)
                {
                    score++;
                }
            }
            // a mouth is a few pixels wide, never the whole row (that would be a beard, a scarf or the chin shading)
            final boolean wholeRow = distance(face(img, scale, 0, fy), skin) > 40 && distance(face(img, scale, 7, fy), skin) > 40;
            if (score > bestScore && !wholeRow)
            {
                best = fy;
                bestScore = score;
            }
        }
        return best;
    }

    private static Lid lid(final int fx, final int fy, final int scale, final int argb)
    {
        return new Lid((8 + fx) * scale, (8 + fy) * scale, scale, argb);
    }

    private static int face(final Pixels img, final int scale, final int fx, final int fy)
    {
        return img.argb((8 + fx) * scale + scale / 2, (8 + fy) * scale + scale / 2);
    }

    /**
     * Skin tone: the biggest group of similar colours on the cheeks (skins are usually shaded with slight variations),
     * averaged. 0 if there is none.
     */
    private static int skinTone(final Pixels img, final int scale)
    {
        final Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int fy = 5; fy <= 7; fy++)
        {
            for (int fx = 0; fx <= 7; fx++)
            {
                final int color = face(img, scale, fx, fy);
                if ((color >>> 24) > 200)
                {
                    final int key = ((color >> 20) & 0xF) << 8 | ((color >> 12) & 0xF) << 4 | ((color >> 4) & 0xF);
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(color);
                }
            }
        }
        List<Integer> best = null;
        for (final List<Integer> group : groups.values())
        {
            if (best == null || group.size() > best.size())
            {
                best = group;
            }
        }
        if (best == null || best.size() < 5)
        {
            return 0;
        }
        int r = 0;
        int g = 0;
        int b = 0;
        for (final int color : best)
        {
            r += (color >> 16) & 0xFF;
            g += (color >> 8) & 0xFF;
            b += color & 0xFF;
        }
        final int n = best.size();
        return 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
    }

    private static boolean isWhite(final int color)
    {
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = color & 0xFF;
        return (color >>> 24) > 200 && r > 200 && g > 200 && b > 200 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 30;
    }

    private static List<Integer> differentColumns(final Pixels img, final int scale, final int fy, final int skin)
    {
        final List<Integer> columns = new ArrayList<>();
        for (int fx = 0; fx <= 7; fx++)
        {
            final int color = face(img, scale, fx, fy);
            if ((color >>> 24) > 128 && distance(color, skin) > DIFFERENT)
            {
                columns.add(fx);
            }
        }
        return columns;
    }

    private static int distance(final int a, final int b)
    {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                 + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                 + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static int shade(final int color, final float factor)
    {
        final int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        final int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        final int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(final int a, final int b, final float t)
    {
        final int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        final int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        final int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
