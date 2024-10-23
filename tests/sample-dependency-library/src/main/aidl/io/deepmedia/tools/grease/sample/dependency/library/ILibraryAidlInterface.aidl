// IMyAidlInterface1.aidl
package io.deepmedia.tools.grease.sample.dependency.library;

// Declare any non-default types here with import statements

interface ILibraryAidlInterface {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    void basicTypes(int anInt, long aLong, boolean aBoolean, float aFloat,
            double aDouble, String aString);
}