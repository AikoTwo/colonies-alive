package com.aiko.coloniesalive.server;

import com.minecolonies.core.colony.jobs.JobPupil;
import com.minecolonies.core.colony.jobs.JobTeacher;
import com.minecolonies.core.entity.ai.workers.AbstractAISkeleton;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.core.entity.citizen.EntityCitizen;

/**
 * School rules. MineColonies sends pupils to school only until noon, and also lets them leave whenever they get
 * "leisure time" or it rains. With this addon, every child registered at a school attends a full school day, from
 * early morning (game time 0, 6:00) until noon ({@link #END}, 12:00), rain or not - unless the child skips
 * school (see {@link ChildLifeAI#isTruantToday()}), which earns a punishment at home afterwards.
 *
 * <p>The rules are applied inside MineColonies' citizen AI by {@code CitizenAIMixin}.
 */
public final class School
{
    /** End of the school day, in game time of day (0 = 6:00, 6000 = 12:00). After that the children are free. */
    public static final long END = 6000;

    private School() {}

    public static boolean isPupil(final EntityCitizen citizen)
    {
        return citizen.isBaby() && citizen.getCitizenData() != null && citizen.getCitizenData().getJob() instanceof JobPupil;
    }

    public static boolean isTeacher(final EntityCitizen citizen)
    {
        return citizen.getCitizenData() != null && citizen.getCitizenData().getJob() instanceof JobTeacher;
    }

    public static boolean isSchoolTime(final EntityCitizen citizen)
    {
        return citizen.level().getDayTime() % 24000 <= END;
    }

    /** A pupil who should be in class right now (not skipping). */
    public static boolean inClass(final EntityCitizen citizen)
    {
        if (!isPupil(citizen) || !isSchoolTime(citizen))
        {
            return false;
        }
        final ChildLifeAI ai = ChildLifeEvents.get(citizen);
        return ai == null || !ai.isTruantToday();
    }

    /**
     * Replaces MineColonies' "pupils go home after noon": the time of day after which the pupil is free.
     * A child skipping school is free all day.
     */
    public static long freeAfter(final EntityCitizen citizen)
    {
        final ChildLifeAI ai = ChildLifeEvents.get(citizen);
        return ai != null && ai.isTruantToday() ? -1 : END;
    }

    /** What the pupil's school AI is doing (study, recess, ...), or null. */
    public static IAIState pupilState(final EntityCitizen citizen)
    {
        if (!isPupil(citizen) || citizen.getCitizenData().getJob().getWorkerAI() == null)
        {
            return null;
        }
        return citizen.getCitizenData().getJob().getWorkerAI() instanceof AbstractAISkeleton<?> ai ? ai.getState() : null;
    }

    public static boolean inRecess(final EntityCitizen citizen)
    {
        return pupilState(citizen) == AIWorkerState.RECESS;
    }

    /** Same school (work building) for both pupils. */
    public static boolean sameSchool(final EntityCitizen a, final EntityCitizen b)
    {
        return a.getCitizenData() != null && b.getCitizenData() != null && a.getCitizenData().getWorkBuilding() != null
                 && a.getCitizenData().getWorkBuilding() == b.getCitizenData().getWorkBuilding();
    }
}
