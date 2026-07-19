package org.telegram.messenger.utils;

import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;

import java.util.LinkedHashSet;
import java.util.Set;

import me.vkryl.core.reference.ReferenceList;

/**
 * A thin wrapper around Android {@link Choreographer} that delivers animation
 * callbacks at a stable ~60 fps regardless of the display refresh rate.
 *
 * <p>Callbacks with the same fps share a single deadline — they always fire
 * on the same tick, minimising the number of screen invalidations.
 *
 * <p>Must be used on the main thread only.
 */
public final class Choreographer60FpsContent implements Choreographer.FrameCallback {

    // ── Target frame rate ─────────────────────────────────────────────────────

    /** Desired animation rate, fps. */
    private static final int  TARGET_FPS        = 60;

    /** Duration of one target frame in nanoseconds (~16.67 ms). */
    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS;

    private static final long FRAME_INTERVAL_30_FPS_NS = 1_000_000_000L / 30;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static Choreographer60FpsContent sInstance;

    public static Choreographer60FpsContent getInstance() {
        checkMainThread();
        if (sInstance == null) {
            sInstance = new Choreographer60FpsContent();
        }
        return sInstance;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Choreographer mChoreographer = Choreographer.getInstance();

    /** One-shot callbacks — fired once on the next frame, then cleared. */
    private final Set<FrameCallback> mOneShot = new LinkedHashSet<>();

    /**
     * Persistent callback groups keyed by interval in nanoseconds.
     * All callbacks in the same group share one deadline and always fire together,
     * so N animations at the same fps produce exactly one invalidate wave per period.
     */
    private final SparseArray<CallbackGroup> mGroups = new SparseArray<>();

    private final ReferenceList<Drawable> mDrawablesToInvalidate      = new ReferenceList<>();
    private final ReferenceList<Drawable> mDrawablesToInvalidate30fps = new ReferenceList<>();
    private final ReferenceList<View>     mViewsToInvalidate          = new ReferenceList<>();

    private long mNextFrameNs;
    private long mNext30FpsFrameNs;
    private boolean mScheduled;
    private long mScheduledForNs = Long.MAX_VALUE;

    // ── Public interface ──────────────────────────────────────────────────────

    /** Callback interface, mirrors {@link Choreographer.FrameCallback}. */
    public interface FrameCallback {
        /**
         * Called at the requested fps.
         *
         * @param frameTimeNanos frame start time in {@link System#nanoTime()} domain, ns
         */
        void doFrame(long frameTimeNanos);
    }

    /**
     * Schedules a one-shot callback for the next ~60 fps frame.
     * Removed automatically after it fires.
     */
    public void post(FrameCallback callback) {
        checkMainThread();
        mOneShot.add(callback);
        schedule();
    }

    public void postInvalidateDrawable(Drawable drawable) {
        checkMainThread();
        mDrawablesToInvalidate.add(drawable);
        schedule();
    }

    public void postInvalidateDrawable30fps(Drawable drawable) {
        checkMainThread();
        mDrawablesToInvalidate30fps.add(drawable);
        scheduleNext();
    }

    public void postInvalidateView(View view) {
        checkMainThread();
        mViewsToInvalidate.add(view);
        schedule();
    }

    /**
     * Subscribes a persistent callback at ~60 fps.
     */
    public void addFrameCallback(FrameCallback callback) {
        addFrameCallback(callback, TARGET_FPS);
    }

    /**
     * Subscribes a persistent Runnable callback at ~60 fps.
     */
    public void addFrameCallback(Runnable callback) {
        addFrameCallback(callback, TARGET_FPS);
    }

    public void addFrameCallbackOnce(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallbackOnce(callback); // remove from any existing group first
        CallbackGroup group = getOrCreateGroup(fps);
        if (group.runnableCallbacksOnce == null) {
            group.runnableCallbacksOnce = new ReferenceList<>();
        }
        group.runnableCallbacksOnce.add(callback);
        scheduleNext();
    }


    /**
     * Subscribes a persistent Runnable callback at the given fps.
     *
     * @param callback the {@link Runnable} to run on each tick
     * @param fps      desired rate in frames per second; clamped to [1, TARGET_FPS]
     */
    public void addFrameCallback(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallback(callback); // remove from any existing group first
        getOrCreateGroup(fps).runnableCallbacks.add(callback);
        scheduleNext();
    }

    /**
     * Subscribes a persistent callback at the given fps.
     *
     * <p>Callbacks sharing the same fps value share a single deadline and
     * are guaranteed to fire on the same tick — this minimises screen invalidations
     * when multiple animations run at the same rate.
     *
     * @param callback the {@link FrameCallback} to register
     * @param fps      desired rate in frames per second; clamped to [1, TARGET_FPS]
     */
    public void addFrameCallback(FrameCallback callback, int fps) {
        checkMainThread();
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallback(callback); // remove from any existing group first
        getOrCreateGroup(fps).callbacks.add(callback);
        scheduleNext();
    }

    /**
     * Unsubscribes a persistent Runnable callback from whichever group it belongs to.
     * Empty groups are removed automatically.
     */
    public void removeFrameCallback(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacks.remove(callback)) {
                return;
            }
        }
    }

    public void removeFrameCallbackOnce(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacksOnce != null && group.runnableCallbacksOnce.remove(callback)) {
                return;
            }
        }
    }

    /**
     * Unsubscribes a persistent callback from whichever group it belongs to.
     * Empty groups are removed automatically.
     */
    public void removeFrameCallback(FrameCallback callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.callbacks.remove(callback)) {
                return;
            }
        }
    }

    // ── Private implementation ────────────────────────────────────────────────

    private Choreographer60FpsContent() {}

    @Override
    public void doFrame(long frameTimeNanos) {
        mScheduled = false;
        mScheduledForNs = Long.MAX_VALUE;
        dispatchFrame(frameTimeNanos);

        if (hasPendingWork()) {
            scheduleNext();
        }
    }

    private void schedule() {
        scheduleAt(nextDeadline(mNextFrameNs, System.nanoTime()));
    }

    private void scheduleNext() {
        final long now = System.nanoTime();
        long deadline = Long.MAX_VALUE;

        if (!mOneShot.isEmpty() || !mDrawablesToInvalidate.isEmpty() || !mViewsToInvalidate.isEmpty()) {
            deadline = nextDeadline(mNextFrameNs, now);
        }
        if (!mDrawablesToInvalidate30fps.isEmpty()) {
            deadline = Math.min(deadline, nextDeadline(mNext30FpsFrameNs, now));
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (!group.isEmpty()) {
                deadline = Math.min(deadline, nextDeadline(group.nextFrameNs, now));
            }
        }

        if (deadline != Long.MAX_VALUE) {
            scheduleAt(deadline);
        }
    }

    private void scheduleAt(long deadlineNs) {
        if (mScheduled && deadlineNs >= mScheduledForNs) {
            return;
        }
        if (mScheduled) {
            mChoreographer.removeFrameCallback(this);
        }

        final long delayNs = deadlineNs - System.nanoTime();
        mScheduled = true;
        mScheduledForNs = deadlineNs;
        if (delayNs <= 0) {
            mChoreographer.postFrameCallback(this);
        } else {
            mChoreographer.postFrameCallbackDelayed(this, delayNs / 1_000_000L);
        }
    }

    private boolean hasPendingWork() {
        for (int i = mGroups.size() - 1; i >= 0; i--) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.isEmpty()) {
                mGroups.removeAt(i);
            }
        }
        return mGroups.size() != 0
                || !mOneShot.isEmpty()
                || !mDrawablesToInvalidate.isEmpty()
                || !mDrawablesToInvalidate30fps.isEmpty()
                || !mViewsToInvalidate.isEmpty();
    }

    private void dispatchFrame(long frameTimeNanos) {
        // Dispatch grouped persistent callbacks that reached their shared deadline.
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (isDue(group.nextFrameNs, frameTimeNanos)) {
                group.nextFrameNs = advanceDeadline(group.nextFrameNs, group.intervalNs, frameTimeNanos);
                if (group.runnableCallbacksOnce != null) {
                    ReferenceList<Runnable> referenceList = group.runnableCallbacksOnce;
                    group.runnableCallbacksOnce = null;
                    for (Runnable runnable : referenceList) {
                        runnable.run();
                    }
                }

                for (FrameCallback cb : group.callbacks) {
                    cb.doFrame(frameTimeNanos);
                }
                for (Runnable runnable : group.runnableCallbacks) {
                    runnable.run();
                }
            }
        }

        if (isDue(mNextFrameNs, frameTimeNanos)) {
            mNextFrameNs = advanceDeadline(mNextFrameNs, FRAME_INTERVAL_NS, frameTimeNanos);

            // One-shot callbacks.
            for (FrameCallback cb : mOneShot) {
                cb.doFrame(frameTimeNanos);
            }

            // View / drawable invalidations.
            for (View view : mViewsToInvalidate) {
                view.invalidate();
            }
            for (Drawable drawable : mDrawablesToInvalidate) {
                drawable.invalidateSelf();
            }
            mViewsToInvalidate.clear();
            mDrawablesToInvalidate.clear();
            mOneShot.clear();
        }

        // Legacy 30fps drawables.
        if (isDue(mNext30FpsFrameNs, frameTimeNanos)) {
            mNext30FpsFrameNs = advanceDeadline(mNext30FpsFrameNs, FRAME_INTERVAL_30_FPS_NS, frameTimeNanos);
            for (Drawable drawable : mDrawablesToInvalidate30fps) {
                drawable.invalidateSelf();
            }
            mDrawablesToInvalidate30fps.clear();
        }
    }

    private static boolean isDue(long deadlineNs, long frameTimeNanos) {
        return deadlineNs == 0 || frameTimeNanos >= deadlineNs;
    }

    private static long nextDeadline(long deadlineNs, long nowNs) {
        return deadlineNs == 0 || deadlineNs <= nowNs ? nowNs : deadlineNs;
    }

    private static long advanceDeadline(long deadlineNs, long intervalNs, long frameTimeNanos) {
        if (deadlineNs == 0) {
            return frameTimeNanos + intervalNs;
        }
        return deadlineNs + ((frameTimeNanos - deadlineNs) / intervalNs + 1) * intervalNs;
    }

    private CallbackGroup getOrCreateGroup(int fps) {
        CallbackGroup group = mGroups.get(fps);
        if (group == null) {
            long intervalNs = 1_000_000_000L / fps;
            group = new CallbackGroup(intervalNs);
            mGroups.put(fps, group);
        }
        return group;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * A group of callbacks sharing the same tick interval.
     *
     * <p>All members of the same group share a deadline and fire in unison.
     *
     * CopyOnWriteArrayList allows safe removal during iteration (e.g. from doFrame).
     */
    private static final class CallbackGroup {
        final long intervalNs;
        long nextFrameNs;

        final ReferenceList<FrameCallback> callbacks = new ReferenceList<>();
        final ReferenceList<Runnable> runnableCallbacks = new ReferenceList<>();
        @Nullable
        ReferenceList<Runnable> runnableCallbacksOnce;

        CallbackGroup(long intervalNs) {
            this.intervalNs = intervalNs;
        }

        boolean isEmpty() {
            return callbacks.isEmpty()
                    && runnableCallbacks.isEmpty()
                    && (runnableCallbacksOnce == null || runnableCallbacksOnce.isEmpty());
        }
    }

    private static void checkMainThread() {
        if (BuildVars.DEBUG_PRIVATE_VERSION || BuildVars.DEBUG_VERSION) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Choreographer60FpsContent must be used on the main thread");
            }
        }
    }
}
