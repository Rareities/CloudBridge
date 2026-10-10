package ca.pkay.rcloneexplorer.util;

/** Implemented by native pipe streams so cancellation cannot commit a partial upload as success. */
public interface TransferControl {
    void abort();
}
