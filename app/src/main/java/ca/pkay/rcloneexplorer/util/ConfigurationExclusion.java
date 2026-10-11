package ca.pkay.rcloneexplorer.util;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Prevent an RC daemon from starting between restore admission and config replacement. */
public final class ConfigurationExclusion {
    private ConfigurationExclusion() {}
    public static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
}
