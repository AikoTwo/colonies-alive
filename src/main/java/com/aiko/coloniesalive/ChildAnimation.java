package com.aiko.coloniesalive;

/**
 * Poses a child can be in. The id is what travels over the network, so only append new entries.
 */
public enum ChildAnimation
{
    NONE,
    /** Hops up and down with arms in the air. */
    HOP_JOY,
    /** Spins around with arms out. */
    SPIN,
    /** Sits on the ground, looking around. */
    DAYDREAM,
    /** Crouches down and draws in the dirt with a stick. */
    CROUCH_LOOK,
    /** Two children facing each other, clapping hands (pat-a-cake). */
    PATTY_CAKE,
    /** Tag: the one chasing, arms reaching forward. */
    TAG_CHASER,
    /** Tag: the one running away, arms pumping. */
    TAG_RUNNER,
    /** Dancing, arms swaying above the head. */
    DANCE,
    /** Ring dance: holding hands with the neighbours. */
    CIRANDA,
    /** Hugging a friend. */
    HUG,
    /** Hand next to the mouth, telling a secret. */
    WHISPER,
    /** Hands on the belly, shaking with laughter. */
    GIGGLE,
    /** Running after an animal, arms out. */
    CHASE_ANIMAL,
    /** Crouching and petting an animal. */
    PET_ANIMAL,
    /** Crouching and picking a flower. */
    PICK_FLOWER,
    /** Holding out a flower to someone. */
    GIVE_FLOWER,
    /** Splashing in the water. */
    SPLASH,
    /** Waving hello. */
    WAVE,
    /** Follow the leader: marching with big arm swings. */
    MARCH,
    /** Follow the leader: the leader, pointing the way. */
    LEADER,
    /** Crying, rubbing the eyes. */
    CRY,
    /** Picked up and loving it: arms up, legs kicking. */
    GRAB_WHEE,
    /** Picked up and scared: flailing. */
    GRAB_SCARED,
    /** Picked up and annoyed: arms crossed, looking away. */
    GRAB_GRUMPY,
    /** Picked up and shy: hands on the face, peeking. */
    GRAB_SHY,
    /** Picked up and relaxed: legs dangling and swinging. */
    GRAB_CALM,
    /** Put down after being annoyed: arms crossed, pouting. */
    POUT,
    /** Talking, one hand gesturing. */
    TALK,
    /** Listening, nodding now and then. */
    LISTEN,
    /** Arguing: hand on the hip, pointing at the other one. */
    ARGUE,
    /** Saying sorry: head down, hands behind the back. */
    SORRY,
    /** Sad: shoulders down, head hanging. */
    SAD,
    /** In class: reading, paper held in front, head down. */
    READ,
    /** In class: writing, one hand moving. */
    WRITE,
    /** In class: hand up to answer. */
    RAISE_HAND,
    /** In class: dozing off, head nodding. */
    DOZE,
    /** Getting back at the player who hit it: a flurry of little punches, stomping (no damage). */
    KID_PUNCH,
    /** Adult carrying a child in front of the chest, both arms under it. */
    CARRY_CHILD,
    /** Baby sitting on the floor, shaking its little arms and looking around. */
    BABY_SIT,
    /** Baby toddling: arms up and out for balance, wobbling from side to side. */
    BABY_TODDLE,
    /** Baby clapping its hands, delighted. */
    BABY_CLAP,
    /** Baby crawling on hands and knees (the whole body is laid down by the renderer, see BabyBodyRender). */
    BABY_CRAWL,
    /** Baby getting up from the floor, very slowly ({@link #BABY_RISE_TICKS}), wobbling. */
    BABY_RISE,
    /** An older child playing peekaboo with a baby: hands over the face, then "boo!". */
    PEEKABOO,
    /** Baby held by a player: sitting in the arms, little legs kicking, arms reaching out to them. */
    BABY_HELD,
    /** Baby held while crying: sitting in the arms, fists rubbing the eyes. */
    BABY_HELD_CRY;

    /** How long a baby takes to get up ({@link #BABY_RISE}). */
    public static final int BABY_RISE_TICKS = 70;

    private static final ChildAnimation[] BY_ID = values();

    public int id()
    {
        return ordinal();
    }

    public static ChildAnimation byId(final int id)
    {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : NONE;
    }
}
