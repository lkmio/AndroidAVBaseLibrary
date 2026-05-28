package com.github.lkmio.androidavbaselibrary.utils;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A lightweight object pool inspired by Go's sync.Pool.
 *
 * <p>Each thread keeps one fast-path cached object, and the rest fall back to a
 * shared queue. Objects may be dropped when the pool is full.</p>
 */
public final class SyncPool<T> {

    public interface Factory<T> {
        T create();
    }

    public interface Resetter<T> {
        void reset(T object);
    }

    private final Factory<T> mFactory;
    private final Resetter<T> mResetter;
    private final int mMaxSharedSize;
    private final ThreadLocal<T> mLocalCache = new ThreadLocal<>();
    private final ConcurrentLinkedQueue<T> mSharedCache = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mSharedSize = new AtomicInteger();

    public SyncPool(Factory<T> factory) {
        this(factory, null, 32);
    }

    public SyncPool(Factory<T> factory, Resetter<T> resetter) {
        this(factory, resetter, 32);
    }

    public SyncPool(Factory<T> factory, Resetter<T> resetter, int maxSharedSize) {
        if (factory == null) {
            throw new NullPointerException("factory == null");
        }
        if (maxSharedSize < 0) {
            throw new IllegalArgumentException("maxSharedSize must >= 0");
        }
        mFactory = factory;
        mResetter = resetter;
        mMaxSharedSize = maxSharedSize;
    }

    public T get() {
        T object = mLocalCache.get();
        if (object != null) {
            mLocalCache.set(null);
            return object;
        }

        object = mSharedCache.poll();
        if (object != null) {
            decrementSharedSize();
            return object;
        }

        return mFactory.create();
    }

    public void put(T object) {
        if (object == null) {
            return;
        }

        if (mResetter != null) {
            mResetter.reset(object);
        }

        if (mLocalCache.get() == null) {
            mLocalCache.set(object);
            return;
        }

        while (true) {
            int size = mSharedSize.get();
            if (size >= mMaxSharedSize) {
                return;
            }
            if (mSharedSize.compareAndSet(size, size + 1)) {
                mSharedCache.offer(object);
                return;
            }
        }
    }

    public void clear() {
        mLocalCache.remove();
        while (mSharedCache.poll() != null) {
            // Drain the queue.
        }
        mSharedSize.set(0);
    }

    public int size() {
        int size = mSharedSize.get();
        if (mLocalCache.get() != null) {
            size++;
        }
        return size;
    }

    private void decrementSharedSize() {
        while (true) {
            int size = mSharedSize.get();
            if (size <= 0) {
                return;
            }
            if (mSharedSize.compareAndSet(size, size - 1)) {
                return;
            }
        }
    }
}
