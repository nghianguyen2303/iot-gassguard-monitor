package com.iot.gas.domain.enums;

public enum RuleOperator {
    GT  { public boolean test(double v, double t) { return v >  t; } },
    GTE { public boolean test(double v, double t) { return v >= t; } },
    LT  { public boolean test(double v, double t) { return v <  t; } },
    LTE { public boolean test(double v, double t) { return v <= t; } };

    public abstract boolean test(double value, double threshold);
}