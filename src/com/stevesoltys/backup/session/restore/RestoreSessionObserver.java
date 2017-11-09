package com.stevesoltys.backup.session.restore;

/**
 * @author Steve Soltys
 */
public interface RestoreSessionObserver {

    void restoreSessionStarted(int packageCount);

    void restorePackageStarted(int packageIndex, String packageName);

    void restoreSessionCompleted(RestoreResult restoreResult);
}
