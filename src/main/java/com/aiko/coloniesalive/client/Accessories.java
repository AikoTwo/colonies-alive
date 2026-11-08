package com.aiko.coloniesalive.client;

import com.aiko.coloniesalive.ColoniesAlive;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The little things babies and children wear on top of MineColonies' models (drawn by {@link AccessoryLayer}).
 * MineColonies already gives most jobs a hat or glasses, so these are about age and moments of life: 20 for babies,
 * 50 for children, plus a few for everyone (nightcap, Santa hat, a band-aid after crying).
 *
 * <p>Each accessory sits in a slot (head, face, neck, back, waist, the hand, both hands, the feet); every baby and every
 * child gets its own outfit, a new one each Minecraft day ({@link #outfit}). Pieces are a few boxes in the space of the
 * part they hang from (head: x -4..4, y -8..0, z -4..4, the face looks to -z; torso: x -4..4, y 0..12, z -2..2; arms
 * and legs hang from the shoulder / hip, the hand is around y 10). White "tinted" pieces take the wearer's colour.
 * The texture is drawn at runtime: one plain region per box, knitted ones with ribs.
 */
public final class Accessories
{
    public enum Anchor
    {
        HEAD,
        BODY,
        RIGHT_ARM,
        LEFT_ARM,
        RIGHT_LEG,
        LEFT_LEG
    }

    public enum Slot
    {
        HEAD,
        FACE,
        NECK,
        BACK,
        WAIST,
        HAND,
        HANDS,
        FEET
    }

    /** Some pieces move: propellers and pinwheels spin, wings flap. */
    public enum Motion
    {
        NONE,
        SPIN_Y,
        SPIN_Z,
        FLAP_LEFT,
        FLAP_RIGHT
    }

    /** Who may wear it. */
    public enum Who
    {
        ANY,
        GIRLS,
        BOYS
    }

    private record Box(float x, float y, float z, float w, float h, float d, int color, boolean knit) {}

    /** One drawable part: a few boxes, where they hang, whether the wearer's colour applies, how it moves. */
    public record Piece(Anchor anchor, ModelPart part, boolean tinted, Motion motion) {}

    /** One accessory: its slot, its pieces, who wears it. */
    public record Accessory(String id, Slot slot, List<Piece> pieces, Who who) {}

    public static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(ColoniesAlive.MODID, "dynamic/accessories");
    private static final int ATLAS_W = 512;
    private static final int ATLAS_H = 512;

    // colours
    private static final int T          = 0xFFFFFF;   // white: tinted with the wearer's colour
    private static final int OFF_WHITE  = 0xF4F1EA;
    private static final int SANTA_RED  = 0xD32F2F;
    private static final int VINE       = 0x5E8C3A;
    private static final int LEAF       = 0x7CB342;
    private static final int STRAP      = 0x5D4037;
    private static final int PINK       = 0xF48FB1;
    private static final int YELLOW     = 0xFFE066;
    private static final int GOLD       = 0xF2C230;
    private static final int RED        = 0xE53935;
    private static final int BLUE       = 0x1E88E5;
    private static final int BLACK      = 0x222222;
    private static final int BROWN      = 0x8D6E63;
    private static final int DARK_BROWN = 0x4E342E;
    private static final int WOOD       = 0xC8A165;
    private static final int STRAW      = 0xE2C068;
    private static final int SILVER     = 0xCFD8DC;
    private static final int TAN        = 0xD7B899;

    // packing of the boxes' regions into the atlas
    private static int cursorX;
    private static int cursorY;
    private static int rowHeight;
    private static final List<int[]> FILLS = new ArrayList<>();
    private static boolean uploaded;

    public static final List<Accessory> BABY  = new ArrayList<>();
    public static final List<Accessory> CHILD = new ArrayList<>();

    // ------------------------------------------------------------------------------------------------------------------
    // Shorthands
    // ------------------------------------------------------------------------------------------------------------------

    private static Box b(final float x, final float y, final float z, final float w, final float h, final float d, final int color)
    {
        return new Box(x, y, z, w, h, d, color, false);
    }

    private static Box k(final float x, final float y, final float z, final float w, final float h, final float d, final int color)
    {
        return new Box(x, y, z, w, h, d, color, true);
    }

    /** A knitted cap snug on the head (dome and rim). */
    private static Box[] cap(final int color)
    {
        return new Box[] {k(-4.7f, -9.1f, -4.7f, 9.4f, 3.8f, 9.4f, color), k(-4.95f, -5.7f, -4.95f, 9.9f, 1.0f, 9.9f, color)};
    }

    /** A thin band over the top of the head, from ear to ear. */
    private static Box[] headband(final int color)
    {
        return new Box[] {b(-4.75f, -8.85f, -0.6f, 9.5f, 0.55f, 1.2f, color), b(-4.75f, -8.85f, -0.6f, 0.55f, 3.4f, 1.2f, color),
          b(4.2f, -8.85f, -0.6f, 0.55f, 3.4f, 1.2f, color)};
    }

    private static Box[] concat(final Box[] a, final Box... more)
    {
        final Box[] all = new Box[a.length + more.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(more, 0, all, a.length, more.length);
        return all;
    }

    private static Piece p(final Anchor anchor, final boolean tinted, final Box... boxes)
    {
        return piece(anchor, tinted, Motion.NONE, 0, 0, 0, boxes);
    }

    private static Accessory add(final List<Accessory> list, final String id, final Slot slot, final Who who, final Piece... pieces)
    {
        final Accessory accessory = new Accessory(id, slot, List.of(pieces), who);
        list.add(accessory);
        return accessory;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Babies (20)
    // ------------------------------------------------------------------------------------------------------------------

    public static final Accessory BABY_CAP = add(BABY, "touquinha", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, cap(T)), p(Anchor.HEAD, false, k(-1.2f, -10.6f, -1.2f, 2.4f, 1.6f, 2.4f, 0xFFFFFF)));
    public static final Accessory BEAR_CAP = add(BABY, "touca de ursinho", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, false, concat(cap(0xA1785C), b(-4.4f, -10.6f, -1f, 2.2f, 2.2f, 1.2f, 0xA1785C), b(2.2f, -10.6f, -1f, 2.2f, 2.2f, 1.2f, 0xA1785C),
        b(-3.9f, -10.1f, -1.15f, 1.2f, 1.2f, 0.2f, 0xF4A6B0), b(2.7f, -10.1f, -1.15f, 1.2f, 1.2f, 0.2f, 0xF4A6B0))));
    public static final Accessory BUNNY_CAP = add(BABY, "touca de coelhinho", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, false, concat(cap(0xF5F5F5), b(-3.2f, -13.8f, -0.6f, 1.6f, 4.9f, 1.2f, 0xF5F5F5), b(1.6f, -13.8f, -0.6f, 1.6f, 4.9f, 1.2f, 0xF5F5F5),
        b(-2.9f, -13.2f, -0.75f, 1f, 3.6f, 0.2f, 0xF4A6B0), b(1.9f, -13.2f, -0.75f, 1f, 3.6f, 0.2f, 0xF4A6B0))));
    public static final Accessory FROG_HAT = add(BABY, "gorro de sapinho", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, false, concat(cap(LEAF), b(-3.8f, -10.6f, -3.2f, 2.4f, 2.2f, 2.2f, 0xFFFFFF), b(1.4f, -10.6f, -3.2f, 2.4f, 2.2f, 2.2f, 0xFFFFFF),
        b(-3.1f, -9.9f, -3.35f, 1f, 1f, 0.2f, BLACK), b(2.1f, -9.9f, -3.35f, 1f, 1f, 0.2f, BLACK))));
    public static final Accessory SUN_BONNET = add(BABY, "chapeuzinho de sol", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-4.8f, -9.2f, -4.4f, 9.6f, 4.2f, 9.2f, T), b(-5.2f, -6.0f, -7.0f, 10.4f, 0.6f, 2.8f, T),
        b(-5.0f, -9.3f, -4.9f, 10f, 0.8f, 0.8f, T)));
    public static final Accessory FLOWER_HEADBAND = add(BABY, "tiara de florzinha", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, headband(T)),
      p(Anchor.HEAD, false, b(2.4f, -9.7f, -1.4f, 1.9f, 1.9f, 1f, PINK), b(3.05f, -9.05f, -1.6f, 0.6f, 0.6f, 0.3f, YELLOW)));
    public static final Accessory TINY_CROWN = add(BABY, "coroinha", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, false, b(-2.6f, -9.4f, -2.6f, 5.2f, 1.2f, 5.2f, GOLD), b(-2.6f, -10.4f, -2.6f, 1f, 1f, 1f, GOLD),
        b(1.6f, -10.4f, -2.6f, 1f, 1f, 1f, GOLD), b(-2.6f, -10.4f, 1.6f, 1f, 1f, 1f, GOLD), b(1.6f, -10.4f, 1.6f, 1f, 1f, 1f, GOLD),
        b(-0.4f, -9.1f, -2.75f, 0.8f, 0.8f, 0.2f, RED)));
    public static final Accessory TINY_CAP = add(BABY, "boné de bebê", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-4.7f, -9.4f, -4.7f, 9.4f, 2.6f, 9.4f, T), b(-4f, -7.0f, -7.4f, 8f, 0.6f, 2.8f, T), b(-0.5f, -9.8f, -0.5f, 1f, 0.5f, 1f, T)));
    public static final Accessory TINY_BOW = add(BABY, "lacinho", Slot.HEAD, Who.GIRLS,
      p(Anchor.HEAD, true, b(2.4f, -9.0f, -2.2f, 1f, 1f, 1f, T), b(1.2f, -9.2f, -2.1f, 1.3f, 1.4f, 0.8f, T), b(3.3f, -9.2f, -2.1f, 1.3f, 1.4f, 0.8f, T)));
    public static final Accessory HAIR_TUFT = add(BABY, "chuquinha", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-0.9f, -9.1f, -0.9f, 1.8f, 0.7f, 1.8f, T), b(-1.5f, -9.6f, -0.4f, 0.8f, 0.8f, 0.8f, T), b(0.7f, -9.6f, -0.4f, 0.8f, 0.8f, 0.8f, T)),
      p(Anchor.HEAD, false, b(-0.5f, -10.7f, -0.5f, 1f, 1.7f, 1f, DARK_BROWN)));
    public static final Accessory PACIFIER = add(BABY, "chupeta", Slot.FACE, Who.ANY,
      p(Anchor.HEAD, true, b(-1.5f, -2.7f, -4.8f, 3f, 1.8f, 0.5f, T)), p(Anchor.HEAD, false, b(-0.6f, -2.3f, -5.5f, 1.2f, 1.0f, 0.7f, OFF_WHITE)));
    public static final Accessory BIB = add(BABY, "babador", Slot.NECK, Who.ANY,
      p(Anchor.BODY, false, k(-3f, 0.2f, -2.5f, 6f, 5f, 0.35f, OFF_WHITE)));
    public static final Accessory TINY_SCARF = add(BABY, "cachecolzinho", Slot.NECK, Who.ANY,
      p(Anchor.BODY, true, k(-4.3f, -0.3f, -2.5f, 8.6f, 1.6f, 5f, T), k(1.4f, 1.2f, -2.75f, 1.6f, 3.6f, 0.5f, T)));
    public static final Accessory TEETHING_NECKLACE = add(BABY, "colar de mordedor", Slot.NECK, Who.ANY, p(Anchor.BODY, false,
      b(-3.2f, 0.4f, -2.6f, 0.8f, 0.8f, 0.5f, 0xF8BBD0), b(-2.2f, 1.0f, -2.6f, 0.8f, 0.8f, 0.5f, 0xB3E5FC), b(-1.2f, 1.4f, -2.6f, 0.8f, 0.8f, 0.5f, 0xFFF9C4),
      b(-0.4f, 1.6f, -2.65f, 0.8f, 0.8f, 0.5f, 0xC8E6C9), b(0.4f, 1.4f, -2.6f, 0.8f, 0.8f, 0.5f, 0xF8BBD0), b(1.4f, 1.0f, -2.6f, 0.8f, 0.8f, 0.5f, 0xB3E5FC),
      b(2.4f, 0.4f, -2.6f, 0.8f, 0.8f, 0.5f, 0xFFF9C4)));
    public static final Accessory MITTENS = add(BABY, "luvinhas", Slot.HANDS, Who.ANY,
      p(Anchor.RIGHT_ARM, true, k(-3.3f, 7.6f, -2.3f, 4.6f, 2.9f, 4.6f, T)), p(Anchor.LEFT_ARM, true, k(-1.3f, 7.6f, -2.3f, 4.6f, 2.9f, 4.6f, T)));
    public static final Accessory BOOTIES = add(BABY, "sapatinhos de lã", Slot.FEET, Who.ANY,
      p(Anchor.RIGHT_LEG, true, k(-2.3f, 9.4f, -2.7f, 4.6f, 2.8f, 5.0f, T)), p(Anchor.LEFT_LEG, true, k(-2.3f, 9.4f, -2.7f, 4.6f, 2.8f, 5.0f, T)));
    public static final Accessory BLANKET_CAPE = add(BABY, "mantinha", Slot.BACK, Who.ANY,
      p(Anchor.BODY, true, k(-4.6f, 0f, 2.35f, 9.2f, 10.5f, 0.5f, T), k(-4.8f, -0.5f, 1.7f, 9.6f, 1.2f, 1.3f, T)));
    public static final Accessory RATTLE = add(BABY, "chocalho", Slot.HAND, Who.ANY,
      p(Anchor.RIGHT_ARM, false, b(-1.5f, 9.4f, -3.2f, 1f, 1f, 3.2f, OFF_WHITE)),
      p(Anchor.RIGHT_ARM, true, b(-2.3f, 8.6f, -5.6f, 2.6f, 2.6f, 2.6f, T)));
    public static final Accessory TEDDY = add(BABY, "ursinho de pelúcia", Slot.HAND, Who.ANY, p(Anchor.RIGHT_ARM, false,
      b(-2.4f, 9.6f, -4.4f, 2.8f, 3f, 2.2f, BROWN), b(-2.2f, 7.6f, -4.2f, 2.4f, 2.2f, 2f, BROWN), b(-2.4f, 7.0f, -3.6f, 0.8f, 0.8f, 0.6f, BROWN),
      b(-0.6f, 7.0f, -3.6f, 0.8f, 0.8f, 0.6f, BROWN), b(-1.4f, 8.4f, -4.4f, 0.8f, 0.6f, 0.3f, TAN)));
    public static final Accessory BOTTLE = add(BABY, "mamadeira", Slot.HAND, Who.ANY,
      p(Anchor.RIGHT_ARM, false, b(-1.8f, 9.2f, -5.8f, 1.6f, 1.6f, 3.4f, 0xF5F5F5), b(-1.4f, 9.6f, -7.1f, 0.8f, 0.8f, 0.8f, TAN)),
      p(Anchor.RIGHT_ARM, true, b(-2.0f, 9.0f, -6.4f, 2.0f, 2.0f, 0.7f, T)));

    // ------------------------------------------------------------------------------------------------------------------
    // Children (50)
    // ------------------------------------------------------------------------------------------------------------------

    // head (23)
    public static final Accessory HAIR_BOW = add(CHILD, "laço de cabelo", Slot.HEAD, Who.GIRLS,
      p(Anchor.HEAD, true, b(1.3f, -9.4f, -1.0f, 1.4f, 1.4f, 1.4f, T), b(-1.2f, -9.7f, -0.8f, 2.5f, 2.0f, 1.0f, T), b(2.7f, -9.7f, -0.8f, 2.5f, 2.0f, 1.0f, T)));
    public static final Accessory FLOWER_CROWN = add(CHILD, "coroa de flores", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-4.8f, -9.0f, -4.9f, 9.6f, 0.6f, 0.6f, VINE), b(-4.8f, -9.0f, 4.3f, 9.6f, 0.6f, 0.6f, VINE), b(4.3f, -9.0f, -4.8f, 0.6f, 0.6f, 9.6f, VINE),
      b(-4.9f, -9.0f, -4.8f, 0.6f, 0.6f, 9.6f, VINE), b(-3.2f, -9.5f, -5.2f, 1.3f, 1.3f, 1.3f, YELLOW), b(-0.65f, -9.6f, -5.3f, 1.3f, 1.3f, 1.3f, PINK),
      b(1.9f, -9.5f, -5.2f, 1.3f, 1.3f, 1.3f, 0xFAFAFA), b(4.0f, -9.5f, -2.6f, 1.3f, 1.3f, 1.3f, 0xB39DDB), b(4.0f, -9.5f, 1.4f, 1.3f, 1.3f, 1.3f, YELLOW),
      b(-5.3f, -9.5f, -2.6f, 1.3f, 1.3f, 1.3f, PINK), b(-5.3f, -9.5f, 1.4f, 1.3f, 1.3f, 1.3f, 0xFAFAFA), b(-0.65f, -9.5f, 4.0f, 1.3f, 1.3f, 1.3f, 0xB39DDB)));
    public static final Accessory BASEBALL_CAP = add(CHILD, "boné", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-4.7f, -9.4f, -4.7f, 9.4f, 2.6f, 9.4f, T), b(-4f, -7.0f, -7.6f, 8f, 0.6f, 3f, T), b(-0.5f, -9.8f, -0.5f, 1f, 0.5f, 1f, T)));
    public static final Accessory CAP_BACKWARDS = add(CHILD, "boné virado", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-4.7f, -9.4f, -4.7f, 9.4f, 2.6f, 9.4f, T), b(-4f, -7.0f, 4.6f, 8f, 0.6f, 3f, T), b(-0.5f, -9.8f, -0.5f, 1f, 0.5f, 1f, T)));
    public static final Accessory STRAW_HAT = add(CHILD, "chapéu de palha", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      k(-4.6f, -10.8f, -4.6f, 9.2f, 3f, 9.2f, STRAW), k(-6.8f, -8.0f, -6.8f, 13.6f, 0.6f, 13.6f, 0xD9B65C), b(-4.7f, -8.6f, -4.7f, 9.4f, 0.8f, 9.4f, 0xC62828)));
    public static final Accessory HEADBAND = add(CHILD, "tiara", Slot.HEAD, Who.ANY, p(Anchor.HEAD, true, headband(T)));
    public static final Accessory CAT_EARS = add(CHILD, "orelhinhas de gato", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, concat(headband(T), b(-3.8f, -10.9f, -0.7f, 2f, 2.2f, 1f, T), b(1.8f, -10.9f, -0.7f, 2f, 2.2f, 1f, T))),
      p(Anchor.HEAD, false, b(-3.4f, -10.4f, -0.8f, 1.2f, 1.4f, 0.2f, 0xF4A6B0), b(2.2f, -10.4f, -0.8f, 1.2f, 1.4f, 0.2f, 0xF4A6B0)));
    public static final Accessory BUNNY_EARS = add(CHILD, "orelhas de coelho", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      concat(headband(0xF5F5F5), b(-3.2f, -14.2f, -0.6f, 1.6f, 5.4f, 1.2f, 0xF5F5F5), b(1.6f, -14.2f, -0.6f, 1.6f, 5.4f, 1.2f, 0xF5F5F5),
        b(-2.9f, -13.6f, -0.75f, 1f, 4f, 0.2f, 0xF4A6B0), b(1.9f, -13.6f, -0.75f, 1f, 4f, 0.2f, 0xF4A6B0))));
    public static final Accessory PAPER_CROWN = add(CHILD, "coroa de papel", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-4.75f, -9.8f, -4.75f, 9.5f, 1.4f, 9.5f, GOLD), b(-4.6f, -10.8f, -4.85f, 1f, 1f, 0.4f, GOLD), b(-0.5f, -10.8f, -4.85f, 1f, 1f, 0.4f, GOLD),
      b(3.6f, -10.8f, -4.85f, 1f, 1f, 0.4f, GOLD), b(-4.6f, -10.8f, 4.45f, 1f, 1f, 0.4f, GOLD), b(-0.5f, -10.8f, 4.45f, 1f, 1f, 0.4f, GOLD),
      b(3.6f, -10.8f, 4.45f, 1f, 1f, 0.4f, GOLD), b(-0.4f, -9.4f, -4.9f, 0.8f, 0.8f, 0.2f, BLUE)));
    public static final Accessory PIRATE_HAT = add(CHILD, "chapéu de pirata", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-5f, -9.8f, -4.7f, 10f, 2f, 9.4f, BLACK), b(-5.6f, -11.4f, -5.1f, 11.2f, 2.6f, 0.6f, BLACK), b(-5.6f, -11.7f, -5.15f, 11.2f, 0.3f, 0.7f, GOLD),
      b(-0.6f, -10.6f, -5.25f, 1.2f, 1.2f, 0.2f, 0xFFFFFF)));
    public static final Accessory VIKING_HELMET = add(CHILD, "elmo viking", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-4.85f, -9.6f, -4.85f, 9.7f, 2.6f, 9.7f, 0x9E9E9E), b(-4.95f, -7.4f, -4.95f, 9.9f, 0.8f, 9.9f, 0x616161),
      b(-6.4f, -10.2f, -0.6f, 1.6f, 1.2f, 1.2f, 0xF5F0E1), b(-6.9f, -11.7f, -0.5f, 1f, 1.6f, 1f, 0xF5F0E1),
      b(4.8f, -10.2f, -0.6f, 1.6f, 1.2f, 1.2f, 0xF5F0E1), b(5.9f, -11.7f, -0.5f, 1f, 1.6f, 1f, 0xF5F0E1)));
    public static final Accessory COWBOY_HAT = add(CHILD, "chapéu de cowboy", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-3.8f, -11.4f, -3.8f, 7.6f, 3f, 7.6f, 0x8D5A3B), b(-3.9f, -8.9f, -3.9f, 7.8f, 0.5f, 7.8f, DARK_BROWN),
      b(-6.8f, -8.6f, -6.8f, 13.6f, 0.6f, 13.6f, 0x8D5A3B), b(-6.8f, -9.6f, -6.8f, 0.6f, 1f, 13.6f, 0x8D5A3B), b(6.2f, -9.6f, -6.8f, 0.6f, 1f, 13.6f, 0x8D5A3B)));
    public static final Accessory POMPOM_BEANIE = add(CHILD, "gorro com pompom", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, k(-4.75f, -9.6f, -4.75f, 9.5f, 3.6f, 9.5f, T), k(-4.95f, -6.4f, -4.95f, 9.9f, 1.2f, 9.9f, T)),
      p(Anchor.HEAD, false, k(-1.3f, -11.2f, -1.3f, 2.6f, 1.8f, 2.6f, 0xFFFFFF)));
    public static final Accessory SWEATBAND = add(CHILD, "faixa esportiva", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, k(-4.75f, -6.9f, -4.75f, 9.5f, 1.2f, 9.5f, T)));
    public static final Accessory PROPELLER_BEANIE = add(CHILD, "boné de hélice", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-4.7f, -9.4f, -4.7f, 9.4f, 2.4f, 9.4f, T)), p(Anchor.HEAD, false, b(-0.3f, -10.6f, -0.3f, 0.6f, 1.3f, 0.6f, 0x9E9E9E)),
      piece(Anchor.HEAD, false, Motion.SPIN_Y, 0, -10.7f, 0, b(-3f, -0.15f, -0.4f, 6f, 0.3f, 0.8f, RED), b(-0.4f, -0.15f, -3f, 0.8f, 0.3f, 6f, BLUE)));
    public static final Accessory LEAF_CROWN = add(CHILD, "coroa de folhas", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-4.8f, -9.0f, -4.9f, 9.6f, 0.6f, 0.6f, VINE), b(-4.8f, -9.0f, 4.3f, 9.6f, 0.6f, 0.6f, VINE), b(4.3f, -9.0f, -4.8f, 0.6f, 0.6f, 9.6f, VINE),
      b(-4.9f, -9.0f, -4.8f, 0.6f, 0.6f, 9.6f, VINE), b(-3.6f, -9.7f, -5.2f, 1.6f, 0.8f, 1f, LEAF), b(-0.8f, -9.9f, -5.3f, 1.6f, 1f, 1f, 0x9CCC65),
      b(2.0f, -9.7f, -5.2f, 1.6f, 0.8f, 1f, LEAF), b(4.2f, -9.7f, -2.6f, 1f, 0.8f, 1.6f, 0x9CCC65), b(-5.2f, -9.7f, -2.6f, 1f, 0.8f, 1.6f, LEAF),
      b(4.2f, -9.7f, 1.4f, 1f, 0.8f, 1.6f, LEAF), b(-5.2f, -9.7f, 1.4f, 1f, 0.8f, 1.6f, 0x9CCC65)));
    public static final Accessory HAIR_CLIPS = add(CHILD, "presilhas", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(4.35f, -6.9f, -2.6f, 0.5f, 0.6f, 2.2f, T)), p(Anchor.HEAD, false, b(4.35f, -5.7f, -2.2f, 0.5f, 0.6f, 2.2f, 0xFFD54F),
        b(-4.85f, -6.9f, -2.6f, 0.5f, 0.6f, 2.2f, 0xFFD54F)));
    public static final Accessory STAR_TIARA = add(CHILD, "tiara de estrela", Slot.HEAD, Who.GIRLS, p(Anchor.HEAD, false,
      b(-4.75f, -8.85f, -0.4f, 9.5f, 0.4f, 0.8f, SILVER), b(-3f, -8.95f, -4.8f, 6f, 0.4f, 0.4f, SILVER), b(-0.9f, -10.3f, -4.85f, 1.8f, 1.8f, 0.4f, 0xFFD740)));
    public static final Accessory PARTY_HAT = add(CHILD, "chapéu de festa", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-2.6f, -10.2f, -2.6f, 5.2f, 1.6f, 5.2f, T), b(-1.9f, -11.8f, -1.9f, 3.8f, 1.6f, 3.8f, T),
        b(-1.2f, -13.4f, -1.2f, 2.4f, 1.6f, 2.4f, T), b(-0.6f, -14.6f, -0.6f, 1.2f, 1.2f, 1.2f, T)),
      p(Anchor.HEAD, false, b(-0.8f, -15.4f, -0.8f, 1.6f, 1f, 1.6f, YELLOW)));
    public static final Accessory BANDANA = add(CHILD, "bandana", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-4.75f, -9.0f, -4.75f, 9.5f, 2.9f, 9.5f, T), b(-1f, -7.6f, 4.7f, 2f, 1.4f, 1.2f, T),
        b(-1.3f, -6.6f, 5.4f, 1f, 2.4f, 0.4f, T), b(0.3f, -6.6f, 5.4f, 1f, 2.0f, 0.4f, T)));
    public static final Accessory MINI_CHEF_HAT = add(CHILD, "chapéu de chef mirim", Slot.HEAD, Who.ANY, p(Anchor.HEAD, false,
      b(-4.5f, -9.6f, -4.5f, 9f, 2.2f, 9f, 0xFFFFFF), k(-5.2f, -13.6f, -5.2f, 10.4f, 4f, 10.4f, 0xFAFAFA)));
    public static final Accessory BERET = add(CHILD, "boina", Slot.HEAD, Who.ANY,
      p(Anchor.HEAD, true, b(-5.2f, -9.4f, -5.2f, 10.4f, 1.2f, 10.4f, T), b(-0.4f, -10.2f, -0.4f, 0.8f, 0.8f, 0.8f, T)));
    public static final Accessory FLOWER_CLIP = add(CHILD, "flor no cabelo", Slot.HEAD, Who.GIRLS, p(Anchor.HEAD, false,
      b(4.3f, -7.4f, -2.0f, 1.2f, 2.4f, 2.4f, PINK), b(4.55f, -6.6f, -1.2f, 1.1f, 0.8f, 0.8f, YELLOW)));

    // face (7)
    public static final Accessory ROUND_GLASSES = add(CHILD, "óculos redondos", Slot.FACE, Who.ANY, p(Anchor.HEAD, false,
      b(-3.6f, -5.3f, -4.65f, 3f, 0.4f, 0.25f, STRAP), b(-3.6f, -3.7f, -4.65f, 3f, 0.4f, 0.25f, STRAP), b(-3.6f, -5.3f, -4.65f, 0.4f, 2f, 0.25f, STRAP),
      b(-1.0f, -5.3f, -4.65f, 0.4f, 2f, 0.25f, STRAP), b(0.6f, -5.3f, -4.65f, 3f, 0.4f, 0.25f, STRAP), b(0.6f, -3.7f, -4.65f, 3f, 0.4f, 0.25f, STRAP),
      b(0.6f, -5.3f, -4.65f, 0.4f, 2f, 0.25f, STRAP), b(3.2f, -5.3f, -4.65f, 0.4f, 2f, 0.25f, STRAP), b(-0.6f, -4.8f, -4.65f, 1.2f, 0.3f, 0.25f, STRAP),
      b(-4.75f, -5.0f, -4.6f, 0.3f, 0.3f, 4.6f, STRAP), b(4.45f, -5.0f, -4.6f, 0.3f, 0.3f, 4.6f, STRAP)));
    public static final Accessory SUNGLASSES = add(CHILD, "óculos de sol", Slot.FACE, Who.ANY, p(Anchor.HEAD, false,
      b(-3.6f, -5.2f, -4.7f, 3f, 1.8f, 0.3f, 0x1A1A1A), b(0.6f, -5.2f, -4.7f, 3f, 1.8f, 0.3f, 0x1A1A1A), b(-0.6f, -4.9f, -4.7f, 1.2f, 0.4f, 0.3f, 0x1A1A1A),
      b(-4.75f, -5.0f, -4.6f, 0.3f, 0.4f, 4.6f, 0x1A1A1A), b(4.45f, -5.0f, -4.6f, 0.3f, 0.4f, 4.6f, 0x1A1A1A)));
    public static final Accessory FAKE_MUSTACHE = add(CHILD, "bigode de mentirinha", Slot.FACE, Who.ANY, p(Anchor.HEAD, false,
      b(-2.2f, -2.7f, -4.65f, 4.4f, 0.8f, 0.3f, 0x3E2723), b(-2.9f, -3.2f, -4.65f, 0.8f, 0.8f, 0.3f, 0x3E2723), b(2.1f, -3.2f, -4.65f, 0.8f, 0.8f, 0.3f, 0x3E2723)));
    public static final Accessory CLOWN_NOSE = add(CHILD, "nariz de palhaço", Slot.FACE, Who.ANY,
      p(Anchor.HEAD, false, b(-0.8f, -3.8f, -5.3f, 1.6f, 1.6f, 1.2f, RED)));
    public static final Accessory EYEPATCH = add(CHILD, "tapa-olho de pirata", Slot.FACE, Who.ANY, p(Anchor.HEAD, false,
      b(-3.5f, -5.1f, -4.7f, 2.4f, 2.1f, 0.3f, BLACK), b(-4.7f, -6.4f, -4.62f, 9.4f, 0.4f, 0.2f, BLACK),
      b(-4.75f, -6.4f, -4.6f, 0.25f, 0.4f, 9.2f, BLACK), b(4.5f, -6.4f, -4.6f, 0.25f, 0.4f, 9.2f, BLACK)));
    public static final Accessory HERO_MASK = add(CHILD, "máscara de herói", Slot.FACE, Who.ANY, p(Anchor.HEAD, true,
      b(-4.7f, -5.6f, -4.7f, 9.4f, 0.5f, 0.3f, T), b(-4.7f, -3.6f, -4.7f, 9.4f, 0.5f, 0.3f, T), b(-0.7f, -5.1f, -4.7f, 1.4f, 1.5f, 0.3f, T),
      b(-4.7f, -5.1f, -4.7f, 1.1f, 1.5f, 0.3f, T), b(3.6f, -5.1f, -4.7f, 1.1f, 1.5f, 0.3f, T)));
    public static final Accessory FACE_PAINT = add(CHILD, "coraçãozinho pintado", Slot.FACE, Who.ANY, p(Anchor.HEAD, false,
      b(1.9f, -3.3f, -4.56f, 1.3f, 1.1f, 0.1f, 0xEC407A), b(2.2f, -2.4f, -4.56f, 0.7f, 0.4f, 0.1f, 0xEC407A)));

    // neck (6)
    public static final Accessory SCARF = add(CHILD, "cachecol", Slot.NECK, Who.ANY,
      p(Anchor.BODY, true, k(-4.4f, -0.4f, -2.6f, 8.8f, 1.8f, 5.2f, T), k(1.2f, 1.2f, -2.85f, 1.6f, 4.2f, 0.5f, T)));
    public static final Accessory BOW_TIE = add(CHILD, "gravata-borboleta", Slot.NECK, Who.ANY,
      p(Anchor.BODY, true, b(-0.5f, 0.4f, -2.6f, 1f, 1f, 0.45f, T), b(-2f, 0.15f, -2.5f, 1.5f, 1.5f, 0.35f, T), b(0.5f, 0.15f, -2.5f, 1.5f, 1.5f, 0.35f, T)));
    public static final Accessory BEAD_NECKLACE = add(CHILD, "colar de miçangas", Slot.NECK, Who.ANY, p(Anchor.BODY, false,
      b(-3.2f, 0.5f, -2.55f, 0.7f, 0.7f, 0.4f, RED), b(-2.3f, 1.1f, -2.55f, 0.7f, 0.7f, 0.4f, 0xFB8C00), b(-1.3f, 1.6f, -2.55f, 0.7f, 0.7f, 0.4f, 0xFDD835),
      b(-0.35f, 1.9f, -2.55f, 0.7f, 0.7f, 0.4f, 0x43A047), b(0.6f, 1.6f, -2.55f, 0.7f, 0.7f, 0.4f, BLUE), b(1.6f, 1.1f, -2.55f, 0.7f, 0.7f, 0.4f, 0x8E24AA),
      b(2.5f, 0.5f, -2.55f, 0.7f, 0.7f, 0.4f, RED)));
    public static final Accessory GOLD_MEDAL = add(CHILD, "medalha de ouro", Slot.NECK, Who.ANY,
      p(Anchor.BODY, true, b(-1.8f, 0f, -2.45f, 0.6f, 3.2f, 0.2f, T), b(1.2f, 0f, -2.45f, 0.6f, 3.2f, 0.2f, T)),
      p(Anchor.BODY, false, b(-1.0f, 2.9f, -2.65f, 2f, 2f, 0.4f, GOLD)));
    public static final Accessory NECKERCHIEF = add(CHILD, "lenço de escoteiro", Slot.NECK, Who.ANY,
      p(Anchor.BODY, true, b(-3.2f, 0f, -2.55f, 6.4f, 1.2f, 0.4f, T), b(-2.2f, 1.2f, -2.55f, 4.4f, 1f, 0.4f, T), b(-1.1f, 2.2f, -2.55f, 2.2f, 1f, 0.4f, T)),
      p(Anchor.BODY, false, b(-0.6f, 1.0f, -2.75f, 1.2f, 0.8f, 0.3f, BROWN)));
    public static final Accessory WHISTLE = add(CHILD, "apito", Slot.NECK, Who.ANY, p(Anchor.BODY, false,
      b(-0.15f, 0f, -2.4f, 0.3f, 3.2f, 0.2f, RED), b(-0.7f, 3.1f, -2.9f, 1.4f, 0.9f, 1.1f, 0xB0BEC5)));

    // back (5)
    public static final Accessory BACKPACK = add(CHILD, "mochila escolar", Slot.BACK, Who.ANY,
      p(Anchor.BODY, true, b(-3f, 1.6f, 2.25f, 6f, 6.5f, 2.6f, T), b(-3.2f, 1.3f, 2.15f, 6.4f, 2.4f, 2.9f, T)),
      p(Anchor.BODY, false, b(-3f, 0f, -2.4f, 0.9f, 6.2f, 0.3f, STRAP), b(2.1f, 0f, -2.4f, 0.9f, 6.2f, 0.3f, STRAP)));
    public static final Accessory HERO_CAPE = add(CHILD, "capa de super-herói", Slot.BACK, Who.ANY,
      p(Anchor.BODY, true, b(-4.5f, 0f, 2.35f, 9f, 11.5f, 0.4f, T), b(-4.6f, -0.3f, 1.7f, 9.2f, 1f, 1.2f, T)));
    public static final Accessory BUTTERFLY_WINGS = add(CHILD, "asas de borboleta", Slot.BACK, Who.ANY,
      piece(Anchor.BODY, true, Motion.FLAP_LEFT, 0.5f, 2f, 2.5f, b(0f, -1f, 0f, 6f, 6.5f, 0.3f, T), b(0f, 5f, 0f, 5f, 3.5f, 0.3f, T)),
      piece(Anchor.BODY, true, Motion.FLAP_RIGHT, -0.5f, 2f, 2.5f, b(-6f, -1f, 0f, 6f, 6.5f, 0.3f, T), b(-5f, 5f, 0f, 5f, 3.5f, 0.3f, T)));
    public static final Accessory QUIVER = add(CHILD, "aljava de flechas", Slot.BACK, Who.ANY, p(Anchor.BODY, false,
      b(0.6f, 0.2f, 2.3f, 2.6f, 8f, 2.4f, 0x795548), b(1.0f, -1.8f, 2.8f, 0.4f, 2f, 0.4f, WOOD), b(1.8f, -2.2f, 3.2f, 0.4f, 2.4f, 0.4f, WOOD),
      b(2.5f, -1.6f, 2.9f, 0.4f, 1.8f, 0.4f, WOOD), b(0.9f, -2.3f, 2.7f, 0.6f, 0.6f, 0.6f, 0xFFFFFF), b(1.7f, -2.7f, 3.1f, 0.6f, 0.6f, 0.6f, RED),
      b(2.4f, -2.1f, 2.8f, 0.6f, 0.6f, 0.6f, 0xFFFFFF)));
    public static final Accessory TEDDY_BACKPACK = add(CHILD, "mochila de ursinho", Slot.BACK, Who.ANY, p(Anchor.BODY, false,
      b(-2.6f, 2.2f, 2.3f, 5.2f, 5.4f, 2.6f, 0xA1785C), b(-2.1f, 0.0f, 2.4f, 4.2f, 2.6f, 2.2f, 0xA1785C), b(-2.4f, -0.6f, 2.8f, 1.2f, 1f, 1f, 0xA1785C),
      b(1.2f, -0.6f, 2.8f, 1.2f, 1f, 1f, 0xA1785C), b(-0.7f, 1.2f, 4.6f, 1.4f, 1f, 0.3f, TAN), b(-3f, 0f, -2.4f, 0.9f, 6f, 0.3f, DARK_BROWN),
      b(2.1f, 0f, -2.4f, 0.9f, 6f, 0.3f, DARK_BROWN)));

    // waist (3)
    public static final Accessory TUTU = add(CHILD, "saia de tutu", Slot.WAIST, Who.GIRLS,
      p(Anchor.BODY, true, b(-5f, 9.4f, -3f, 10f, 1.4f, 6f, T), b(-5.6f, 10.4f, -3.6f, 11.2f, 1f, 7.2f, T)));
    public static final Accessory SWORD_BELT = add(CHILD, "cinto com espada de madeira", Slot.WAIST, Who.ANY, p(Anchor.BODY, false,
      b(-4.35f, 10f, -2.35f, 8.7f, 0.8f, 4.7f, STRAP), b(-0.6f, 10f, -2.5f, 1.2f, 0.8f, 0.2f, GOLD), b(4.3f, 9.6f, -1.2f, 0.8f, 0.5f, 2.4f, BROWN),
      b(4.4f, 10.1f, -0.4f, 0.6f, 6f, 0.8f, WOOD), b(4.45f, 8.4f, -0.3f, 0.5f, 1.2f, 0.6f, DARK_BROWN)));
    public static final Accessory FANNY_PACK = add(CHILD, "pochete", Slot.WAIST, Who.ANY,
      p(Anchor.BODY, false, b(-4.35f, 10.2f, -2.35f, 8.7f, 0.6f, 4.7f, BLACK)), p(Anchor.BODY, true, b(-1.8f, 9.6f, -3.4f, 3.6f, 1.8f, 1.2f, T)));

    // in the hand (6)
    public static final Accessory LOLLIPOP = add(CHILD, "pirulito", Slot.HAND, Who.ANY,
      p(Anchor.RIGHT_ARM, false, b(-1.2f, 9.6f, -3.6f, 0.4f, 0.4f, 3.2f, 0xFFFFFF)), p(Anchor.RIGHT_ARM, true, b(-2.2f, 8.4f, -5.0f, 2.2f, 2.2f, 0.5f, T)));
    public static final Accessory BALLOON = add(CHILD, "balão", Slot.HAND, Who.ANY,
      p(Anchor.RIGHT_ARM, false, b(-1.1f, -26f, -0.1f, 0.2f, 36f, 0.2f, 0xFFFFFF)),
      p(Anchor.RIGHT_ARM, true, b(-3f, -32f, -2f, 4f, 5f, 4f, T), b(-1.3f, -27f, -0.3f, 0.6f, 0.8f, 0.6f, T)));
    public static final Accessory PINWHEEL = add(CHILD, "cata-vento", Slot.HAND, Who.ANY,
      p(Anchor.RIGHT_ARM, false, b(-1.2f, 9.6f, -5.6f, 0.4f, 0.4f, 5f, WOOD)),
      piece(Anchor.RIGHT_ARM, false, Motion.SPIN_Z, -1.0f, 9.8f, -5.8f, b(0f, -1.6f, -0.1f, 1.6f, 1.6f, 0.2f, RED), b(-1.6f, -1.6f, -0.1f, 1.6f, 1.6f, 0.2f, BLUE),
        b(-1.6f, 0f, -0.1f, 1.6f, 1.6f, 0.2f, YELLOW), b(0f, 0f, -0.1f, 1.6f, 1.6f, 0.2f, 0x43A047)));
    public static final Accessory WOODEN_SWORD = add(CHILD, "espadinha de madeira", Slot.HAND, Who.ANY, p(Anchor.RIGHT_ARM, false,
      b(-1.4f, 9.2f, -1.6f, 0.8f, 0.8f, 2f, DARK_BROWN), b(-2.2f, 9.0f, -2.2f, 2.4f, 1.2f, 0.6f, BROWN), b(-1.3f, 9.3f, -9f, 0.6f, 1.0f, 6.8f, WOOD)));
    public static final Accessory STAR_WAND = add(CHILD, "varinha de estrela", Slot.HAND, Who.ANY, p(Anchor.RIGHT_ARM, false,
      b(-1.2f, 9.6f, -5f, 0.4f, 0.4f, 4.6f, 0xFFFFFF), b(-2.3f, 8.5f, -5.6f, 2.6f, 2.6f, 0.5f, 0xFFD740)));
    public static final Accessory BUTTERFLY_NET = add(CHILD, "rede de borboleta", Slot.HAND, Who.ANY, p(Anchor.RIGHT_ARM, false,
      b(-1.2f, 9.6f, -10f, 0.4f, 0.4f, 9.6f, WOOD), b(-2.8f, 7.4f, -12.0f, 3.2f, 3.2f, 0.3f, 0xFFFFFF), b(-2.5f, 7.7f, -11.7f, 2.6f, 2.6f, 1.6f, 0xE0F7FA)));

    // ------------------------------------------------------------------------------------------------------------------
    // For everyone, depending on the moment
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * A colourful kids' band-aid (the strip takes a bright colour, the pad is white) low on the cheek, next to the mouth:
     * the skin-coloured one up by the eye looked like a stray bit of skin. Modelled for a mouth on face row 6.
     */
    public static final Accessory BAND_AID_CHEEK = new Accessory("curativo", Slot.FACE,
      List.of(p(Anchor.HEAD, true, b(1.6f, -1.85f, -4.7f, 2.4f, 0.7f, 0.25f, T)),
              p(Anchor.HEAD, false, b(2.4f, -1.95f, -4.8f, 0.8f, 0.9f, 0.15f, OFF_WHITE))), Who.ANY);

    // more of them the more hurt a child is (AccessoryLayer): funny, but a bit worrying. The face ones stand in front of
    // the skin's hair/hat layer (half a pixel out from the head), or the hair hides them

    /** The other cheek, a little higher. */
    public static final Accessory BAND_AID_CHEEK_LEFT = new Accessory("curativo 2", Slot.FACE,
      List.of(p(Anchor.HEAD, true, b(-4.0f, -2.35f, -4.7f, 2.4f, 0.7f, 0.25f, T)),
              p(Anchor.HEAD, false, b(-3.2f, -2.45f, -4.8f, 0.8f, 0.9f, 0.15f, OFF_WHITE))), Who.ANY);

    /** Above the eye, two strips crossing. */
    public static final Accessory BAND_AID_FOREHEAD = new Accessory("curativo na testa", Slot.FACE,
      List.of(p(Anchor.HEAD, true, b(0.6f, -6.4f, -4.7f, 2.6f, 0.6f, 0.25f, T), b(1.6f, -7.0f, -4.75f, 0.6f, 1.8f, 0.25f, T)),
              p(Anchor.HEAD, false, b(1.5f, -6.5f, -4.85f, 0.8f, 0.8f, 0.15f, OFF_WHITE))), Who.ANY);

    /** Across the bridge of the nose, between the eyes and the mouth. Modelled for a mouth on face row 6. */
    public static final Accessory BAND_AID_NOSE = new Accessory("curativo no nariz", Slot.FACE,
      List.of(p(Anchor.HEAD, true, b(-1.3f, -3.15f, -4.7f, 2.6f, 0.55f, 0.25f, T)),
              p(Anchor.HEAD, false, b(-0.4f, -3.25f, -4.8f, 0.8f, 0.75f, 0.15f, OFF_WHITE))), Who.ANY);

    /** Round the upper arm. */
    public static final Accessory BAND_AID_ARM = new Accessory("curativo no braço", Slot.HANDS,
      List.of(p(Anchor.LEFT_ARM, true, b(-1.35f, 3.5f, -2.35f, 4.7f, 1.1f, 4.7f, T)),
              p(Anchor.LEFT_ARM, false, b(0.3f, 3.4f, -2.5f, 1.4f, 1.3f, 0.2f, OFF_WHITE))), Who.ANY);

    /** A cross on the knee. */
    public static final Accessory BAND_AID_KNEE = new Accessory("curativo no joelho", Slot.FEET,
      List.of(p(Anchor.RIGHT_LEG, true, b(-1.6f, 5.2f, -2.4f, 3.2f, 0.8f, 0.3f, T), b(-0.4f, 4.4f, -2.45f, 0.8f, 2.4f, 0.3f, T)),
              p(Anchor.RIGHT_LEG, false, b(-0.5f, 5.1f, -2.55f, 1.0f, 1.0f, 0.15f, OFF_WHITE))), Who.ANY);

    /** Really hurt: a bandage wrapped round the head (outside the hair layer), knot at the back, a little red spot. */
    public static final Accessory HEAD_BANDAGE = new Accessory("faixa na cabeça", Slot.HEAD,
      List.of(p(Anchor.HEAD, false,
        b(-4.7f, -6.7f, -4.7f, 9.4f, 1.4f, 9.4f, OFF_WHITE),
        b(-0.6f, -6.9f, 4.65f, 1.2f, 1.2f, 0.8f, OFF_WHITE),
        b(1.2f, -6.5f, -4.8f, 1.0f, 0.9f, 0.1f, 0xE57373))), Who.ANY);

    private static final int[] BAND_AID_COLORS = {0xF06292, 0x4FC3F7, 0xFFD54F, 0x81C784, 0xBA68C8, 0xFF8A65};

    /** The band-aid's colour, always the same for one child. */
    public static int bandAidColor(final int seed)
    {
        return BAND_AID_COLORS[Math.floorMod(seed * 31 + 7, BAND_AID_COLORS.length)];
    }

    public static final Accessory NIGHTCAP = new Accessory("touca de dormir", Slot.HEAD, List.of(
      p(Anchor.HEAD, true, k(-4.8f, -9.1f, -4.8f, 9.6f, 3.0f, 9.6f, T), k(-3.4f, -11.3f, -2.6f, 6.8f, 2.4f, 6.8f, T), k(-2.2f, -12.7f, 0f, 4.4f, 1.6f, 4.4f, T)),
      p(Anchor.HEAD, false, k(-5.05f, -6.4f, -5.05f, 10.1f, 1.0f, 10.1f, OFF_WHITE), k(-1.1f, -13.1f, 3.1f, 2.2f, 2.2f, 2.2f, 0xFFFFFF))), Who.ANY);

    public static final Accessory SANTA_HAT = new Accessory("gorro de Papai Noel", Slot.HEAD, List.of(
      p(Anchor.HEAD, false, k(-4.8f, -9.1f, -4.8f, 9.6f, 3.0f, 9.6f, SANTA_RED), k(-3.4f, -11.3f, -2.6f, 6.8f, 2.4f, 6.8f, SANTA_RED),
        k(-2.2f, -12.7f, 0f, 4.4f, 1.6f, 4.4f, SANTA_RED), k(-5.05f, -6.6f, -5.05f, 10.1f, 1.6f, 10.1f, 0xFFFFFF), k(-1.1f, -13.1f, 3.1f, 2.2f, 2.2f, 2.2f, 0xFFFFFF))),
      Who.ANY);

    private Accessories() {}

    /** December, by the real calendar (like MineColonies' own Halloween look). */
    public static boolean santaSeason()
    {
        return LocalDate.now().getMonth() == Month.DECEMBER || Showcase.forceSanta;
    }

    private static Piece piece(final Anchor anchor, final boolean tinted, final Motion motion, final float px, final float py, final float pz,
                               final Box... boxes)
    {
        final CubeListBuilder cubes = CubeListBuilder.create();
        for (final Box box : boxes)
        {
            // region of this box in the atlas: the cube's UV layout is (2d + 2w) x (d + h)
            final int w = (int) Math.ceil(2 * box.d() + 2 * box.w()) + 1;
            final int h = (int) Math.ceil(box.d() + box.h()) + 1;
            if (cursorX + w > ATLAS_W)
            {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            FILLS.add(new int[] {cursorX, cursorY, w, h, box.color(), box.knit() ? 1 : 0});
            cubes.texOffs(cursorX, cursorY).addBox(box.x(), box.y(), box.z(), box.w(), box.h(), box.d());
            cursorX += w;
            rowHeight = Math.max(rowHeight, h);
        }
        final MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("piece", cubes, PartPose.offset(px, py, pz));
        return new Piece(anchor, LayerDefinition.create(mesh, ATLAS_W, ATLAS_H).bakeRoot().getChild("piece"), tinted, motion);
    }

    /** Draws the atlas once (render thread). */
    public static void ensureUploaded()
    {
        if (uploaded)
        {
            return;
        }
        uploaded = true;
        final NativeImage image = new NativeImage(NativeImage.Format.RGBA, ATLAS_W, ATLAS_H, true);
        final Random rand = new Random(7);
        for (final int[] fill : FILLS)
        {
            for (int y = 0; y < fill[3]; y++)
            {
                for (int x = 0; x < fill[2]; x++)
                {
                    float light = 0.94f + rand.nextFloat() * 0.08f;
                    if (fill[5] == 1)
                    {
                        // knitted: ribs every other column, a stitch row every third
                        light *= (x % 2 == 0 ? 1.0f : 0.9f) * (y % 3 == 0 ? 0.95f : 1.0f);
                    }
                    image.setPixelRGBA(fill[0] + x, fill[1] + y, abgr(fill[4], light));
                }
            }
        }
        Minecraft.getInstance().getTextureManager().register(ATLAS, new DynamicTexture(image));
    }

    private static int abgr(final int rgb, final float light)
    {
        final int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * light));
        final int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * light));
        final int b = Math.min(255, Math.round((rgb & 0xFF) * light));
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Outfits
    // ------------------------------------------------------------------------------------------------------------------

    /** What one baby or child wears today; null slots are empty. */
    public record Outfit(Accessory head, Accessory face, Accessory neck, Accessory back, Accessory waist, Accessory hand, Accessory hands,
                         Accessory feet, int seed) {}

    private record CacheKey(int entity, long day, boolean infant, boolean pupil) {}

    private static final java.util.Map<CacheKey, Outfit> OUTFITS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Today's outfit of this baby or child: picked from its id and the day, so it is the same all day and a new one
     * tomorrow.
     */
    public static Outfit outfit(final int entityId, final java.util.UUID uuid, final boolean female, final boolean infant, final boolean pupil, final long day)
    {
        if (OUTFITS.size() > 512)
        {
            OUTFITS.clear();
        }
        return OUTFITS.computeIfAbsent(new CacheKey(entityId, day, infant, pupil), key -> {
            final int seed = uuid.hashCode() * 31 + (int) day * 7919;
            final Random rand = new Random(seed);
            if (infant)
            {
                return new Outfit(
                  pick(rand, BABY, Slot.HEAD, female, 100),
                  rand.nextInt(100) < 65 ? PACIFIER : null,
                  pick(rand, BABY, Slot.NECK, female, 75),
                  pick(rand, BABY, Slot.BACK, female, 15),
                  null,
                  pick(rand, BABY, Slot.HAND, female, 80),
                  pick(rand, BABY, Slot.HANDS, female, 25),
                  pick(rand, BABY, Slot.FEET, female, 50),
                  seed);
            }
            final Accessory back = pupil ? BACKPACK : pickExcept(rand, CHILD, Slot.BACK, female, 25, BACKPACK);
            return new Outfit(
              pick(rand, CHILD, Slot.HEAD, female, 70),
              pick(rand, CHILD, Slot.FACE, female, 25),
              pick(rand, CHILD, Slot.NECK, female, 35),
              back,
              pick(rand, CHILD, Slot.WAIST, female, 20),
              pick(rand, CHILD, Slot.HAND, female, 30),
              null,
              null,
              seed);
        });
    }

    private static Accessory pick(final Random rand, final List<Accessory> from, final Slot slot, final boolean female, final int chance)
    {
        return pickExcept(rand, from, slot, female, chance, null);
    }

    private static Accessory pickExcept(final Random rand, final List<Accessory> from, final Slot slot, final boolean female, final int chance,
                                        final Accessory except)
    {
        final int roll = rand.nextInt(100);
        final List<Accessory> options = new ArrayList<>();
        for (final Accessory accessory : from)
        {
            if (accessory.slot() == slot && accessory != except && accessory != PACIFIER
                  && (accessory.who() == Who.ANY || (accessory.who() == Who.GIRLS) == female))
            {
                options.add(accessory);
            }
        }
        final int index = rand.nextInt(Math.max(1, options.size()));
        return roll < chance && !options.isEmpty() ? options.get(index) : null;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------------------------------------------------------

    /** The baby's onesie colour (same palette as the generated baby skins: by gender and skin number). */
    public static int babyColor(final boolean female, final int textureId)
    {
        final int[][] palette = {{0xF4B6C8, 0xCDB8E8, 0xA8E6E0}, {0xA9CFF0, 0xB6E3C6, 0xF3E3A1}};
        return palette[female ? 0 : 1][Math.floorMod(textureId, 3)];
    }

    private static final int[] BABY_ACCENTS = {0xFFF3D6, 0xFFD1DC, 0xFFE9A8, 0xC9F2E1, 0xD9C8F5, 0xFFFFFF};

    /**
     * A baby's scarf, mittens, booties and blanket: a soft colour that is not its onesie's (in the onesie colour they
     * disappeared into it).
     */
    public static int babyAccent(final int seed, final int onesie)
    {
        for (int i = 0; i < BABY_ACCENTS.length; i++)
        {
            final int accent = BABY_ACCENTS[Math.floorMod(seed + i, BABY_ACCENTS.length)];
            if (accent != onesie)
            {
                return accent;
            }
        }
        return 0xFFFFFF;
    }

    private static final int[] KID_COLORS     = {0xF06292, 0xE53935, 0x64B5F6, 0xFFD54F, 0xBA68C8, 0x4DB6AC, 0xFF8A65, 0x81C784, 0x1565C0, 0x6A1B9A};
    private static final int[] NIGHTCAP_COLORS = {0x5C7CC9, 0x7E57C2, 0x43A047, 0xE57373};

    /** A child's colour for one slot (each slot its own, so a cap and a scarf don't have to match). */
    public static int kidColor(final int seed, final Slot slot)
    {
        return KID_COLORS[Math.floorMod(seed * 7 + slot.ordinal() * 13, KID_COLORS.length)];
    }

    public static int nightcapColor(final int seed)
    {
        return NIGHTCAP_COLORS[Math.floorMod(seed * 5 + 1, NIGHTCAP_COLORS.length)];
    }

    /** Every accessory, for the development fashion show. */
    public static List<Accessory> all()
    {
        final List<Accessory> all = new ArrayList<>(BABY);
        all.addAll(CHILD);
        return all;
    }
}
