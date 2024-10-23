// IMyAidlInterface1.aidl
package io.deepmedia.tools.grease.sample.library;

import io.deepmedia.tools.grease.sample.library.ActiveResultInfo;

interface IMyAidlInterface {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    void basicTypes(inout ActiveResultInfo info, long aLong, boolean aBoolean, float aFloat,
            double aDouble, String aString);
}