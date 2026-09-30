package com.wiyuka.acceleratedrecoiling.api;

public interface ICustomData {

    int getNativeId();
    void setNativeId(int id);

    void extractionBoundingBox(double[] doubleArray, int offset, double inflate);
    void extractionPosition(double[] doubleArray, int offset);

    void setDensity(float i);

    float getDensity();

    /** Version counter bumped on every setDeltaMovement; used for native body row dirty tracking. */
    int getEcoVelVersion();

    void setEcoVelVersion(int version);

    /** Per-EntitySection insertion counter (see EntitySectionMixin); feeds the native ordered build. */
    long getEcoSectionOrder();

    void setEcoSectionOrder(long order);
}